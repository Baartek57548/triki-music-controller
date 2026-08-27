package pl.trikimusic.controller.data.media

import android.media.AudioManager
import android.media.ToneGenerator
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.repository.RatingFeedback

class RatingFeedbackPlayer(
    private val logger: AppLogger,
) : RatingFeedback {
    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME_PERCENT)
    }.onFailure { error ->
        logger.log(LogCategory.MEDIA, "Nie udało się przygotować sygnałów oceny utworu.", error)
    }.getOrNull()

    @Synchronized
    override fun play(action: MediaAction) {
        val signal = signalFor(action)
        runCatching {
            check(toneGenerator?.startTone(signal.tone, signal.durationMillis) == true) {
                "Generator sygnału dźwiękowego nie przyjął polecenia."
            }
        }.onFailure { error ->
            logger.log(LogCategory.MEDIA, "Nie udało się odtworzyć sygnału oceny utworu.", error)
        }
    }

    internal data class RatingSignal(
        val tone: Int,
        val durationMillis: Int,
    )

    internal companion object {
        const val TONE_VOLUME_PERCENT = 55
        const val LIKE_TONE_DURATION_MILLIS = 110
        const val DISLIKE_TONE_DURATION_MILLIS = 120

        fun signalFor(action: MediaAction): RatingSignal = when (action) {
            MediaAction.LIKE -> RatingSignal(ToneGenerator.TONE_PROP_ACK, LIKE_TONE_DURATION_MILLIS)
            MediaAction.DISLIKE -> RatingSignal(ToneGenerator.TONE_PROP_NACK, DISLIKE_TONE_DURATION_MILLIS)
            else -> throw IllegalArgumentException("Sygnał oceny wymaga akcji Like albo Dislike.")
        }
    }
}
