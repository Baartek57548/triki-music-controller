package pl.trikimusic.controller.data.update

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.trikimusic.controller.domain.model.AppUpdateInfo
import pl.trikimusic.controller.domain.model.SemanticVersion

internal object ReleaseMetadataParser {
    private const val REPOSITORY_PATH = "/Baartek57548/triki-music-controller"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, currentVersion: SemanticVersion): AppUpdateInfo? {
        val release = try {
            json.decodeFromString<Release>(raw)
        } catch (error: Exception) {
            throw UpdateException("GitHub zwrócił nieprawidłowe metadane wydania.", error)
        }
        if (release.draft || release.prerelease) return null
        val version = SemanticVersion.parse(release.tag)
            ?: throw UpdateException("Nieprawidłowy tag wydania: ${release.tag}")
        if (version <= currentVersion) return null
        val expectedName = "triki-music-controller-android-v$version-release.apk"
        val asset = release.assets.singleOrNull { it.name == expectedName && it.state == "uploaded" }
            ?: throw UpdateException("Wydanie nie zawiera jednego podpisanego APK: $expectedName")
        if (asset.size !in 1L..100L * 1_024 * 1_024) throw UpdateException("Nieprawidłowy rozmiar APK.")
        validateUrl(asset.url, "$REPOSITORY_PATH/releases/download/${release.tag}/${asset.name}")
        validateUrl(release.url, "$REPOSITORY_PATH/releases/tag/${release.tag}")
        val hash = asset.digest?.takeIf { it.startsWith("sha256:", true) }?.substringAfter(':')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw UpdateException("GitHub nie udostępnił prawidłowej sumy SHA-256 APK.")
        return AppUpdateInfo(
            versionName = version.toString(), tagName = release.tag,
            releaseName = release.name.ifBlank { release.tag }, releaseNotes = release.body.orEmpty().trim().take(4_000),
            releaseUrl = release.url, apkDownloadUrl = asset.url, apkFileName = asset.name,
            apkSizeBytes = asset.size, apkSha256 = hash,
        )
    }

    private fun validateUrl(value: String, path: String) {
        val uri = try { URI(value) } catch (error: Exception) { throw UpdateException("Nieprawidłowy adres wydania.", error) }
        if (!uri.scheme.equals("https", true) || !uri.host.equals("github.com", true) || uri.path != path ||
            uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) {
            throw UpdateException("Adres aktualizacji nie zgadza się z repozytorium i tagiem wydania.")
        }
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val url: String,
        val name: String = "", val body: String? = null,
        val draft: Boolean = false, val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String, val state: String = "", val size: Long, val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )
}
