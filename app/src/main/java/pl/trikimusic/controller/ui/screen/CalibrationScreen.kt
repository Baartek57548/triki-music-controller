package pl.trikimusic.controller.ui.screen

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar

@Composable
fun CalibrationScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose(viewModel::resetCalibrationState) }
    Scaffold(topBar = { DetailTopBar("Kalibracja", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Text("Połóż Triki nieruchomo", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Umieść kapsel górną stroną do góry na stabilnej, płaskiej powierzchni. Przez trzy sekundy nie dotykaj urządzenia.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    if (calibration.running) {
                        Text("Zbieranie próbek · ${calibration.sampleCount}", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { calibration.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Nie poruszaj Triki.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (calibration.result != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Kalibracja zapisana", style = MaterialTheme.typography.titleMedium)
                        }
                        val result = requireNotNull(calibration.result)
                        Text("Próbki: ${result.sampleCount}")
                        Text("Szum akcelerometru: %.4f g".format(result.accelerometerNoise))
                        Text("Szum żyroskopu: %.2f°/s".format(result.gyroscopeNoise))
                        Text("Neutralnie: pitch %.1f°, roll %.1f°".format(result.neutralPitch, result.neutralRoll))
                    } else {
                        Text(
                            if (state.settings.calibration.isValid) "Urządzenie ma zapisany profil kalibracyjny." else "Brak zapisanej kalibracji.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.settings.calibration.takeIf { it.isValid }?.let {
                            Text("Poprzednio: ${it.sampleCount} próbek · szum gyro %.2f°/s".format(it.gyroscopeNoise))
                        }
                    }
                    calibration.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Button(onClick = viewModel::startCalibration, enabled = !calibration.running, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.settings.calibration.isValid) "Kalibruj ponownie" else "Rozpocznij kalibrację")
            }
        }
    }
}
