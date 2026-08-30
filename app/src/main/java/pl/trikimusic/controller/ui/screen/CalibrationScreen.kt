package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.TrikiCard

@Composable
fun CalibrationScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose(viewModel::resetCalibrationState) }

    Scaffold(topBar = { DetailTopBar("Kalibracja", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp).size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Text("Połóż Triki nieruchomo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Umieść kapsel górną stroną do góry na stabilnej, płaskiej powierzchni. Podczas kalibracji (ok. 3 sekundy) nie dotykaj ani nie poruszaj urządzenia.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TrikiCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (calibration.running) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Zbieranie próbek czujników", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${calibration.sampleCount}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { calibration.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Text("Nie poruszaj urządzeniem…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (calibration.result != null) {
                        val result = requireNotNull(calibration.result)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Kalibracja pomyślnie zapisana", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        CalibrationDetailRow("Liczba próbek", "${result.sampleCount}")
                        CalibrationDetailRow("Szum akcelerometru", "%.4f g".format(result.accelerometerNoise))
                        CalibrationDetailRow("Szum żyroskopu", "%.2f °/s".format(result.gyroscopeNoise))
                        CalibrationDetailRow("Kąt neutralny", "Pitch %.1f° • Roll %.1f°".format(result.neutralPitch, result.neutralRoll))
                    } else {
                        Text(
                            if (state.settings.calibration.isValid) "Zapisany aktywny profil kalibracyjny" else "Brak zapisanej kalibracji",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        state.settings.calibration.takeIf { it.isValid }?.let {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            CalibrationDetailRow("Poprzednia próba", "${it.sampleCount} próbek")
                            CalibrationDetailRow("Szum żyroskopu", "%.2f °/s".format(it.gyroscopeNoise))
                        }
                    }

                    calibration.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Button(
                onClick = viewModel::startCalibration,
                enabled = !calibration.running && state.ble.connectionState == pl.trikimusic.controller.domain.model.TrikiConnectionState.READY,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (state.settings.calibration.isValid) "Kalibruj ponownie" else "Rozpocznij kalibrację",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CalibrationDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
