package pl.trikimusic.controller.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.domain.model.AppLogEntry
import pl.trikimusic.controller.domain.model.LogCategory

class AppLogger(
    private val maxEntries: Int = 400,
) {
    init {
        require(maxEntries in 50..5_000)
    }

    private val mutableEntries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = mutableEntries.asStateFlow()

    fun log(category: LogCategory, message: String, throwable: Throwable? = null) {
        val sanitized = message.take(MAX_MESSAGE_LENGTH)
        mutableEntries.update { current ->
            (current + AppLogEntry(System.currentTimeMillis(), category, sanitized)).takeLast(maxEntries)
        }
        if (BuildConfig.DEBUG) {
            if (throwable == null) {
                Log.d("Triki/${category.name}", sanitized)
            } else {
                Log.e("Triki/${category.name}", sanitized, throwable)
            }
        }
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 1_000
    }
}
