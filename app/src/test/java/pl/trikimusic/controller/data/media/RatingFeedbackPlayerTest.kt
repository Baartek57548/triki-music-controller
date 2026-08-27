package pl.trikimusic.controller.data.media

import android.media.ToneGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.MediaAction

class RatingFeedbackPlayerTest {
    @Test
    fun likeUsesShortPositiveConfirmation() {
        val signal = RatingFeedbackPlayer.signalFor(MediaAction.LIKE)

        assertEquals(ToneGenerator.TONE_PROP_ACK, signal.tone)
        assertTrue(signal.durationMillis in 80..150)
    }

    @Test
    fun dislikeUsesShortNegativeConfirmation() {
        val signal = RatingFeedbackPlayer.signalFor(MediaAction.DISLIKE)

        assertEquals(ToneGenerator.TONE_PROP_NACK, signal.tone)
        assertTrue(signal.durationMillis in 80..150)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonRatingActionHasNoConfirmationSignal() {
        RatingFeedbackPlayer.signalFor(MediaAction.PLAY_PAUSE)
    }
}
