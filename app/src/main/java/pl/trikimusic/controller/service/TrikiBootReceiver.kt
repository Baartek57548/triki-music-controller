package pl.trikimusic.controller.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.trikimusic.controller.TrikiMusicApplication
import pl.trikimusic.controller.domain.model.LogCategory

/** Restores passive auto-connect after a phone restart when the user left it enabled. */
class TrikiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val application = context.applicationContext as TrikiMusicApplication
        application.container.scope.launch {
            try {
                val settings = withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MILLIS) {
                    application.container.settingsRepository.settings.first()
                }
                if (settings?.backgroundEnabled == true && settings.knownDeviceAddress != null) {
                    runCatching { TrikiForegroundService.start(application) }
                        .onFailure { error ->
                            application.container.logger.log(
                                LogCategory.SERVICE,
                                "Android nie pozwolił przywrócić autołączenia po restarcie telefonu.",
                                error,
                            )
                        }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val SETTINGS_LOAD_TIMEOUT_MILLIS = 4_000L
    }
}
