package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.LiveLineChart

@Composable
fun GestureTrainerScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val trainer by viewModel.trainer.collectAsStateWithLifecycle()
    val detectedGesture = trainer.detectedGesture
    val history = state.runtime.history.takeLast(180)
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelTrainer() }
    }
    Scaffold(
        topBar = {
            DetailTopBar("Naucz gest") {
                viewModel.cancelTrainer()
                onBack()
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Wybierz gest", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GestureType.entries.forEach { gesture ->
                    FilterChip(
                        selected = trainer.selectedGesture == gesture,
                        onClick = { viewModel.selectTrainerGesture(gesture) },
                        label = { Text(gesture.displayName) },
                        enabled = !trainer.recording,
                    )
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Akcelerometr X / Y / Z", style = MaterialTheme.typography.titleMedium)
                    LiveLineChart(
                        series = listOf(
                            history.map { it.accelerometerG.x },
                            history.map { it.accelerometerG.y },
                            history.map { it.accelerometerG.z },
                        ),
                        colors = listOf(Color(0xFF34D399), Color(0xFF60A5FA), Color(0xFFF59E0B)),
                    )
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        when {
                            trainer.recording -> "Nagrywanie: ${trainer.selectedGesture.displayName}"
                            detectedGesture != null -> "Wykryto: ${detectedGesture.displayName}"
                            else -> "Gotowe — naciśnij Start"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (trainer.recording) {
                        Text(
                            "${trainer.sampleCount} próbek · ${"%.1f".format(trainer.durationMillis / 1_000f)} s",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    trainer.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!trainer.recording && trainer.sampleCount > 0) {
                        val range = trainer.accelerationRangeG
                        Text(
                            buildString {
                                append("Nagranie: ${trainer.sampleCount} próbek · ${"%.1f".format(trainer.durationMillis / 1_000f)} s")
                                trainer.confidence?.let { append(" · pewność ${"%.0f".format(it * 100f)}%") }
                                append("\nPeak gyro: ${"%.0f".format(trainer.peakGyroscopeDps)}°/s")
                                if (range != null) {
                                    append(" · accel ${"%.2f".format(range.start)}–${"%.2f".format(range.endInclusive)} g")
                                }
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (trainer.accepted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text(" Zaakceptowano", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (trainer.recording) {
                Button(
                    onClick = viewModel::stopTrainer,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(" Stop i analizuj")
                }
            } else if (trainer.detectedGesture == null) {
                Button(onClick = viewModel::startTrainer, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(" Start nagrania")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = viewModel::startTrainer, modifier = Modifier.weight(1f)) { Text("Nagraj ponownie") }
                    Button(onClick = viewModel::acceptTrainerResult, modifier = Modifier.weight(1f)) { Text("Akceptuj") }
                }
            }
        }
    }
}
