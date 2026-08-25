package pl.trikimusic.controller.data.media

import android.media.AudioManager
import android.media.ToneGenerator
import pl.trikimusic.controller.core.gesture.RatingGestureAction
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.LogCategory

class RatingFeedbackPlayer(
    private val logger: AppLogger,
) {
    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME_PERCENT)
    }.onFailure { error ->
        logger.log(LogCategory.MEDIA, "Nie udało się przygotować sygnałów oceny utworu.", error)
    }.getOrNull()

    @Synchronized
    fun play(action: RatingGestureAction, succeeded: Boolean) {
        val tone = when {
            !succeeded -> ToneGenerator.TONE_SUP_ERROR
            action == RatingGestureAction.LIKE -> ToneGenerator.TONE_PROP_ACK
            else -> ToneGenerator.TONE_PROP_NACK
        }
        val durationMillis = if (succeeded) SUCCESS_TONE_DURATION_MILLIS else ERROR_TONE_DURATION_MILLIS
        runCatching {
            check(toneGenerator?.startTone(tone, durationMillis) == true) {
                "Generator sygnału dźwiękowego nie przyjął polecenia."
            }
        }.onFailure { error ->
            logger.log(LogCategory.MEDIA, "Nie udało się odtworzyć sygnału oceny utworu.", error)
        }
    }

    private companion object {
        const val TONE_VOLUME_PERCENT = 55
        const val SUCCESS_TONE_DURATION_MILLIS = 130
        const val ERROR_TONE_DURATION_MILLIS = 180
    }
}
