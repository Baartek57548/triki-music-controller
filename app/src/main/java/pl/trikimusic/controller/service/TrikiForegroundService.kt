package pl.trikimusic.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.trikimusic.controller.MainActivity
import pl.trikimusic.controller.R
import pl.trikimusic.controller.TrikiMusicApplication
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.TrikiConnectionState

class TrikiForegroundService : Service() {
    private val container by lazy { (application as TrikiMusicApplication).container }
    private var stateJob: Job? = null
    private var bootstrapJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            container.bleManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(container.bleManager.state.value.connectionState))
        container.logger.log(LogCategory.SERVICE, "Uruchomiono usługę połączenia w tle.")
        bootstrapJob?.cancel()
        bootstrapJob = container.scope.launch {
            val persisted = withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MILLIS) {
                container.settingsRepository.settings.first()
            } ?: container.settings.value
            val knownAddress = persisted.knownDeviceAddress
            if (knownAddress != null && container.bleManager.state.value.connectionState == TrikiConnectionState.DISCONNECTED) {
                container.bleManager.autoConnectKnown(knownAddress)
            }
        }
        stateJob?.cancel()
        bootstrapJob?.cancel()
        stateJob = container.scope.launch {
            container.bleManager.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(state.connectionState),
                )
                if (state.connectionState in setOf(TrikiConnectionState.DISCONNECTED, TrikiConnectionState.ERROR)) {
                    delay(IDLE_STOP_DELAY_MILLIS)
                    if (container.bleManager.state.value.connectionState in setOf(TrikiConnectionState.DISCONNECTED, TrikiConnectionState.ERROR)) {
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        container.logger.log(LogCategory.SERVICE, "Zatrzymano usługę połączenia w tle.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: TrikiConnectionState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrikiForegroundService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            TrikiConnectionState.READY -> getString(R.string.notification_connected)
            TrikiConnectionState.RECONNECTING,
            TrikiConnectionState.SCANNING,
            TrikiConnectionState.CONNECTING,
            -> getString(R.string.notification_reconnecting)
            else -> "Triki: ${state.name.lowercase()}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(state != TrikiConnectionState.DISCONNECTED && state != TrikiConnectionState.ERROR)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notification_disconnect), disconnectIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "triki_connection"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_DISCONNECT = "pl.trikimusic.controller.action.DISCONNECT"
        private const val IDLE_STOP_DELAY_MILLIS = 5_000L
        private const val SETTINGS_LOAD_TIMEOUT_MILLIS = 2_000L

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrikiForegroundService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TrikiForegroundService::class.java))
        }
    }
}
