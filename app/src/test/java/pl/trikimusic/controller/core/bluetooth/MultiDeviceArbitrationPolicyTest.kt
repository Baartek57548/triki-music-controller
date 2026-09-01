package pl.trikimusic.controller.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.MultiDeviceArbitrationMode

class MultiDeviceArbitrationPolicyTest {

    @Test
    fun activeMediaPlaybackHasZeroDelayAndHighestPriority() {
        val now = 1_000_000_000L
        val delay = MultiDeviceArbitrationPolicy.calculateConnectionDelay(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = true,
            lastPlaybackTimeMillis = null,
            isUserActiveOrForeground = false,
            nowMillis = now,
        )

        assertEquals(0L, delay)
    }

    @Test
    fun foregroundOrActiveUserHasNearInstantDelayWhenNotPlaying() {
        val now = 1_000_000_000L
        val delay = MultiDeviceArbitrationPolicy.calculateConnectionDelay(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = false,
            lastPlaybackTimeMillis = null,
            isUserActiveOrForeground = true,
            nowMillis = now,
        )

        assertEquals(100L, delay)
    }

    @Test
    fun recentPlaybackWithin3MinutesHasShortDelay() {
        val now = 1_000_000_000L
        val lastPlayback = now - (2 * 60 * 1000L)
        val delay = MultiDeviceArbitrationPolicy.calculateConnectionDelay(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = false,
            lastPlaybackTimeMillis = lastPlayback,
            isUserActiveOrForeground = false,
            nowMillis = now,
        )

        assertEquals(200L, delay)
    }

    @Test
    fun stalePlaybackWithin10MinutesHasMediumDelay() {
        val now = 1_000_000_000L
        val lastPlayback = now - (6 * 60 * 1000L)
        val delay = MultiDeviceArbitrationPolicy.calculateConnectionDelay(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = false,
            lastPlaybackTimeMillis = lastPlayback,
            isUserActiveOrForeground = false,
            nowMillis = now,
        )

        assertEquals(500L, delay)
    }

    @Test
    fun idleDeviceWithNoRecentPlaybackYieldsWith1SecondDelay() {
        val now = 1_000_000_000L
        val lastPlayback = now - (60 * 60 * 1000L)
        val delay = MultiDeviceArbitrationPolicy.calculateConnectionDelay(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = false,
            lastPlaybackTimeMillis = lastPlayback,
            isUserActiveOrForeground = false,
            nowMillis = now,
        )

        assertEquals(1000L, delay)
    }

    @Test
    fun alwaysConnectModeAlwaysHasZeroDelay() {
        val now = 1_000_000_000L
        val delay = MultiDeviceArbitrationPolicy.calculateConnectionDelay(
            MultiDeviceArbitrationMode.ALWAYS_CONNECT,
            isMediaPlaying = false,
            lastPlaybackTimeMillis = null,
            isUserActiveOrForeground = false,
            nowMillis = now,
        )

        assertEquals(0L, delay)
    }

    @Test
    fun onlyWhenPlayingModeBlocksAttemptWhenNotPlayingAndInactive() {
        val shouldAttempt = MultiDeviceArbitrationPolicy.shouldAttemptConnection(
            MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING,
            isMediaPlaying = false,
            isUserActiveOrForeground = false,
        )

        assertFalse(shouldAttempt)
    }

    @Test
    fun onlyWhenPlayingModeAllowsAttemptWhenPlaying() {
        val shouldAttempt = MultiDeviceArbitrationPolicy.shouldAttemptConnection(
            MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING,
            isMediaPlaying = true,
            isUserActiveOrForeground = false,
        )

        assertTrue(shouldAttempt)
    }

    @Test
    fun yieldsConnectionAfter10SecondsOfInactivityWithoutMedia() {
        val shouldYieldShort = MultiDeviceArbitrationPolicy.shouldYieldConnection(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = false,
            connectedDurationWithoutMediaMillis = 5_000L,
        )
        assertFalse(shouldYieldShort)

        val shouldYieldLong = MultiDeviceArbitrationPolicy.shouldYieldConnection(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = false,
            connectedDurationWithoutMediaMillis = 11_000L,
        )
        assertTrue(shouldYieldLong)

        val shouldYieldWhilePlaying = MultiDeviceArbitrationPolicy.shouldYieldConnection(
            MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            isMediaPlaying = true,
            connectedDurationWithoutMediaMillis = 20_000L,
        )
        assertFalse(shouldYieldWhilePlaying)
    }
}
