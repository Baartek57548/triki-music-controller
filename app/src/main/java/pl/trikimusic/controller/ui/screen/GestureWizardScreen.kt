package pl.trikimusic.controller.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MIN_PERSONALIZED_SAMPLES_PER_GESTURE
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.GestureWizardUiState
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.TrainerUiState
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.LiveLineChart

@Composable
fun GestureWizardScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val wizard by viewModel.gestureWizard.collectAsStateWithLifecycle()
    val trainer by viewModel.trainer.collectAsStateWithLifecycle()
    val leaveWizard = {
        viewModel.endGestureWizard()
        onBack()
    }

    LaunchedEffect(viewModel) { viewModel.beginGestureWizard() }
    LaunchedEffect(wizard.completionSaved) {
        if (wizard.completionSaved) {
            onFinished()
            viewModel.endGestureWizard()
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelTrainer() }
    }
    BackHandler(onBack = leaveWizard)

    Scaffold(
        topBar = { DetailTopBar("Kreator gestów", leaveWizard) },
        bottomBar = {
            WizardBottomBar(
                wizard = wizard,
                trainer = trainer,
                learnedSampleCount = state.settings.personalizedGestureModel.sampleCountFor(wizard.currentGesture),
                onPrevious = viewModel::previousGestureWizardStep,
                onSaveStep = viewModel::saveGestureWizardStep,
                onFinish = viewModel::finishGestureWizard,
            )
        },
    ) { padding ->
        if (wizard.summaryVisible) {
            WizardSummary(state, wizard, padding)
        } else {
            GestureStep(state, wizard, trainer, viewModel, padding)
        }
    }
}

