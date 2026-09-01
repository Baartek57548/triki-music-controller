package pl.trikimusic.controller.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.LogCategory

@Serializable
data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val is_active: Boolean,
    val volume_percent: Int? = null,
)

@Serializable
data class SpotifyDevicesResponse(
    val devices: List<SpotifyDevice> = emptyList(),
)

class SpotifyConnectService(
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var accessToken: String? = null
    private var selectedDeviceId: String? = null
    private var availableDevices: List<SpotifyDevice> = emptyList()

    fun setAccessToken(token: String?) {
        accessToken = token?.trim()
        if (accessToken.isNullOrBlank()) {
            availableDevices = emptyList()
            selectedDeviceId = null
        }
    }

    fun selectDevice(deviceId: String?) {
        selectedDeviceId = deviceId
    }

    fun getDevices(): List<SpotifyDevice> = availableDevices
    fun getSelectedDeviceId(): String? = selectedDeviceId

    suspend fun refreshDevices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext emptyList()
        try {
            val url = URL("https://api.spotify.com/v1/me/player/devices")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString<SpotifyDevicesResponse>(responseText)
                availableDevices = parsed.devices
                if (selectedDeviceId == null || availableDevices.none { it.id == selectedDeviceId }) {
                    selectedDeviceId = availableDevices.firstOrNull { it.is_active }?.id
                        ?: availableDevices.firstOrNull()?.id
                }
                availableDevices
            } else {
                emptyList()
            }
        } catch (error: Exception) {
            logger.log(LogCategory.SERVICE, "Błąd pobierania urządzeń Spotify Connect: ${error.message}")
            emptyList()
        }
    }

    suspend fun setVolume(volumePercent: Int): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext false
        val clamped = volumePercent.coerceIn(0, 100)
        try {
            var urlString = "https://api.spotify.com/v1/me/player/volume?volume_percent=$clamped"
            selectedDeviceId?.let { urlString += "&device_id=$it" }
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 4000
            }
            conn.responseCode in 200..299
        } catch (error: Exception) {
            logger.log(LogCategory.SERVICE, "Błąd głośności Spotify Connect: ${error.message}")
            false
        }
    }

    suspend fun nextTrack(): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext false
        try {
            var urlString = "https://api.spotify.com/v1/me/player/next"
            selectedDeviceId?.let { urlString += "?device_id=$it" }
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 4000
            }
            conn.responseCode in 200..299
        } catch (error: Exception) {
            logger.log(LogCategory.SERVICE, "Błąd następnego utworu Spotify Connect: ${error.message}")
            false
        }
    }

    suspend fun previousTrack(): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext false
        try {
            var urlString = "https://api.spotify.com/v1/me/player/previous"
            selectedDeviceId?.let { urlString += "?device_id=$it" }
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 4000
            }
            conn.responseCode in 200..299
        } catch (error: Exception) {
            logger.log(LogCategory.SERVICE, "Błąd poprzedniego utworu Spotify Connect: ${error.message}")
            false
        }
    }
}
