package pl.trikimusic.controller.core.bluetooth

import pl.trikimusic.controller.domain.model.MultiDeviceArbitrationMode

object MultiDeviceArbitrationPolicy {
    const val RECENT_PLAYBACK_WINDOW_MILLIS = 3 * 60 * 1000L
    const val IDLE_PLAYBACK_WINDOW_MILLIS = 10 * 60 * 1000L

    const val ACTIVE_PLAYBACK_DELAY_MILLIS = 0L
    const val FOREGROUND_OR_ACTIVE_USER_DELAY_MILLIS = 100L
    const val RECENT_PLAYBACK_DELAY_MILLIS = 200L
    const val STALE_PLAYBACK_DELAY_MILLIS = 500L
    const val IDLE_YIELD_DELAY_MILLIS = 1000L

    fun shouldAttemptConnection(
        mode: MultiDeviceArbitrationMode,
        isMediaPlaying: Boolean,
        isUserActiveOrForeground: Boolean,
    ): Boolean = when (mode) {
        MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING -> isMediaPlaying || isUserActiveOrForeground
        MultiDeviceArbitrationMode.ALWAYS_CONNECT -> true
        MultiDeviceArbitrationMode.MEDIA_PRIORITY -> true
    }

    fun calculateConnectionDelay(
        mode: MultiDeviceArbitrationMode,
        isMediaPlaying: Boolean,
        lastPlaybackTimeMillis: Long?,
        isUserActiveOrForeground: Boolean,
        nowMillis: Long,
    ): Long {
        if (mode == MultiDeviceArbitrationMode.ALWAYS_CONNECT) return 0L
        if (mode == MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING) return 0L

        // MediaPriority mode:
        if (isMediaPlaying) return ACTIVE_PLAYBACK_DELAY_MILLIS
        if (isUserActiveOrForeground) return FOREGROUND_OR_ACTIVE_USER_DELAY_MILLIS

        if (lastPlaybackTimeMillis != null) {
            val elapsed = (nowMillis - lastPlaybackTimeMillis).coerceAtLeast(0L)
            if (elapsed <= RECENT_PLAYBACK_WINDOW_MILLIS) return RECENT_PLAYBACK_DELAY_MILLIS
            if (elapsed <= IDLE_PLAYBACK_WINDOW_MILLIS) return STALE_PLAYBACK_DELAY_MILLIS
        }

        return IDLE_YIELD_DELAY_MILLIS
    }

    fun shouldYieldConnection(
        mode: MultiDeviceArbitrationMode,
        isMediaPlaying: Boolean,
        connectedDurationWithoutMediaMillis: Long,
    ): Boolean {
        if (isMediaPlaying) return false
        if (mode == MultiDeviceArbitrationMode.MEDIA_PRIORITY || mode == MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING) {
            return connectedDurationWithoutMediaMillis >= 10_000L
        }
        return false
    }
}
