package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiDevice
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.LoadingInline
import pl.trikimusic.controller.ui.components.NavigationRow
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.StatusPill

@Composable
fun DeviceScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    viewModel: MainViewModel,
    onCalibration: () -> Unit,
    onSensor: () -> Unit,
    onInspector: () -> Unit,
    onPermissions: () -> Unit,
) {
    var ledOn by remember { mutableStateOf(false) }
    val working = state.ble.connectionState in setOf(
        TrikiConnectionState.SCANNING,
        TrikiConnectionState.CONNECTING,
        TrikiConnectionState.RECONNECTING,
        TrikiConnectionState.CONNECTED,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 22.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                            Text(state.ble.selectedDevice?.name ?: state.settings.knownDeviceName ?: "Triki", style = MaterialTheme.typography.titleLarge)
                            Text(
                                state.ble.selectedDevice?.address ?: state.settings.knownDeviceAddress ?: "Brak zapamiętanego urządzenia",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(state.ble.connectionState)
                    }
                    LoadingInline(
                        when (state.ble.connectionState) {
                            TrikiConnectionState.SCANNING -> "Szukam aktywnego Triki…"
                            TrikiConnectionState.CONNECTING -> "Nawiązuję połączenie GATT…"
                            TrikiConnectionState.CONNECTED -> "Odczytuję usługi i informacje…"
                            TrikiConnectionState.RECONNECTING -> "Naciśnij przycisk Triki — telefon czeka na jego wybudzenie…"
                            else -> ""
                        },
                        working,
                    )
                    state.ble.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (state.ble.connectionState) {
                            TrikiConnectionState.READY -> {
                                Button(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) { Text("Rozłącz") }
                                OutlinedButton(
                                    onClick = { ledOn = !ledOn; viewModel.setLed(ledOn) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.Lightbulb, null)
                                    Text(if (ledOn) " Zgaś LED" else " Zapal LED")
                                }
                            }

                            TrikiConnectionState.RECONNECTING,
                            TrikiConnectionState.CONNECTING,
                            TrikiConnectionState.CONNECTED,
                            -> Button(
                                onClick = if (state.ble.connectionState == TrikiConnectionState.RECONNECTING) {
                                    viewModel::disableAutoConnect
                                } else {
                                    viewModel::disconnect
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.ble.connectionState == TrikiConnectionState.RECONNECTING) "Wyłącz autołączenie" else "Anuluj")
                            }

                            else -> {
                                Button(
                                    onClick = { if (state.permissions.bluetoothPermissionsGranted) viewModel.startScan() else onPermissions() },
                                    enabled = !working,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, null)
                                    Text(" Skanuj urządzenia")
                                }
                            }
                        }
                    }
                    if (state.settings.knownDeviceAddress != null) {
                        OutlinedButton(onClick = viewModel::forgetDevice, modifier = Modifier.fillMaxWidth()) {
                            Text("Zapomnij urządzenie")
                        }
                    }
                }
            }
        }

        if (state.ble.discoveredDevices.isNotEmpty() && state.ble.connectionState != TrikiConnectionState.READY) {
            item { SectionTitle("Znalezione") }
            items(state.ble.discoveredDevices, key = TrikiDevice::address) { device ->
                DeviceResult(device = device, onConnect = { viewModel.connect(device) })
            }
        } else if (state.ble.connectionState == TrikiConnectionState.FOUND && state.ble.discoveredDevices.isEmpty()) {
            item { EmptyState("Brak wyników", "Naciśnij przycisk na kapslu, aby go wybudzić, i ponów skanowanie.") }
        }

        if (state.ble.connectionState == TrikiConnectionState.READY) {
            item { SectionTitle("Parametry urządzenia") }
            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoRow("Bateria", state.ble.battery.percent?.let { "$it%" } ?: "Nieudostępniona")
                        InfoRow("RSSI", state.ble.rssi?.let { "$it dBm" } ?: "Oczekiwanie")
                        InfoRow("Próbkowanie", state.ble.measuredSampleRateHz?.let { "%.1f Hz (pomiar)".format(it) } ?: "Pomiar w toku")
                        InfoRow("Producent", state.ble.deviceInfo.manufacturer ?: "Nieudostępniony")
                        InfoRow("Model", state.ble.deviceInfo.model ?: "Nieudostępniony")
                        InfoRow("Firmware", state.ble.deviceInfo.firmwareRevision ?: "Nieudostępniony")
                    }
                }
            }
        }

        item { SectionTitle("Narzędzia") }
        item {
            NavigationRow(
                Icons.Default.Tune,
                "Kalibracja",
                if (state.ble.connectionState == TrikiConnectionState.READY) {
                    "Wyznacz odchylenie, pozycję neutralną i poziom szumu."
                } else {
                    "Najpierw połącz Triki, aby rozpocząć kalibrację."
                },
                onCalibration,
                enabled = state.ble.connectionState == TrikiConnectionState.READY,
            )
        }
        item { NavigationRow(Icons.Default.Sensors, "Monitor czujników", "Akcelerometr, żyroskop, orientacja i wykresy na żywo.", onSensor) }
        if (state.settings.developerMode) {
            item { NavigationRow(Icons.Default.BugReport, "Inspektor BLE", "Usługi GATT, właściwości oraz pakiety RAW w HEX i DEC.", onInspector) }
        }
        item { NavigationRow(Icons.Default.Security, "Uprawnienia", "Bluetooth, powiadomienie usługi i informacje o odtwarzaniu.", onPermissions) }
    }
}

@Composable
private fun DeviceResult(device: TrikiDevice, onConnect: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SignalCellularAlt, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("${device.address} · ${device.rssi ?: "—"} dBm", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onConnect) { Text(if (device.isKnown) "Połącz ponownie" else "Połącz i zapamiętaj") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
