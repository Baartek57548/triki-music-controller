package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.LiveLineChart
import pl.trikimusic.controller.ui.components.TrikiCard
import pl.trikimusic.controller.ui.components.volumeControlPresentation

@Composable
fun SensorMonitorScreen(state: MainUiState, onBack: () -> Unit, viewModel: MainViewModel? = null) {
    val sample = state.runtime.latestSample
    val history = state.runtime.history.takeLast(240)
    val volumePresentation = state.volumeControlPresentation()

    Scaffold(topBar = { DetailTopBar("Monitor czujników", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (sample == null) {
                item { EmptyState("Brak danych IMU", "Połącz kontroler Triki lub włącz symulator w trybie debugowania.") }
            } else {
                item {
                    TrikiCard {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Akcelerometr (g)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            AxisRow("Oś X", sample.accelerometerG.x)
                            AxisRow("Oś Y", sample.accelerometerG.y)
                            AxisRow("Oś Z", sample.accelerometerG.z)
                            AxisRow("Długość wektora wypadkowego", sample.accelerationMagnitude)
                        }
                    }
                }
                item {
                    TrikiCard {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Żyroskop (°/s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            AxisRow("Oś X", sample.gyroscopeDps.x)
                            AxisRow("Oś Y", sample.gyroscopeDps.y)
                            AxisRow("Oś Z", sample.gyroscopeDps.z)
                            AxisRow("Długość wektora wypadkowego", sample.gyroscopeMagnitude)
                        }
                    }
                }
                item {
                    TrikiCard {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Orientacja i regulatory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            AxisRow("Pochylenie (Pitch)", sample.orientation.pitch, "°")
                            AxisRow("Przechylenie (Roll)", sample.orientation.roll, "°")
                            AxisRow("Odchylenie (Yaw)", sample.orientation.yaw, "°")
                            AxisRow("Status pakietu RAW", sample.source.status.toFloat())
                            AxisRow("Przechył od poziomu", state.runtime.volumeTiltDegrees, "°")
                            AxisRow("Żyroskop Z regulatora", state.runtime.volumeGyroscopeZDps, " °/s")
                            Text(
                                "Interpretacja statusu: ${state.runtime.buttonProtocolMode.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Bramka: ${volumePresentation.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(volumePresentation.instruction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AxisRow("Sygnał RSSI", state.ble.rssi?.toFloat(), " dBm")
                            AxisRow("Poziom baterii", state.ble.battery.percent?.toFloat(), "%")
                        }
                    }
                }
            }
            item {
                ChartCard(
                    "Przebieg akcelerometru (X / Y / Z)",
                    listOf(
                        history.map { it.accelerometerG.x },
                        history.map { it.accelerometerG.y },
                        history.map { it.accelerometerG.z },
                    ),
                )
            }
            item {
                ChartCard(
                    "Przebieg żyroskopu (X / Y / Z)",
                    listOf(
                        history.map { it.gyroscopeDps.x },
                        history.map { it.gyroscopeDps.y },
                        history.map { it.gyroscopeDps.z },
                    ),
                )
            }
            if (BuildConfig.DEBUG && state.settings.developerMode && viewModel != null) {
                item {
                    Text("Generator kliknięć testowych", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value?.let { "%+.3f%s".format(it, unit) } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChartCard(title: String, series: List<List<Float>>) {
    TrikiCard {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LiveLineChart(series, listOf(Color(0xFF34D399), Color(0xFF60A5FA), Color(0xFFF59E0B)))
        }
    }
}

