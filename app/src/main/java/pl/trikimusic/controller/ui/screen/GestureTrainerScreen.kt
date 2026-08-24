package pl.trikimusic.controller.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MIN_PERSONALIZED_SAMPLES_PER_GESTURE
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.LiveLineChart

@Composable
fun GestureTrainerScreen(state: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val trainer by viewModel.trainer.collectAsStateWithLifecycle()
    val detectedGesture = trainer.detectedGesture
    val history = state.runtime.history.takeLast(180)
    var captureText by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(captureText) }
                    ?: error("Nie można otworzyć pliku do zapisu.")
            }.onSuccess {
                Toast.makeText(context, "Zapisano nagranie gestu.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Błąd zapisu.", Toast.LENGTH_LONG).show()
            }
        }
    }
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
                    Text(
                        if (trainer.learnedSampleCount >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE) {
                            "Model lokalny: ${trainer.learnedSampleCount} próbek · aktywny"
                        } else {
                            "Model lokalny: ${trainer.learnedSampleCount}/$MIN_PERSONALIZED_SAMPLES_PER_GESTURE próbek"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
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
                        Text(
                            "Jakość cech accel + gyro: ${"%.0f".format(trainer.featureQuality * 100f)}%",
                            color = if (trainer.featureReady || trainer.accepted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (trainer.accepted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text(" Dodano do modelu", color = MaterialTheme.colorScheme.primary)
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
            } else {
                Button(onClick = viewModel::startTrainer, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(if (trainer.sampleCount > 0) " Nagraj kolejną próbkę" else " Start nagrania")
                }
                if (trainer.featureReady && !trainer.accepted) {
                    Button(onClick = viewModel::learnTrainerSample, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text(" Dodaj jako ${trainer.selectedGesture.displayName}")
                    }
                }
                if (trainer.sampleCount > 0) {
                    OutlinedButton(
                        onClick = {
                            runCatching { viewModel.trainerCaptureCsv() }
                                .onSuccess { csv ->
                                    captureText = csv
                                    val gesture = trainer.selectedGesture.name.lowercase()
                                    exportLauncher.launch("triki-gesture-$gesture-${System.currentTimeMillis()}.csv")
                                }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        it.message ?: "Nie można przygotować eksportu.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Text(" Zapisz nagranie CSV")
                    }
                }
                if (trainer.learnedSampleCount > 0) {
                    TextButton(onClick = viewModel::clearTrainerSamples, modifier = Modifier.fillMaxWidth()) {
                        Text("Usuń próbki modelu dla tego gestu")
                    }
                }
            }
        }
    }
}
