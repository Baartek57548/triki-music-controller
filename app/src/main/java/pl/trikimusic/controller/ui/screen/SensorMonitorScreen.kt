package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.LiveLineChart

@Composable
fun SensorMonitorScreen(state: MainUiState, onBack: () -> Unit, viewModel: MainViewModel? = null) {
    val sample = state.runtime.latestSample
    val history = state.runtime.history.takeLast(240)
    Scaffold(topBar = { DetailTopBar("Sensor Monitor", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (sample == null) {
                item { EmptyState("Brak danych IMU", "Połącz Triki albo użyj Fake Triki w buildzie debug.") }
            } else {
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Accelerometer · g", style = MaterialTheme.typography.titleMedium)
                            AxisRow("X", sample.accelerometerG.x)
                            AxisRow("Y", sample.accelerometerG.y)
                            AxisRow("Z", sample.accelerometerG.z)
                            AxisRow("Magnitude", sample.accelerationMagnitude)
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Gyroscope · °/s", style = MaterialTheme.typography.titleMedium)
                            AxisRow("X", sample.gyroscopeDps.x)
                            AxisRow("Y", sample.gyroscopeDps.y)
                            AxisRow("Z", sample.gyroscopeDps.z)
                            AxisRow("Magnitude", sample.gyroscopeMagnitude)
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Orientation", style = MaterialTheme.typography.titleMedium)
                            AxisRow("Pitch", sample.orientation.pitch, "°")
                            AxisRow("Roll", sample.orientation.roll, "°")
                            AxisRow("Yaw", sample.orientation.yaw, "°")
                            AxisRow("Status RAW", sample.source.status.toFloat())
                            Text(
                                "Interpretacja statusu: ${state.runtime.buttonProtocolMode.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            AxisRow("RSSI", state.ble.rssi?.toFloat(), " dBm")
                            AxisRow("Battery", state.ble.battery.percent?.toFloat(), "%")
                        }
                    }
                }
            }
            item {
                ChartCard(
                    "Accelerometer X / Y / Z",
                    listOf(
                        history.map { it.accelerometerG.x },
                        history.map { it.accelerometerG.y },
                        history.map { it.accelerometerG.z },
                    ),
                )
            }
            item {
                ChartCard(
                    "Gyroscope X / Y / Z",
                    listOf(
                        history.map { it.gyroscopeDps.x },
                        history.map { it.gyroscopeDps.y },
                        history.map { it.gyroscopeDps.z },
                    ),
                )
            }
            if (BuildConfig.DEBUG && state.settings.developerMode && viewModel != null) {
                item {
                    Text("Fake Triki", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GestureType.entries.forEach { gesture ->
                            AssistChip(
                                onClick = { viewModel.emitFakeGesture(gesture) },
                                label = { Text(gesture.displayName) },
                                leadingIcon = { Icon(Icons.Default.Sensors, null) },
                            )
                        }
                        (1..3).forEach { clickCount ->
                            AssistChip(
                                onClick = { viewModel.emitFakeButtonClicks(clickCount) },
                                label = { Text("Przycisk ×$clickCount") },
                                leadingIcon = { Icon(Icons.Default.Sensors, null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisRow(label: String, value: Float?, unit: String = "") {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let { "%+.3f%s".format(it, unit) } ?: "—", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ChartCard(title: String, series: List<List<Float>>) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            LiveLineChart(series, listOf(Color(0xFF34D399), Color(0xFF60A5FA), Color(0xFFF59E0B)))
        }
    }
}
