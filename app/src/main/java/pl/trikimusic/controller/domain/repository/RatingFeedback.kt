package pl.trikimusic.controller.domain.repository

import pl.trikimusic.controller.domain.model.MediaAction

fun interface RatingFeedback {
    fun play(action: MediaAction)
}
