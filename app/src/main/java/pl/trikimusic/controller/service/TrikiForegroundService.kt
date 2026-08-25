package pl.trikimusic.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var bluetoothReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> container.bleManager.disconnect(forgetReconnect = false)
                BluetoothAdapter.STATE_ON -> bootstrapAutoConnect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bluetoothReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            container.bleManager.disconnect()
            container.scope.launch {
                runCatching { container.settingsRepository.setBackgroundEnabled(false) }
                    .onFailure { error ->
                        container.logger.log(
                            LogCategory.SERVICE,
                            "Nie udało się trwale wyłączyć autołączenia.",
                            error,
                        )
                    }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(container.bleManager.state.value.connectionState))
        container.logger.log(LogCategory.SERVICE, "Uruchomiono usługę autołączenia Triki w tle.")
        stateJob?.cancel()
        stateJob = container.scope.launch {
            container.bleManager.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(state.connectionState),
                )
                if (state.connectionState in setOf(TrikiConnectionState.DISCONNECTED, TrikiConnectionState.ERROR)) {
                    delay(IDLE_STOP_DELAY_MILLIS)
                    val settings = container.settings.value
                    if (
                        container.bleManager.state.value.connectionState in
                        setOf(TrikiConnectionState.DISCONNECTED, TrikiConnectionState.ERROR) &&
                        (!settings.backgroundEnabled || settings.knownDeviceAddress == null)
                    ) {
                        stopSelf()
                    }
                }
            }
        }
        bootstrapAutoConnect(startId)
        return START_STICKY
    }

    private fun bootstrapAutoConnect(startId: Int? = null) {
        bootstrapJob?.cancel()
        bootstrapJob = container.scope.launch {
            val persisted = withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MILLIS) {
                container.settingsRepository.settings.first()
            } ?: container.settings.value
            val knownAddress = persisted.knownDeviceAddress
            if (!persisted.backgroundEnabled) {
                if (startId != null) stopSelf(startId) else stopSelf()
            } else if (
                knownAddress != null &&
                container.bleManager.state.value.connectionState in
                setOf(TrikiConnectionState.DISCONNECTED, TrikiConnectionState.ERROR)
            ) {
                container.bleManager.autoConnectKnown(knownAddress, persisted.knownDeviceName)
            } else if (knownAddress == null && container.bleManager.state.value.connectionState == TrikiConnectionState.DISCONNECTED) {
                if (startId != null) stopSelf(startId) else stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        bootstrapJob?.cancel()
        if (bluetoothReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        container.logger.log(LogCategory.SERVICE, "Zatrzymano usługę autołączenia Triki w tle.")
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
            TrikiConnectionState.RECONNECTING -> getString(R.string.notification_waiting_for_wake)
            TrikiConnectionState.SCANNING,
            TrikiConnectionState.CONNECTING,
            -> getString(R.string.notification_reconnecting)
            else -> if (container.permissionManager.state().bluetoothEnabled) {
                getString(R.string.notification_autoconnect_inactive)
            } else {
                getString(R.string.notification_bluetooth_off)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
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
