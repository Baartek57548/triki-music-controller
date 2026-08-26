package pl.trikimusic.controller.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import pl.trikimusic.controller.core.gesture.HoldGesturePhase
import pl.trikimusic.controller.core.gesture.RotationGestureDirection
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.components.StatusPill
import pl.trikimusic.controller.ui.components.VolumeGateState
import pl.trikimusic.controller.ui.components.volumeControlPresentation

@Composable
fun HomeScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onMediaAction: (MediaAction) -> Unit,
    onOpenDevice: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { TrikiStatusCard(state, onOpenDevice) }
        item { NowPlayingCard(state, onMediaAction, onOpenPermissions) }
        item { ControllerStatusCard(state) }
        state.runtime.lastActionError?.let { message ->
            item { InlineControlError(message) }
        }
    }
}

@Composable
private fun TrikiStatusCard(state: MainUiState, onOpenDevice: () -> Unit) {
    val ready = state.ble.connectionState == TrikiConnectionState.READY
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ) {
                    Icon(
                        if (ready) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.ble.selectedDevice?.name ?: state.settings.knownDeviceName ?: "Triki",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        connectionSummary(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(state.ble.connectionState)
            }

            if (ready) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactMetric(
                        icon = Icons.Default.BatteryFull,
                        label = "Bateria",
                        value = state.ble.battery.percent?.let { "$it%" } ?: "Brak danych",
                    )
                    CompactMetric(
                        icon = Icons.Default.SignalCellularAlt,
                        label = "Sygnał",
                        value = signalQuality(state.ble.rssi),
                    )
                }
            } else {
                Text(
                    connectionInstruction(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.ble.connectionState == TrikiConnectionState.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Button(onClick = onOpenDevice, modifier = Modifier.fillMaxWidth()) {
                    Text(connectionActionLabel(state.ble.connectionState))
                }
            }
        }
    }
}