@Composable
private fun GestureStep(
    state: MainUiState,
    wizard: GestureWizardUiState,
    trainer: TrainerUiState,
    viewModel: MainViewModel,
    padding: PaddingValues,
) {
    val gesture = wizard.currentGesture
    val guide = gestureGuide(gesture)
    val resultBelongsToStep = trainer.selectedGesture == gesture
    val modelReady = trainer.learnedSampleCount >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE
    val history = state.runtime.history.takeLast(160)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Gest ${wizard.currentIndex + 1} z ${GestureType.entries.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${((wizard.currentIndex + 1) * 100) / GestureType.entries.size}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (wizard.currentIndex + 1f) / GestureType.entries.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!state.settings.calibration.isValid || state.ble.connectionState != TrikiConnectionState.READY) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        if (!state.settings.calibration.isValid) {
                            "Próba ruchu wymaga najpierw poprawnej kalibracji. Możesz ustawić akcje, a nagranie wykonać później."
                        } else {
                            "Triki nie przesyła teraz danych IMU. Połącz urządzenie ponownie, aby wykonać próbę; mapowania możesz ustawić bez połączenia."
                        },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(gesture.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(guide.instruction, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        guide.tip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (gesture == GestureType.THROW_UP) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                "Podrzucaj nisko, nad miękką powierzchnią i pewnie złap urządzenie.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
            ) {
                Text(
                    "Uczenie lokalne: zapisz 2 krótkie próby — każdą z innej typowej pozycji kapsla. Model porówna jednocześnie akcelerometr i żyroskop. Możesz też pominąć gest.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Akcja dla gestu", style = MaterialTheme.typography.titleMedium)
                ActionDropdown(
                    selected = wizard.selectedAction,
                    enabled = !trainer.recording && !wizard.saving,
                    onSelect = viewModel::selectGestureWizardAction,
                )
                Text(
                    if (wizard.selectedAction == MediaAction.NONE) {
                        "Ten gest będzie wyłączony i nie uruchomi żadnej komendy."
                    } else {
                        "Po rozpoznaniu aplikacja wykona: ${wizard.selectedAction.displayName}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Próba rozpoznawania", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Naciśnij Start, odczekaj chwilę w bezruchu, wykonaj dokładnie jeden gest i zatrzymaj nagranie po uspokojeniu Triki.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LiveLineChart(
                        series = listOf(
                            history.map { it.accelerometerG.x },
                            history.map { it.accelerometerG.y },
                            history.map { it.accelerometerG.z },
                        ),
                        colors = listOf(Color(0xFF34D399), Color(0xFF60A5FA), Color(0xFFF59E0B)),
                    )
                    RecordingResult(gesture, trainer, resultBelongsToStep)
                    Text(
                        if (modelReady) {
                            "Model gestu: ${trainer.learnedSampleCount} próbek · gotowy"
                        } else {
                            "Model gestu: ${trainer.learnedSampleCount}/$MIN_PERSONALIZED_SAMPLES_PER_GESTURE próbek"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (modelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (trainer.recording && resultBelongsToStep) {
                        Button(
                            onClick = viewModel::stopTrainer,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text(" Stop i analizuj")
                        }
                    } else {
                        Button(
                            onClick = viewModel::startTrainer,
                            enabled = !wizard.saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(if (trainer.sampleCount > 0 && resultBelongsToStep) " Nagraj ponownie" else " Start nagrania")
                        }
                    }
                    if (trainer.featureReady && !trainer.accepted) {
                        Button(
                            onClick = viewModel::learnTrainerSample,
                            enabled = !wizard.saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Text(" Dodaj próbkę jako ${gesture.displayName}")
                        }
                    }
                    if (trainer.accepted || modelReady) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (modelReady) "Model gestu jest gotowy" else "Próbka została dodana",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingResult(
    expectedGesture: GestureType,
    trainer: TrainerUiState,
    resultBelongsToStep: Boolean,
) {
    if (!resultBelongsToStep) return
    val detected = trainer.detectedGesture
    Text(
        when {
            trainer.recording -> "Nagrywanie · ${trainer.sampleCount} próbek · ${"%.1f".format(trainer.durationMillis / 1_000f)} s"
            detected == expectedGesture -> "Wykryto: ${detected.displayName}"
            detected != null -> "Wykryto inny ruch: ${detected.displayName}"
            trainer.sampleCount > 0 -> "Nie udało się rozpoznać pełnego gestu."
            else -> "Gotowe do nagrania."
        },
        style = MaterialTheme.typography.titleSmall,
        color = when {
            detected == expectedGesture -> MaterialTheme.colorScheme.primary
            detected != null || trainer.sampleCount > 0 -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
    trainer.message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!trainer.recording && trainer.sampleCount > 0) {
        Text(
            buildString {
                append("${trainer.sampleCount} próbek · ${"%.1f".format(trainer.durationMillis / 1_000f)} s")
                trainer.confidence?.let { append(" · pewność ${"%.0f".format(it * 100f)}%") }
                append(" · peak gyro ${"%.0f".format(trainer.peakGyroscopeDps)}°/s")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Jakość accel + gyro: ${"%.0f".format(trainer.featureQuality * 100f)}%",
            style = MaterialTheme.typography.bodySmall,
            color = if (trainer.featureReady || trainer.accepted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun ActionDropdown(
    selected: MediaAction,
    enabled: Boolean,
    onSelect: (MediaAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected.displayName, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MediaAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.displayName,
                            fontWeight = if (action == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun WizardBottomBar(
    wizard: GestureWizardUiState,
    trainer: TrainerUiState,
    learnedSampleCount: Int,
    onPrevious: () -> Unit,
    onSaveStep: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    val gesture = wizard.currentGesture
    val learnedInThisSession = trainer.selectedGesture == gesture && trainer.accepted
    val verifiedInThisSession = learnedInThisSession ||
        learnedSampleCount > 0 ||
        gesture in wizard.verifiedGestures
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = (wizard.currentIndex > 0 || wizard.summaryVisible) && !wizard.saving && !wizard.finishing && !trainer.recording,
            ) {
                Text("Wstecz")
            }
            if (wizard.summaryVisible) {
                Button(
                    onClick = onFinish,
                    enabled = !wizard.finishing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (wizard.finishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.DoneAll, contentDescription = null)
                    }
                    Text(" Zakończ konfigurację")
                }
            } else {
                Button(
                    onClick = { onSaveStep(verifiedInThisSession) },
                    enabled = !trainer.recording && !wizard.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (wizard.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        when {
                            verifiedInThisSession -> if (wizard.isLastGesture) "Zapisz i podsumuj" else "Zapisz i dalej"
                            wizard.selectedAction == MediaAction.NONE -> "Wyłącz i dalej"
                            else -> "Pomiń próbę i dalej"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardSummary(state: MainUiState, wizard: GestureWizardUiState, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.DoneAll, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Gesty skonfigurowane", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Próbki uczące dodano dla ${wizard.verifiedGestures.size} z ${GestureType.entries.size} gestów. Pominięte gesty nadal mogą działać przez bezpieczne reguły bazowe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column {
                    GestureType.entries.forEachIndexed { index, gesture ->
                        val action = wizard.configuredActions[gesture] ?: MediaAction.NONE
                        val learnedSamples = state.settings.personalizedGestureModel.sampleCountFor(gesture)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(gesture.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    action.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                when {
                                    learnedSamples >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE -> "Model: $learnedSamples"
                                    learnedSamples == 1 || gesture in wizard.verifiedGestures -> "Model: 1 próbka"
                                    else -> "Reguły bazowe"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (learnedSamples > 0 || gesture in wizard.verifiedGestures) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (index != GestureType.entries.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                        }
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f)),
            ) {
                Text(
                    "Kreator można uruchomić ponownie z zakładki Gestures. Pojedynczy gest przetestujesz też w ekranie „Naucz gest”.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private data class GestureGuide(
    val instruction: String,
    val tip: String,
)

private fun gestureGuide(gesture: GestureType): GestureGuide = when (gesture) {
    GestureType.TILT_LEFT -> GestureGuide(
        instruction = "Zacznij w dowolnej stabilnej pozycji. Przechyl Triki w lewo względem oznaczenia na kapslu, wróć do pozycji startowej i odczekaj chwilę.",
        tip = "Drugą próbę nagraj z innego typowego ułożenia, ale zawsze zachowaj kierunek względem obudowy.",
    )
    GestureType.TILT_RIGHT -> GestureGuide(
        instruction = "Zacznij w dowolnej stabilnej pozycji. Przechyl Triki w prawo względem oznaczenia na kapslu, wróć do pozycji startowej i odczekaj chwilę.",
        tip = "Drugą próbę nagraj z innego typowego ułożenia, ale zawsze zachowaj kierunek względem obudowy.",
    )
    GestureType.SHAKE -> GestureGuide(
        instruction = "Po chwili bezruchu wykonaj jeden krótki, zdecydowany ruch tam i z powrotem, a następnie zatrzymaj Triki.",
        tip = "Jeden pełny impuls powinien być wyraźny, ale nie gwałtowny.",
    )
    GestureType.DOUBLE_SHAKE -> GestureGuide(
        instruction = "Wykonaj dwa krótkie potrząśnięcia jedno po drugim, po czym odłóż Triki i poczekaj na bezruch.",
        tip = "Zachowaj krótki odstęp między impulsami; nie wykonuj długiej serii ruchów.",
    )
    GestureType.FLIP -> GestureGuide(
        instruction = "Zacznij nieruchomo, odwróć Triki na przeciwną stronę o około 180° i pozostaw je stabilnie w nowej pozycji.",
        tip = "Pierwszą próbę możesz wykonać logo do góry, a drugą z pozycji bocznej.",
    )
    GestureType.ROTATE_LEFT -> GestureGuide(
        instruction = "Obróć Triki w płaszczyźnie stołu co najmniej o 70° w lewo i zatrzymaj je w nowym położeniu.",
        tip = "Obracaj wokół osi pionowej; nie przechylaj urządzenia na bok.",
    )
    GestureType.ROTATE_RIGHT -> GestureGuide(
        instruction = "Obróć Triki w płaszczyźnie stołu co najmniej o 70° w prawo i zatrzymaj je w nowym położeniu.",
        tip = "Obracaj wokół osi pionowej; nie przechylaj urządzenia na bok.",
    )
    GestureType.THROW_UP -> GestureGuide(
        instruction = "Podrzuć Triki kilka centymetrów pionowo, pewnie je złap, odłóż i zaczekaj aż pozostanie nieruchome.",
        tip = "Klasyfikator szuka krótkiej nieważkości, a potem wyraźnego momentu złapania.",
    )
}
