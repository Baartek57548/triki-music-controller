package pl.trikimusic.controller.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.TrikiMusicApplication
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar

@Composable
fun PermissionsScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as TrikiMusicApplication).container
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshSystemState()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshSystemState()
    }
    Scaffold(topBar = { DetailTopBar("Uprawnienia", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Każdy dostęp ma konkretny cel. Aplikacja nie używa lokalizacji ani nie wysyła danych IMU poza telefon.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PermissionCard(
                icon = Icons.Default.Bluetooth,
                title = "Urządzenia w pobliżu",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "BLUETOOTH_SCAN wyszukuje Triki, a BLUETOOTH_CONNECT obsługuje GATT. Flaga neverForLocation deklaruje brak użycia do lokalizacji."
                } else {
                    "Android 8–11 wymaga lokalizacji podczas skanowania BLE; aplikacja nie pobiera pozycji GPS."
                },
                granted = state.permissions.bluetoothPermissionsGranted,
                actionLabel = "Nadaj dostęp",
                onAction = { bluetoothLauncher.launch(container.permissionManager.runtimeBluetoothPermissions()) },
            )
            if (!state.permissions.bluetoothEnabled) {
                PermissionCard(
                    icon = Icons.Default.Bluetooth,
                    title = "Bluetooth wyłączony",
                    description = "Włącz Bluetooth w ustawieniach systemowych, aby rozpocząć skanowanie.",
                    granted = false,
                    actionLabel = "Otwórz ustawienia",
                    onAction = { context.startActivity(container.permissionManager.bluetoothSettingsIntent()) },
                )
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !state.permissions.legacyLocationServicesEnabled) {
                PermissionCard(
                    icon = Icons.Default.Bluetooth,
                    title = "Usługa lokalizacji wyłączona",
                    description = "Android 8–11 nie zwróci wyników skanowania BLE przy wyłączonej systemowej usłudze lokalizacji. Triki Music nie odczytuje GPS.",
                    granted = false,
                    actionLabel = "Otwórz ustawienia",
                    onAction = { context.startActivity(container.permissionManager.locationSettingsIntent()) },
                )
            }
            PermissionCard(
                icon = Icons.Default.PlayCircle,
                title = "Informacje o odtwarzanym utworze",
                description = "Dostęp jest opcjonalny: pokazuje tytuł, wykonawcę i okładkę. Play/Pauza, Następny, Poprzedni oraz głośność działają także bez niego przez systemowe przyciski multimedialne.",
                granted = state.permissions.mediaSessionGranted,
                optional = true,
                actionLabel = "Opcjonalnie włącz",
                onAction = { context.startActivity(container.permissionManager.notificationListenerSettingsIntent()) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    title = "Powiadomienie usługi",
                    description = "Widoczne powiadomienie informuje o połączeniu działającym w tle i zawiera przycisk Rozłącz.",
                    granted = state.permissions.notificationGranted,
                    actionLabel = "Zezwól",
                    onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
            OutlinedButton(onClick = viewModel::refreshSystemState, modifier = Modifier.fillMaxWidth()) {
                Text("Sprawdź ponownie")
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    optional: Boolean = false,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(
                    when {
                        granted -> Icons.Default.CheckCircle
                        optional -> Icons.Default.Info
                        else -> Icons.Default.ErrorOutline
                    },
                    null,
                    tint = if (granted || optional) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
