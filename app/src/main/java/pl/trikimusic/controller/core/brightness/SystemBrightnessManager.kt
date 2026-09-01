package pl.trikimusic.controller.core.brightness

import android.content.Context
import android.provider.Settings
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.LogCategory

class SystemBrightnessManager(
    private val context: Context,
    private val logger: AppLogger,
) {
    private var cachedPercent: Float = 50f

    fun getBrightnessPercent(): Float {
        return try {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            )
            cachedPercent = (raw / 255f) * 100f
            cachedPercent
        } catch (error: Exception) {
            logger.log(LogCategory.SERVICE, "Nie udało się odczytać jasności ekranu: ${error.message}")
            cachedPercent
        }
    }

    fun setBrightnessPercent(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        cachedPercent = clamped
        try {
            val raw = ((clamped / 100f) * 255f).toInt().coerceIn(1, 255)
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    raw,
                )
            }
        } catch (error: Exception) {
            logger.log(LogCategory.SERVICE, "Nie udało się zapisać jasności ekranu: ${error.message}")
        }
    }

    fun stepBrightness(deltaPercent: Float) {
        val current = getBrightnessPercent()
        setBrightnessPercent(current + deltaPercent)
    }
}
