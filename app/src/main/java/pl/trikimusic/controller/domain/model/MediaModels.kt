package pl.trikimusic.controller.domain.model

data class MediaSessionState(
    val hasPermission: Boolean = false,
    val hasActiveSession: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val canLike: Boolean = false,
    val canDislike: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 0,
    val isMuted: Boolean = false,
    val errorMessage: String? = null,
) {
    val volumePercent: Int
        get() = if (maxVolume > 0) ((volume.toFloat() / maxVolume.toFloat()) * 100f).toInt().coerceIn(0, 100) else 0
}
