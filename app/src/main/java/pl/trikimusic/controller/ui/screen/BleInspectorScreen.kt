package pl.trikimusic.controller.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.GattServiceInfo
import pl.trikimusic.controller.domain.model.RawBlePacket
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.SectionTitle

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
                Toast.makeText(context, "Zapisano log BLE.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Błąd zapisu.", Toast.LENGTH_LONG).show()
            }
        }
    }
    Scaffold(topBar = { DetailTopBar("Inspektor BLE", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!recording) viewModel.startRawRecording() else viewModel.stopRawRecording()
                            recording = !recording
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null)
                        Text(if (recording) " Zatrzymaj" else " Start recording")
                    }
                    OutlinedButton(
                        onClick = {
                            captureText = viewModel.rawCaptureText()
                            exportLauncher.launch("triki-ble-${System.currentTimeMillis()}.txt")
                        },
                        enabled = state.rawPackets.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Save, null)
                        Text(" Zapisz")
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Dekoder IMU", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.ble.measuredSampleRateHz?.let { "%.1f Hz".format(it) } ?: "— Hz"} · " +
                                "ramki ${state.ble.decodedFrames} · start odrzucone ${state.ble.discardedStartupFrames}",
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "ID pakietu ${state.ble.lastPacketId ?: "—"} · pominięte bajty ${state.ble.droppedProtocolBytes}",
                            fontFamily = FontFamily.Monospace,
                            color = if (state.ble.droppedProtocolBytes == 0L) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
            item { SectionTitle("GATT", subtitle = "${state.ble.gattServices.size} usług") }
            if (state.ble.gattServices.isEmpty()) {
                item { EmptyState("Brak danych GATT", "Połącz Triki, aby wykonać discovery services.") }
            } else {
                items(state.ble.gattServices, key = GattServiceInfo::uuid) { service -> GattServiceCard(service) }
            }
            item { SectionTitle("RAW notifications", subtitle = "${state.rawPackets.size} pakietów w rotującym buforze") }
            if (state.rawPackets.isEmpty()) {
                item { EmptyState("Brak pakietów", "Po READY notyfikacje NUS TX pojawią się tutaj w HEX i DEC.") }
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
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(service.uuid, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                service.characteristics.forEach { characteristic ->
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(characteristic.uuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(characteristic.properties.joinToString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        characteristic.valueHex?.let { Text("VALUE $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                        if (characteristic.descriptors.isNotEmpty()) {
                            Text("Descriptors: ${characteristic.descriptors.joinToString { it.uuid }}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RawPacketCard(packet: RawBlePacket) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(packet.characteristicUuid, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text("HEX  ${packet.hex}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Text("DEC  ${packet.decimal}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
