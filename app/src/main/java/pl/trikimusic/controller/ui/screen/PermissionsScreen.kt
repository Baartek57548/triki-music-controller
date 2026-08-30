package pl.trikimusic.controller.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.TrikiMusicApplication
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.TrikiCard

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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Nadaj uprawnienia wymagane do komunikacji Bluetooth oraz opcjonalne do odczytu metadanych odtwarzacza.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PermissionCard(
                icon = Icons.Default.Bluetooth,
                title = "Urządzenia Bluetooth w pobliżu",
                description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Wymagane do wykrywania i utrzymywania połączenia z kapslem Triki."
                } else {
                    "Android 8–11 wymaga tego uprawnienia podczas wyszukiwania urządzeń BLE."
                },
                granted = state.permissions.bluetoothPermissionsGranted,
                actionLabel = "Nadaj uprawnienie",
                onAction = { bluetoothLauncher.launch(container.permissionManager.runtimeBluetoothPermissions()) },
            )

            if (!state.permissions.bluetoothEnabled) {
                PermissionCard(
                    icon = Icons.Default.Bluetooth,
                    title = "Moduł Bluetooth wyłączony",
                    description = "Włącz Bluetooth w ustawieniach systemu, aby aplikacja mogła połączyć się z kontrolerem.",
                    granted = false,
                    actionLabel = "Otwórz ustawienia Bluetooth",
                    onAction = { context.startActivity(container.permissionManager.bluetoothSettingsIntent()) },
                )
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !state.permissions.legacyLocationServicesEnabled) {
                PermissionCard(
                    icon = Icons.Default.Bluetooth,
                    title = "Lokalizacja systemowa wyłączona",
                    description = "Android 8–11 wymaga włączonej lokalizacji do skanowania BLE (aplikacja nie odczytuje danych GPS).",
                    granted = false,
                    actionLabel = "Otwórz ustawienia lokalizacji",
                    onAction = { context.startActivity(container.permissionManager.locationSettingsIntent()) },
                )
            }

            PermissionCard(
                icon = Icons.Default.PlayCircle,
                title = "Dostęp do powiadomień muzycznych",
                description = "Umożliwia odczyt tytułu utworu, artysty oraz okładki albumu. Podstawowe sterowanie działa także bez tego uprawnienia.",
                granted = state.permissions.mediaSessionGranted,
                optional = true,
                actionLabel = "Włącz dostęp do odtwarzacza",
                onAction = { context.startActivity(container.permissionManager.notificationListenerSettingsIntent()) },
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    title = "Powiadomienia usługi pierwszoplanowej",
                    description = "Pozwala aplikacji wyświetlać stały status kontrolera w panelu powiadomień podczas działania w tle.",
                    granted = state.permissions.notificationGranted,
                    actionLabel = "Zezwól na powiadomienia",
                    onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            OutlinedButton(
                onClick = viewModel::refreshSystemState,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Odśwież stan uprawnień", fontWeight = FontWeight.SemiBold)
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
    val statusLabel = when {
        granted -> "Przyznano"
        optional -> "Opcjonalne"
        else -> "Wymagane"
    }
    val statusColor = if (granted || optional) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    TrikiCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Icon(icon, null, modifier = Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
                }
                Icon(
                    when {
                        granted -> Icons.Default.CheckCircle
                        optional -> Icons.Default.Info
                        else -> Icons.Default.ErrorOutline
                    },
                    contentDescription = statusLabel,
                    tint = statusColor,
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(actionLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

