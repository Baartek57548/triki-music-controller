package pl.trikimusic.controller.ui.screen

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import pl.trikimusic.controller.core.gesture.RatingGestureAction
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.core.gesture.HoldGesturePhase
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
    val sample = state.runtime.latestSample
    val volumePresentation = state.volumeControlPresentation()

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
                    HorizontalDivider()
                    GateStatusRow(
                        "Zakres stabilizacji",
                        state.runtime.volumeWithinTiltRange,
                        sample?.let { "przechył %.1f° · dozwolone 0–25°".format(state.runtime.volumeTiltDegrees) } ?: "brak danych",
                    )
                    HorizontalDivider()
                    GateStatusRow(
                        "Bez gwałtownego ruchu",
                        state.runtime.volumeAccelerationStable,
                        sample?.let { "|ACC| %.2f g · dozwolone 0,80–1,20 g".format(it.accelerationMagnitude) } ?: "brak danych",
                    )
                    HorizontalDivider()
                    SensorValueRow(
                        "Żyroskop Z",
                        sample?.let { "%+.1f °/s".format(state.runtime.volumeGyroscopeZDps) } ?: "—",
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Przytrzymaj + ruch pionowy", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("W górę → polub utwór", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.ThumbDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("W dół → odrzuć utwór", Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    Text(
                        when (state.runtime.ratingGesturePhase) {
                            HoldGesturePhase.IDLE -> "Gotowe — rozpocznij od przytrzymania przycisku."
                            HoldGesturePhase.HOLDING -> "Przytrzymanie %.0f%%".format(
                                state.runtime.ratingGestureHoldProgress * 100f,
                            )
                            HoldGesturePhase.READY -> "Przycisk przytrzymany — przesuń kapsel w górę lub w dół."
                            HoldGesturePhase.TRACKING -> when (state.runtime.ratingGestureDirection) {
                                RatingGestureAction.LIKE -> "Ruch w górę: %.0f cm".format(
                                    abs(state.runtime.ratingGestureDisplacementCentimeters),
                                )
                                RatingGestureAction.DISLIKE -> "Ruch w dół: %.0f cm".format(
                                    abs(state.runtime.ratingGestureDisplacementCentimeters),
                                )
                                null -> "Potwierdzam kierunek ruchu…"
                            }
                            HoldGesturePhase.REARMING -> "Uspokój ruch na moment przed kolejną próbą."
                            HoldGesturePhase.TRIGGERED -> "Ocena wysłana — puść przycisk przed następną akcją."
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        when {
                            !state.media.hasActiveSession -> "Uruchom odtwarzanie w aplikacji obsługującej Android MediaSession."
                            state.media.canLike && state.media.canDislike -> "Odtwarzacz obsługuje polubienie i odrzucenie. Usłyszysz osobny krótki sygnał dla każdej akcji."
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
private fun SensorValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

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
