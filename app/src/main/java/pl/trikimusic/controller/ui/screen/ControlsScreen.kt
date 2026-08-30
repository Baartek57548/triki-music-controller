package pl.trikimusic.controller.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.core.gesture.HoldGesturePhase
import pl.trikimusic.controller.core.gesture.RotationGestureDirection
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.VolumeGateState
import pl.trikimusic.controller.ui.components.volumeControlPresentation

@Composable
fun ControlsScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    viewModel: MainViewModel,
) {
    var selectedClick by remember { mutableStateOf<ButtonClickType?>(null) }
    var showVolumeDetails by remember { mutableStateOf(false) }
    val sample = state.runtime.latestSample
    val volumePresentation = state.volumeControlPresentation()
    val rotationProgress = state.runtime.rotationGestureProgress.coerceIn(0f, 1f)

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 22.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (volumePresentation.ready) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
                    },
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Głośność", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(volumePresentation.title, style = MaterialTheme.typography.titleLarge)
                        }
                        Icon(
                            if (volumePresentation.ready) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (volumePresentation.ready) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (volumePresentation.state == VolumeGateState.STABILIZING) {
                        LinearProgressIndicator(
                            progress = { state.runtime.volumeStabilizationProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        volumePresentation.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showVolumeDetails = !showVolumeDetails }) {
                        Icon(
                            if (showVolumeDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                        Text(if (showVolumeDetails) "Ukryj szczegóły" else "Pokaż szczegóły")
                    }
                    AnimatedVisibility(showVolumeDetails) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider()
                            GateStatusRow(
                                "Zakres 0–25°",
                                state.runtime.volumeWithinTiltRange,
                                sample?.let { "Aktualny przechył %.1f°".format(state.runtime.volumeTiltDegrees) } ?: "Brak danych",
                            )
                            HorizontalDivider()
                            GateStatusRow(
                                "Spokojny ruch",
                                state.runtime.volumeAccelerationStable,
                                sample?.let { "Przyspieszenie %.2f g · zakres 0,80–1,20 g".format(it.accelerationMagnitude) } ?: "Brak danych",
                            )
                            HorizontalDivider()
                            SensorValueRow(
                                "Żyroskop Z",
                                sample?.let { "%+.1f °/s".format(state.runtime.volumeGyroscopeZDps) } ?: "—",
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val angle = state.settings.rotationAngleDegrees
                    Text("Nawigacja obrotem $angle°", style = MaterialTheme.typography.titleLarge)
                    GestureActionRow(Icons.Default.SkipNext, "Ruch dłoni w lewo", "Następny utwór")
                    GestureActionRow(Icons.Default.SkipPrevious, "Ruch dłoni w prawo", "Poprzedni utwór")
                    Text(
                        "Kapsel jest odwrócony, dlatego liczy się kierunek ruchu Twojej dłoni. Ustabilizuj go przez 0,5 s, a następnie obróć o $angle° wokół osi Z — bez wciskania przycisku.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Dwa kliknięcia → Like · trzy kliknięcia → Dislike · osobny krótki sygnał potwierdza każdą ocenę",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    if (
                        (state.runtime.rotationGesturePhase == HoldGesturePhase.HOLDING && state.runtime.rotationGestureFaceDown) ||
                        state.runtime.rotationGesturePhase in setOf(HoldGesturePhase.TRACKING, HoldGesturePhase.COMPLETING)
                    ) {
                        LinearProgressIndicator(
                            progress = { rotationProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        when (state.runtime.rotationGesturePhase) {
                            HoldGesturePhase.IDLE -> "Gotowe — odwróć kapsel i odczekaj 0,5 s."
                            HoldGesturePhase.HOLDING -> if (state.runtime.rotationGestureFaceDown) {
                                "Odwrócenie potwierdzone · stabilizacja %.0f%%".format(
                                    rotationProgress * 100f,
                                )
                            } else {
                                "Odwróć kapsel górą w dół i uspokój go przed ruchem."
                            }
                            HoldGesturePhase.READY -> "Gotowe — obróć o $angle°: lewo = następny, prawo = poprzedni."
                            HoldGesturePhase.TRACKING -> when (state.runtime.rotationGestureDirection) {
                                RotationGestureDirection.LEFT -> "Następny utwór · ruch w lewo: %.0f° / $angle°".format(
                                    rotationProgress * angle,
                                )
                                RotationGestureDirection.RIGHT -> "Poprzedni utwór · ruch w prawo: %.0f° / $angle°".format(
                                    rotationProgress * angle,
                                )
                                null -> "Potwierdzam kierunek obrotu…"
                            }
                            HoldGesturePhase.COMPLETING -> when (state.runtime.rotationGestureDirection) {
                                RotationGestureDirection.LEFT -> "Następny utwór — dokończ ruch w lewo do $angle°."
                                RotationGestureDirection.RIGHT -> "Poprzedni utwór — dokończ ruch w prawo do $angle°."
                                null -> "Dokończ obrót do $angle°."
                            }
                            HoldGesturePhase.REARMING -> "Uspokój ruch na moment przed kolejną próbą."
                            HoldGesturePhase.TRIGGERED -> when (state.runtime.rotationGestureDirection) {
                                RotationGestureDirection.LEFT -> "Następny utwór — rozpoznano ruch w lewo."
                                RotationGestureDirection.RIGHT -> "Poprzedni utwór — rozpoznano ruch w prawo."
                                null -> "Zmiana utworu wysłana."
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        when {
                            !state.media.hasActiveSession -> "Uruchom odtwarzanie w aplikacji obsługującej Android MediaSession."
                            state.media.canLike && state.media.canDislike -> "Odtwarzacz obsługuje Like i Dislike wysyłane przez dwa lub trzy kliknięcia."
                            state.media.canLike -> "Odtwarzacz obsługuje polubienie, ale nie udostępnia odrzucenia."
                            state.media.canDislike -> "Odtwarzacz obsługuje odrzucenie, ale nie udostępnia polubienia."
                            else -> "Aktywna aplikacja nie udostępnia oceniania utworów przez MediaSession."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionTitle("Przycisk") }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column {
                    ButtonClickType.entries.forEachIndexed { index, click ->
                        val action = state.settings.activeProfile.actionFor(click)
                        TextButton(
                            onClick = { selectedClick = click },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(click.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                Text(action.displayName, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("Zmień", fontWeight = FontWeight.SemiBold)
                        }
                        if (index != ButtonClickType.entries.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                        }
                    }
                }
            }
        }
    }

    selectedClick?.let { click ->
        MediaActionDialog(
            title = click.displayName,
            selected = state.settings.activeProfile.actionFor(click),
            onDismiss = { selectedClick = null },
            onSelect = { action ->
                viewModel.setButtonMapping(click, action)
                selectedClick = null
            },
        )
    }
}

@Composable
private fun GestureActionRow(icon: ImageVector, movement: String, action: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(movement, style = MaterialTheme.typography.labelLarge)
            Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SensorValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

private const val ROTATION_TARGET_DEGREES = 270f

@Composable
private fun GateStatusRow(label: String, passed: Boolean, detail: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (passed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaActionDialog(
    title: String,
    selected: MediaAction,
    onDismiss: () -> Unit,
    onSelect: (MediaAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                MediaAction.entries.forEach { action ->
                    TextButton(onClick = { onSelect(action) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            action.displayName,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (action == selected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Wybrano")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
