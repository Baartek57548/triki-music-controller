package pl.trikimusic.controller.core.permissions

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.trikimusic.controller.service.TrikiNotificationListenerService

data class PermissionState(
    val bluetoothSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val scanGranted: Boolean,
    val connectGranted: Boolean,
    val notificationGranted: Boolean,
    val mediaSessionGranted: Boolean,
    val legacyLocationServicesEnabled: Boolean,
) {
    val bluetoothPermissionsGranted: Boolean
        get() = scanGranted && connectGranted
}

class PermissionManager(private val context: Context) {
    fun state(): PermissionState {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return PermissionState(
            bluetoothSupported = adapter != null,
            bluetoothEnabled = adapter?.isEnabled == true,
            scanGranted = hasPermission(scanPermission()),
            connectGranted = hasPermission(connectPermission()),
            notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(Manifest.permission.POST_NOTIFICATIONS),
            mediaSessionGranted = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName),
            legacyLocationServicesEnabled = legacyLocationServicesEnabled(),
        )
    }

    fun runtimeBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun bluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun notificationListenerSettingsIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, TrikiNotificationListenerService::class.java).flattenToString(),
            )
        }
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    private fun scanPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_SCAN
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    private fun connectPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        Manifest.permission.BLUETOOTH
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun legacyLocationServicesEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    }
}
