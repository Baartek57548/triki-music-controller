package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiDevice
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.LoadingInline
import pl.trikimusic.controller.ui.components.MetricTile
import pl.trikimusic.controller.ui.components.NavigationRow
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.StatusPill
import pl.trikimusic.controller.ui.components.TrikiCard
import pl.trikimusic.controller.ui.components.signalQualityLabel

@Composable
fun DeviceScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    viewModel: MainViewModel,
    onCalibration: () -> Unit,
    onPermissions: () -> Unit,
) {
    var ledOn by remember { mutableStateOf(false) }
    val working = state.ble.connectionState in setOf(
        TrikiConnectionState.SCANNING,
        TrikiConnectionState.CONNECTING,
        TrikiConnectionState.RECONNECTING,
        TrikiConnectionState.WAITING_FOR_WAKE,
        TrikiConnectionState.CONNECTED,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionTitle(
                title = "Połączenie Bluetooth",
                subtitle = "Stan kontrolera, zarządzanie urządzeniem i wyszukiwanie.",
                icon = Icons.Default.Bluetooth,
            )
        }

        item {
            TrikiCard(
                containerColor = if (state.ble.connectionState == TrikiConnectionState.READY) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DeviceHeader(state)

                    LoadingInline(
                        when (state.ble.connectionState) {
                            TrikiConnectionState.SCANNING -> "Szukam aktywnego Triki…"
                            TrikiConnectionState.CONNECTING -> "Nawiązuję połączenie GATT…"
                            TrikiConnectionState.CONNECTED -> "Odczytuję usługi i parametry…"
                            TrikiConnectionState.RECONNECTING -> "Naciśnij przycisk Triki — telefon czeka na wybudzenie…"
                            TrikiConnectionState.WAITING_FOR_WAKE -> if (state.ble.wakeWatcherArmed) {
                                "Nasłuch w toku — naciśnij przycisk na kapslu…"
                            } else {
                                "Czekam, aż poprzednia sesja całkowicie zaśnie…"
                            }
                            else -> ""
                        },
                        working,
                    )

                    state.ble.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    if (state.ble.connectionState == TrikiConnectionState.READY) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MetricTile(
                                icon = Icons.Default.BatteryFull,
                                label = "Bateria",
                                value = state.ble.battery.percent?.let { "$it%" } ?: "Nieudostępniona",
                            )
                            MetricTile(
                                icon = Icons.Default.SignalCellularAlt,
                                label = "Jakość sygnału",
                                value = signalQualityLabel(state.ble.rssi),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (state.ble.connectionState) {
                            TrikiConnectionState.READY -> {
                                Button(
                                    onClick = viewModel::disconnect,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text("Rozłącz")
                                }
                                OutlinedButton(
                                    onClick = { ledOn = !ledOn; viewModel.setLed(ledOn) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(Icons.Default.Lightbulb, null, modifier = Modifier.size(18.dp))
                                    Text(if (ledOn) " Zgaś LED" else " Zapal LED")
                                }
                            }

                            TrikiConnectionState.RECONNECTING,
                            TrikiConnectionState.WAITING_FOR_WAKE,
                            TrikiConnectionState.CONNECTING,
                            TrikiConnectionState.CONNECTED,
                            -> Button(
                                onClick = if (state.ble.connectionState in setOf(
                                    TrikiConnectionState.RECONNECTING,
                                    TrikiConnectionState.WAITING_FOR_WAKE,
                                )) {
                                    viewModel::disableAutoConnect
                                } else {
                                    viewModel::disconnect
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    if (state.ble.connectionState in setOf(
                                            TrikiConnectionState.RECONNECTING,
                                            TrikiConnectionState.WAITING_FOR_WAKE,
                                        )
                                    ) {
                                        "Wyłącz autołączenie"
                                    } else {
                                        "Anuluj"
                                    },
                                )
                            }

                            else -> {
                                Button(
                                    onClick = { if (state.permissions.bluetoothPermissionsGranted) viewModel.startScan() else onPermissions() },
                                    enabled = !working,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, null, modifier = Modifier.size(20.dp))
                                    Text(" Znajdź Triki", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (state.settings.knownDeviceAddress != null && state.ble.connectionState != TrikiConnectionState.READY) {
                        OutlinedButton(
                            onClick = viewModel::forgetDevice,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Zapomnij zapamiętane urządzenie")
                        }
                    }
                }
            }
        }

        if (state.ble.discoveredDevices.isNotEmpty() && state.ble.connectionState != TrikiConnectionState.READY) {
            item {
                SectionTitle(
                    title = "Wykryte kontrolery",
                    subtitle = "Wybierz urządzenie, aby nawiązać połączenie.",
                )
            }
            items(state.ble.discoveredDevices, key = TrikiDevice::address) { device ->
                DeviceResultCard(device = device, onConnect = { viewModel.connect(device) })
            }
        } else if (state.ble.connectionState == TrikiConnectionState.FOUND && state.ble.discoveredDevices.isEmpty()) {
            item {
                EmptyState("Brak wyników wyszukiwania", "Naciśnij fizyczny przycisk na kapslu Triki, aby go wybudzić, a następnie ponów wyszukiwanie.")
            }
        }

        if (state.ble.connectionState == TrikiConnectionState.READY) {
            item {
                SectionTitle(
                    title = "Informacje o sprzęcie",
                    subtitle = "Dane techniczne odczytane z kontrolera.",
                    icon = Icons.Default.Info,
                )
            }
            item {
                TrikiCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoRow("Producent", state.ble.deviceInfo.manufacturer ?: "Triki Systems")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        InfoRow("Model", state.ble.deviceInfo.model ?: "Triki Music Controller")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        InfoRow("Wersja oprogramowania (Firmware)", state.ble.deviceInfo.firmwareRevision ?: "Domyślna (NUS)")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        InfoRow("Adres Bluetooth", state.ble.selectedDevice?.address ?: "—")
                    }
                }
            }
        }

        item {
            SectionTitle(
                title = "Konfiguracja i uprawnienia",
                subtitle = "Kalibracja położenia neutralnego oraz dostęp do funkcji systemowych.",
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Tune,
                title = "Kalibracja czujników",
                subtitle = if (state.ble.connectionState == TrikiConnectionState.READY) {
                    "Wyznacz pozycję neutralną, odchylenie i poziom szumu żyroskopu."
                } else {
                    "Połącz Triki, aby rozpocząć procedurę kalibracji."
                },
                onClick = onCalibration,
                enabled = state.ble.connectionState == TrikiConnectionState.READY,
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Security,
                title = "Uprawnienia systemowe",
                subtitle = "Dostęp do Bluetooth w pobliżu, powiadomień usługi i sesji muzycznych.",
                onClick = onPermissions,
            )
        }
    }
}

@Composable
private fun DeviceResultCard(device: TrikiDevice, onConnect: () -> Unit) {
    TrikiCard {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
        ) {
            if (maxWidth < 440.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeviceIdentity(device)
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (device.isKnown) "Połącz ponownie" else "Połącz i zapamiętaj", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceIdentity(device, Modifier.weight(1f))
                    Button(
                        onClick = onConnect,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (device.isKnown) "Połącz ponownie" else "Połącz i zapamiętaj", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceHeader(state: MainUiState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val identity: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        state.ble.selectedDevice?.name ?: state.settings.knownDeviceName ?: "Triki",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        state.ble.selectedDevice?.address
                            ?: state.settings.knownDeviceAddress
                            ?: "Brak zapamiętanego urządzenia",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                identity(Modifier.fillMaxWidth())
                StatusPill(state.ble.connectionState)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                identity(Modifier.weight(1f))
                StatusPill(state.ble.connectionState)
            }
        }
    }
}

@Composable
private fun DeviceIdentity(device: TrikiDevice, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Icon(
                Icons.Default.SignalCellularAlt,
                contentDescription = null,
                modifier = Modifier.padding(8.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${signalQualityLabel(device.rssi)} · ${device.address}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

