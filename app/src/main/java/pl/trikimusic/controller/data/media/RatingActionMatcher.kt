package pl.trikimusic.controller.data.media

import java.util.Locale
import pl.trikimusic.controller.domain.model.MediaAction

object RatingActionMatcher {
    fun score(actionId: String, displayName: CharSequence, target: MediaAction): Int {
        require(target == MediaAction.LIKE || target == MediaAction.DISLIKE)
        val id = normalize(actionId)
        val name = normalize(displayName.toString())
        val combined = "$id $name"
        val negativeMatch = NEGATIVE_KEYWORDS.maxOfOrNull { keyword ->
            keywordScore(id, name, combined, keyword)
        } ?: 0
        val positiveMatch = POSITIVE_KEYWORDS.maxOfOrNull { keyword ->
            keywordScore(id, name, combined, keyword)
        } ?: 0
        return when (target) {
            MediaAction.LIKE -> if (negativeMatch > 0) 0 else positiveMatch
            MediaAction.DISLIKE -> negativeMatch
            else -> 0
        }
    }

    private fun keywordScore(id: String, name: String, combined: String, keyword: String): Int = when {
        id == keyword || name == keyword -> 100
        id.endsWith(" $keyword") || name.endsWith(" $keyword") -> 80
        containsWholePhrase(combined, keyword) -> 60
        else -> 0
    }

    private fun containsWholePhrase(value: String, phrase: String): Boolean =
        " $value ".contains(" $phrase ")

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private val POSITIVE_KEYWORDS = listOf(
        "like",
        "thumb up",
        "thumbup",
        "favorite",
        "favourite",
        "heart",
        "save",
        "polub",
        "lubię",
        "ulubione",
    )
    private val NEGATIVE_KEYWORDS = listOf(
        "dislike",
        "thumb down",
        "thumbdown",
        "unlike",
        "unfavorite",
        "not interested",
        "ban",
        "downvote",
        "nie lubię",
        "odrzuć",
    )
}
