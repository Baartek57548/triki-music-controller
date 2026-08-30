package pl.trikimusic.controller.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.GattServiceInfo
import pl.trikimusic.controller.domain.model.RawBlePacket
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.TrikiCard

@Composable
fun BleInspectorScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var captureText by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(captureText) }
                    ?: error("Nie można otworzyć pliku do zapisu.")
            }.onSuccess {
                Toast.makeText(context, "Zapisano log BLE do pliku.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Błąd zapisu pliku.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(topBar = { DetailTopBar("Inspektor Bluetooth LE", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            if (!recording) viewModel.startRawRecording() else viewModel.stopRawRecording()
                            recording = !recording
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, modifier = Modifier.size(18.dp))
                        Text(if (recording) " Zatrzymaj" else " Rejestruj")
                    }
                    OutlinedButton(
                        onClick = {
                            captureText = viewModel.rawCaptureText()
                            exportLauncher.launch("triki-ble-${System.currentTimeMillis()}.txt")
                        },
                        enabled = state.rawPackets.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                        Text(" Zapisz do pliku")
                    }
                }
            }

            item {
                TrikiCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Diagnostyka dekodera IMU", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Text(
                            "Częstotliwość: ${state.ble.measuredSampleRateHz?.let { "%.1f Hz".format(it) } ?: "— Hz"} • " +
                                "Ramki: ${state.ble.decodedFrames} • Odrzucone na starcie: ${state.ble.discardedStartupFrames}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "ID ostatniego pakietu: ${state.ble.lastPacketId ?: "—"} • Pominięte bajty: ${state.ble.droppedProtocolBytes}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.ble.droppedProtocolBytes == 0L) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Usługi GATT",
                    subtitle = "Wykryto ${state.ble.gattServices.size} usług Bluetooth GATT.",
                )
            }

            if (state.ble.gattServices.isEmpty()) {
                item { EmptyState("Brak danych GATT", "Połącz kontroler Triki, aby wykonać procedurę odkrywania usług.") }
            } else {
                items(state.ble.gattServices, key = GattServiceInfo::uuid) { service -> GattServiceCard(service) }
            }

            item {
                SectionTitle(
                    title = "Surowe pakiety (RAW)",
                    subtitle = "${state.rawPackets.size} pakietów w buforze rotacyjnym.",
                )
            }

            if (state.rawPackets.isEmpty()) {
                item { EmptyState("Brak pakietów", "Po uzyskaniu stanu gotowości powiadomienia NUS pojawią się tutaj w formatach HEX i DEC.") }
            } else {
                itemsIndexed(
                    state.rawPackets.takeLast(100).reversed(),
                    key = { index, packet -> "$index-${packet.timestampMillis}-${packet.hashCode()}" },
                ) { _, packet ->
                    RawPacketCard(packet)
                }
            }
        }
    }
}

@Composable
private fun GattServiceCard(service: GattServiceInfo) {
    var expanded by remember { mutableStateOf(false) }
    TrikiCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(service.uuid, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                service.characteristics.forEach { characteristic ->
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(characteristic.uuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(characteristic.properties.joinToString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        characteristic.valueHex?.let { Text("WARTOŚĆ HEX: $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        if (characteristic.descriptors.isNotEmpty()) {
                            Text("Deskryptory: ${characteristic.descriptors.joinToString { it.uuid }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawPacketCard(packet: RawBlePacket) {
    TrikiCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(packet.characteristicUuid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("HEX: ${packet.hex}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text("DEC: ${packet.decimal}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

