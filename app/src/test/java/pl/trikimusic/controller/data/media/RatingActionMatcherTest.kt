package pl.trikimusic.controller.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.MediaAction

class RatingActionMatcherTest {
    @Test
    fun `spotify style identifiers are recognized`() {
        val like = RatingActionMatcher.score(
            "com.spotify.mobile.android.THUMB_UP",
            "Like",
            MediaAction.LIKE,
        )
        val dislike = RatingActionMatcher.score(
            "com.spotify.mobile.android.THUMB_DOWN",
            "Dislike",
            MediaAction.DISLIKE,
        )

        assertTrue(like > 0)
        assertTrue(dislike > 0)
    }

    @Test
    fun `dislike can never be mistaken for like`() {
        val score = RatingActionMatcher.score(
            "player.action.DISLIKE",
            "Dislike",
            MediaAction.LIKE,
        )

        assertEquals(0, score)
    }

    @Test
    fun `unrelated custom action is ignored`() {
        val score = RatingActionMatcher.score(
            "player.action.SHUFFLE",
            "Losowo",
            MediaAction.LIKE,
        )

        assertEquals(0, score)
    }
}