@Composable
private fun CompactMetric(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(value, style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowPlayingCard(
    state: MainUiState,
    onMediaAction: (MediaAction) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val hasMetadata = state.media.hasPermission && state.media.hasActiveSession
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Teraz odtwarzane", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hasMetadata && state.media.artworkUri != null) {
                    AsyncImage(
                        model = state.media.artworkUri,
                        contentDescription = "Okładka albumu",
                        modifier = Modifier.size(78.dp).clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    Box(
                        Modifier.size(78.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        when {
                            hasMetadata -> state.media.title ?: "Nieznany utwór"
                            !state.media.hasPermission -> "Sterowanie muzyką"
                            else -> "Nic teraz nie gra"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            hasMetadata -> state.media.artist ?: state.media.album ?: "Nieznany wykonawca"
                            !state.media.hasPermission -> "Sterowanie działa; dostęp do tytułu i okładki jest opcjonalny."
                            else -> "Uruchom utwór w wybranej aplikacji."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasMetadata) {
                        Text(
                            if (state.media.isPlaying) "Odtwarzanie" else "Wstrzymano",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            MediaControls(state.media.isPlaying, onMediaAction)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    volumeLabel(state),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.media.hasPermission) {
                    OutlinedButton(onClick = onOpenPermissions) { Text("Pokaż informacje") }
                }
            }
        }
    }
}

@Composable
private fun MediaControls(isPlaying: Boolean, onMediaAction: (MediaAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onMediaAction(MediaAction.PREVIOUS) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Poprzedni utwór")
        }
        FilledIconButton(onClick = { onMediaAction(MediaAction.PLAY_PAUSE) }, modifier = Modifier.size(58.dp)) {
            AnimatedContent(isPlaying, label = "stan odtwarzania") { playing ->
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Wstrzymaj" else "Odtwórz",
                )
            }
        }
        IconButton(onClick = { onMediaAction(MediaAction.NEXT) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Następny utwór")
        }
    }
}

@Composable
private fun ControllerStatusCard(state: MainUiState) {
    val presentation = controllerPresentation(state)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (presentation.ready) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    when {
                        presentation.error -> Icons.Default.ErrorOutline
                        presentation.ready -> Icons.Default.CheckCircle
                        else -> Icons.Default.HourglassTop
                    },
                    contentDescription = null,
                    tint = when {
                        presentation.error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text("Sterowanie Triki", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(presentation.title, style = MaterialTheme.typography.titleLarge)
                }
                state.runtime.lastAction?.takeIf { it != MediaAction.NONE }?.let { action ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(
                            action.displayName,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(presentation.instruction, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            presentation.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            ControlHintRow(Icons.AutoMirrored.Filled.VolumeUp, "Obrót", "Głośność systemowa")
            ControlHintRow(Icons.Default.SwapHoriz, "Odwróć + obrót 270°", "Lewo: następny • prawo: poprzedni")
            ControlHintRow(Icons.Default.TouchApp, "Przycisk", "1× Play/Pause • 2× Like • 3× Dislike")
        }
    }
}

@Composable
private fun ControlHintRow(icon: ImageVector, gesture: String, result: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(gesture, style = MaterialTheme.typography.labelLarge)
            Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineControlError(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Sterowanie wymaga uwagi", fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class ControllerPresentation(
    val title: String,
    val instruction: String,
    val progress: Float? = null,
    val ready: Boolean = false,
    val error: Boolean = false,
)

private fun controllerPresentation(state: MainUiState): ControllerPresentation {
    val gesture = state.runtime.rotationGesturePhase
    if (gesture == HoldGesturePhase.TRACKING) {
        val action = when (state.runtime.rotationGestureDirection) {
            RotationGestureDirection.LEFT -> "Następny utwór"
            RotationGestureDirection.RIGHT -> "Poprzedni utwór"
            null -> "Zmiana utworu"
        }
        return ControllerPresentation(
            title = action,
            instruction = "Kontynuuj płynny obrót do 270°.",
            progress = state.runtime.rotationGestureProgress,
        )
    }
    if (gesture == HoldGesturePhase.HOLDING && state.runtime.rotationGestureFaceDown) {
        return ControllerPresentation(
            title = "Przygotowywanie zmiany utworu…",
            instruction = "Trzymaj odwrócone Triki stabilnie przez chwilę.",
            progress = state.runtime.rotationGestureProgress,
        )
    }
    if (gesture == HoldGesturePhase.REARMING) {
        return ControllerPresentation("Ustabilizuj Triki", "Uspokój ruch przed kolejnym gestem.")
    }
    if (gesture == HoldGesturePhase.TRIGGERED) {
        return ControllerPresentation("Gest rozpoznany", "Zmiana utworu została wysłana.", ready = true)
    }

    val volume = state.volumeControlPresentation()
    return when (volume.state) {
        VolumeGateState.READY -> ControllerPresentation("Gotowe", "Obracaj Triki, aby płynnie zmieniać głośność.", ready = true)
        VolumeGateState.STABILIZING -> ControllerPresentation(
            "Przygotowywanie sterowania…",
            "Utrzymaj łagodny przechył i unikaj szarpnięć.",
            state.runtime.volumeStabilizationProgress,
        )
        VolumeGateState.SUDDEN_MOTION -> ControllerPresentation("Ustabilizuj Triki", "Gwałtowny ruch przerwał przygotowanie sterowania.")
        VolumeGateState.OUTSIDE_TILT_RANGE -> ControllerPresentation("Ustaw Triki prawie poziomo", "Utrzymuj kapsel w zakresie 0–25°.")
        VolumeGateState.UPSIDE_DOWN -> ControllerPresentation("Tryb zmiany utworu", "Ustabilizuj odwrócony kapsel, a potem obróć go o 270°.")
        VolumeGateState.SENSOR_INVALID -> ControllerPresentation("Sprawdź Triki", "Nie otrzymuję prawidłowych danych ruchu.", error = true)
        VolumeGateState.NO_DATA -> ControllerPresentation("Sterowanie nieaktywne", "Połącz Triki, aby uruchomić gesty i przycisk.")
    }
}

private fun connectionSummary(state: MainUiState): String = when (state.ble.connectionState) {
    TrikiConnectionState.READY -> "Połączono i gotowe"
    TrikiConnectionState.SCANNING -> "Szukam kontrolera"
    TrikiConnectionState.FOUND -> "Triki znalezione"
    TrikiConnectionState.CONNECTING, TrikiConnectionState.CONNECTED -> "Łączenie…"
    TrikiConnectionState.RECONNECTING, TrikiConnectionState.WAITING_FOR_WAKE -> "Oczekiwanie na wybudzenie"
    TrikiConnectionState.ERROR -> "Problem z połączeniem"
    TrikiConnectionState.DISCONNECTED -> "Niepołączone"
}

private fun connectionInstruction(state: MainUiState): String = state.ble.errorMessage ?: when (state.ble.connectionState) {
    TrikiConnectionState.SCANNING -> "Naciśnij przycisk Triki, jeśli kontroler jest uśpiony."
    TrikiConnectionState.FOUND -> "Wybierz znalezione Triki na ekranie urządzenia."
    TrikiConnectionState.CONNECTING, TrikiConnectionState.CONNECTED -> "Kończę przygotowanie bezpiecznego połączenia."
    TrikiConnectionState.RECONNECTING, TrikiConnectionState.WAITING_FOR_WAKE ->
        "Naciśnij przycisk kontrolera. Telefon połączy się automatycznie."
    TrikiConnectionState.ERROR -> "Sprawdź Bluetooth i spróbuj ponownie."
    else -> "Obudź kontroler przyciskiem, a następnie znajdź i połącz Triki."
}

private fun connectionActionLabel(state: TrikiConnectionState): String = when (state) {
    TrikiConnectionState.ERROR -> "Sprawdź połączenie"
    TrikiConnectionState.SCANNING, TrikiConnectionState.CONNECTING, TrikiConnectionState.CONNECTED -> "Zobacz szczegóły"
    TrikiConnectionState.RECONNECTING, TrikiConnectionState.WAITING_FOR_WAKE -> "Otwórz urządzenie"
    else -> "Znajdź Triki"
}

private fun signalQuality(rssi: Int?): String = when {
    rssi == null -> "Brak danych"
    rssi >= -60 -> "Bardzo dobry"
    rssi >= -72 -> "Dobry"
    rssi >= -84 -> "Słaby"
    else -> "Bardzo słaby"
}

private fun volumeLabel(state: MainUiState): String {
    if (state.media.maxVolume <= 0) return "Głośność systemowa"
    val percent = (state.media.volume.toFloat() / state.media.maxVolume * 100f).roundToInt().coerceIn(0, 100)
    return "Głośność $percent%${if (state.media.isMuted) " • wyciszona" else ""}"
}
