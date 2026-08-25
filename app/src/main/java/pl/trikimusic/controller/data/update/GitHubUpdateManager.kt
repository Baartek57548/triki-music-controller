package pl.trikimusic.controller.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.AppUpdateInfo
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.SemanticVersion

class GitHubUpdateManager(
    private val context: Context,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(currentVersionName: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val currentVersion = SemanticVersion.parse(currentVersionName)
            ?: throw UpdateException("Nieprawidłowa wersja bieżącej aplikacji: $currentVersionName")
        val response = getText(LATEST_RELEASE_API_URL)
        val release = runCatching { json.decodeFromString(GitHubRelease.serializer(), response) }
            .getOrElse { error -> throw UpdateException("GitHub zwrócił nieprawidłowe metadane wydania.", error) }
        val update = release.toUpdateInfo(currentVersion)
        logger.log(
            LogCategory.UPDATE,
            update?.let { "Dostępna aktualizacja ${it.versionName}." }
                ?: "Aplikacja jest aktualna ($currentVersionName).",
        )
        update
    }

    suspend fun downloadAndVerify(
        update: AppUpdateInfo,
        currentVersionCode: Long,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        validateAssetUrl(update.apkDownloadUrl)
        require(update.apkSizeBytes in 1L..MAX_APK_SIZE_BYTES) { "Nieprawidłowy rozmiar APK." }

        val updateDirectory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY).apply {
            check(exists() || mkdirs()) { "Nie udało się przygotować katalogu aktualizacji." }
        }
        val safeVersion = update.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val target = File(updateDirectory, "triki-update-$safeVersion.apk")
        val temporary = File(updateDirectory, "triki-update-$safeVersion.apk.part")
        temporary.delete()
        target.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var downloadedBytes = 0L
        val connection = openConnection(update.apkDownloadUrl, accept = APK_MIME_TYPE)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw UpdateException("Pobieranie APK zakończyło się kodem HTTP $responseCode.")
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APK_SIZE_BYTES) {
                throw UpdateException("Plik aktualizacji przekracza dozwolony rozmiar.")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloadedBytes += count
                        if (downloadedBytes > MAX_APK_SIZE_BYTES) {
                            throw UpdateException("Plik aktualizacji przekracza dozwolony rozmiar.")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress((downloadedBytes.toDouble() / update.apkSizeBytes).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        if (downloadedBytes != update.apkSizeBytes) {
            temporary.delete()
            throw UpdateException("Pobrany APK ma nieprawidłowy rozmiar ($downloadedBytes B).")
        }
        val actualSha256 = digest.digest().toHex()
        update.apkSha256?.let { expected ->
            if (!actualSha256.equals(expected, ignoreCase = true)) {
                temporary.delete()
                throw UpdateException("Suma SHA-256 pobranego APK jest nieprawidłowa.")
            }
        }
        verifyApkIdentity(temporary, currentVersionCode)
        check(temporary.renameTo(target)) {
            temporary.delete()
            "Nie udało się zatwierdzić pobranej aktualizacji."
        }
        onProgress(1f)
        logger.log(LogCategory.UPDATE, "Pobrano i zweryfikowano ${update.versionName}, sha256=$actualSha256.")
        target
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(): Result<Unit> = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { error ->
        logger.log(LogCategory.UPDATE, "Nie udało się otworzyć ustawień instalowania aplikacji.", error)
    }

    fun launchInstaller(apk: File): Result<Unit> = runCatching {
        require(apk.isFile && apk.length() > 0L) { "Pobrany APK nie jest dostępny." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Aktualizacja Triki Music", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        logger.log(LogCategory.UPDATE, "Uruchomiono systemowy instalator aktualizacji.")
    }.onFailure { error ->
        logger.log(LogCategory.UPDATE, "Nie udało się uruchomić instalatora aktualizacji.", error)
    }

    fun deleteDownloadedUpdate(apk: File?) {
        if (apk == null) return
        runCatching {
            val trustedDirectory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY).canonicalFile
            if (apk.canonicalFile.parentFile == trustedDirectory && apk.exists() && !apk.delete()) {
                throw IOException("Nie udało się usunąć pliku aktualizacji z pamięci podręcznej.")
            }
        }.onFailure { error ->
            logger.log(LogCategory.UPDATE, "Nie udało się wyczyścić pliku aktualizacji.", error)
        }
    }

    private fun getText(url: String): String {
        val connection = openConnection(url, accept = GITHUB_JSON_MIME_TYPE)
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw UpdateException("Sprawdzanie aktualizacji zakończyło się kodem HTTP $responseCode.")
            }
            val builder = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(JSON_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    builder.append(buffer, 0, count)
                    if (builder.length > MAX_RELEASE_JSON_CHARS) {
                        throw UpdateException("Metadane wydania są zbyt duże.")
                    }
                }
            }
            builder.toString()
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Aktualizacje wymagają połączenia HTTPS." }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
        }
    }

    private fun GitHubRelease.toUpdateInfo(currentVersion: SemanticVersion): AppUpdateInfo? {
        if (draft || prerelease) return null
        val releaseVersion = SemanticVersion.parse(tagName)
            ?: throw UpdateException("Tag najnowszego wydania ma nieprawidłowy format: $tagName")
        if (releaseVersion <= currentVersion) return null

        val candidates = assets.filter { asset ->
            asset.state.equals("uploaded", ignoreCase = true) &&
                asset.name.endsWith(".apk", ignoreCase = true) &&
                !asset.name.contains("debug", ignoreCase = true) &&
                asset.size in 1L..MAX_APK_SIZE_BYTES
        }
        val asset = candidates.singleOrNull()
            ?: candidates.singleOrNull { it.name.startsWith("triki-music-controller", ignoreCase = true) }
            ?: throw UpdateException("Wydanie $tagName nie zawiera jednoznacznego pliku APK.")
        validateAssetUrl(asset.browserDownloadUrl)
        val digest = asset.digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        return AppUpdateInfo(
            versionName = releaseVersion.toString(),
            tagName = tagName,
            releaseName = name.ifBlank { tagName },
            releaseNotes = body.trim().take(MAX_RELEASE_NOTES_CHARS),
            releaseUrl = htmlUrl,
            apkDownloadUrl = asset.browserDownloadUrl,
            apkFileName = asset.name,
            apkSizeBytes = asset.size,
            apkSha256 = digest,
        )
    }

    private fun validateAssetUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw UpdateException("Nieprawidłowy adres APK.", it) }
        val expectedPathPrefix = "/$GITHUB_OWNER/$GITHUB_REPOSITORY/releases/download/"
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.path.startsWith(expectedPathPrefix) &&
                uri.path.endsWith(".apk", ignoreCase = true),
        ) { "Adres APK nie wskazuje na zaufane wydanie projektu." }
    }

    private fun verifyApkIdentity(apk: File, currentVersionCode: Long) {
        val packageManager = context.packageManager
        val archive = getArchivePackageInfo(packageManager, apk)
            ?: throw UpdateException("Android nie rozpoznaje pobranego pliku jako APK.")
        if (archive.packageName != context.packageName) {
            throw UpdateException("APK należy do innej aplikacji (${archive.packageName}).")
        }
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archive)
        if (archiveVersionCode <= currentVersionCode) {
            throw UpdateException("APK nie ma wyższego versionCode ($archiveVersionCode).")
        }
        val installed = getInstalledPackageInfo(packageManager)
        val installedSigners = signingCertificateDigests(installed)
        val archiveSigners = signingCertificateDigests(archive)
        if (installedSigners.isEmpty() || installedSigners != archiveSigners) {
            throw UpdateException("Certyfikat podpisujący aktualizację nie zgadza się z zainstalowaną aplikacją.")
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(packageManager: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(packageManager: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            packageManager.getPackageInfo(context.packageName, flags)
        }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String = "",
        val body: String = "",
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val state: String = "",
        val size: Long,
        val digest: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val GITHUB_OWNER = "Baartek57548"
        const val GITHUB_REPOSITORY = "triki-music-controller"
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPOSITORY/releases/latest"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val GITHUB_JSON_MIME_TYPE = "application/vnd.github+json"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val USER_AGENT = "Triki-Music-Controller-Android"
        const val UPDATE_CACHE_DIRECTORY = "updates"
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val DOWNLOAD_BUFFER_SIZE = 32 * 1_024
        const val JSON_BUFFER_SIZE = 4 * 1_024
        const val MAX_RELEASE_JSON_CHARS = 1_000_000
        const val MAX_RELEASE_NOTES_CHARS = 4_000
        const val MAX_APK_SIZE_BYTES = 100L * 1_024L * 1_024L
    }
}

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
