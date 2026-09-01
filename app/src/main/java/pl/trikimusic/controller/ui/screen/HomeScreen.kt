package pl.trikimusic.controller.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import pl.trikimusic.controller.core.gesture.HoldGesturePhase
import pl.trikimusic.controller.core.gesture.RotationGestureDirection
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.components.InfoDialog
import pl.trikimusic.controller.ui.components.MetricTile
import pl.trikimusic.controller.ui.components.StatusPill
import pl.trikimusic.controller.ui.components.TrikiCard
import pl.trikimusic.controller.ui.components.VolumeGateState
import pl.trikimusic.controller.ui.components.signalQualityLabel
import pl.trikimusic.controller.ui.components.volumeControlPresentation

@Composable
fun HomeScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onMediaAction: (MediaAction) -> Unit,
    onOpenDevice: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    var showGesturesHelp by remember { mutableStateOf(false) }

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
        item { TrikiHeroStatusCard(state, onOpenDevice) }
        item { NowPlayingCard(state, onMediaAction, onOpenPermissions) }
        item { ControllerLiveCard(state, onOpenHelp = { showGesturesHelp = true }) }
        state.runtime.lastActionError?.let { message ->
            item { InlineControlError(message) }
        }
    }

    if (showGesturesHelp) {
        GesturesHelpDialog(
            angle = state.settings.rotationAngleDegrees,
            onDismiss = { showGesturesHelp = false },
        )
    }
}

@Composable
private fun TrikiHeroStatusCard(state: MainUiState, onOpenDevice: () -> Unit) {
    val ready = state.ble.connectionState == TrikiConnectionState.READY
    TrikiCard(
        containerColor = if (ready) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (ready) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Icon(
                        if (ready) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.ble.selectedDevice?.name ?: state.settings.knownDeviceName ?: "Triki",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        connectionSummary(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(state.ble.connectionState)
            }

            if (ready) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricTile(
                        icon = Icons.Default.BatteryFull,
                        label = "Bateria",
                        value = state.ble.battery.percent?.let { "$it%" } ?: "—",
                    )
                    MetricTile(
                        icon = Icons.Default.SignalCellularAlt,
                        label = "Sygnał",
                        value = signalQualityLabel(state.ble.rssi),
                    )
                }
            }

            Button(
                onClick = onOpenDevice,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(connectionActionLabel(state.ble.connectionState), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    state: MainUiState,
    onMediaAction: (MediaAction) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val hasTrack = !state.media.title.isNullOrBlank()

    TrikiCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    if (state.media.artworkUri != null) {
                        AsyncImage(
                            model = state.media.artworkUri,
                            contentDescription = "Okładka albumu",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (hasTrack) state.media.title.orEmpty() else "Brak odtwarzanego utworu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (hasTrack) state.media.artist ?: "Nieznany wykonawca" else "Uruchom odtwarzacz muzyki",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.media.appName?.let { app ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        ) {
                            Text(
                                app,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            MediaControls(
                isPlaying = state.media.isPlaying,
                onMediaAction = onMediaAction,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(onClick = { onMediaAction(MediaAction.VOLUME_DOWN) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Ciszej", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Głośność", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${state.media.volumePercent}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = {
                            if (state.media.maxVolume > 0) (state.media.volume.toFloat() / state.media.maxVolume.toFloat()).coerceIn(0f, 1f)
                            else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
                IconButton(onClick = { onMediaAction(MediaAction.VOLUME_UP) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Głośniej", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (!state.media.hasPermission) {
                OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Nadaj dostęp do powiadomień multimediów")
                }
            }
        }
    }
}

@Composable
private fun MediaControls(
    isPlaying: Boolean,
    onMediaAction: (MediaAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onMediaAction(MediaAction.LIKE) },
            modifier = Modifier.size(46.dp),
        ) {
            Icon(Icons.Default.ThumbUp, contentDescription = "Polub utwór", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(
            onClick = { onMediaAction(MediaAction.PREVIOUS) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Poprzedni utwór", modifier = Modifier.size(28.dp))
        }
        FilledIconButton(
            onClick = { onMediaAction(MediaAction.PLAY_PAUSE) },
            modifier = Modifier.size(60.dp),
        ) {
            AnimatedContent(isPlaying, label = "stan odtwarzania") { playing ->
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Wstrzymaj" else "Odtwórz",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        IconButton(
            onClick = { onMediaAction(MediaAction.NEXT) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = "Następny utwór", modifier = Modifier.size(28.dp))
        }
        IconButton(
            onClick = { onMediaAction(MediaAction.DISLIKE) },
            modifier = Modifier.size(46.dp),
        ) {
            Icon(Icons.Default.ThumbDown, contentDescription = "Odrzuć utwór", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControllerLiveCard(
    state: MainUiState,
    onOpenHelp: () -> Unit,
) {
    val presentation = controllerPresentation(state)
    val angle = state.settings.rotationAngleDegrees

    TrikiCard(
        containerColor = if (presentation.ready) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    Text("Sterowanie Triki", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(presentation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = onOpenHelp,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Przewodnik po gestach",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            presentation.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f).clickable { onOpenHelp() },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(2.dp))
                        Text("Głośność", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f).clickable { onOpenHelp() },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(2.dp))
                        Text("Obrót $angle°", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f).clickable { onOpenHelp() },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(2.dp))
                        Text("Przycisk", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GesturesHelpDialog(angle: Int, onDismiss: () -> Unit) {
    InfoDialog(title = "Przewodnik po gestach Triki", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HelpItem(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Regulacja głośności",
                description = "Trzymaj kapsel w pozycji zbliżonej do poziomej (przechył 0–25°) przez około 2 sekundy. Po ustabilizowaniu obracaj kapsel wokół osi Z, aby płynnie zmieniać poziom dźwięku.",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            HelpItem(
                icon = Icons.Default.SwapHoriz,
                title = "Zmiana utworu (obrót $angle°)",
                description = "Odwróć kapsel górą w dół i odczekaj 0,5 s na potwierdzenie pozycji. Następnie obróć dłoń: ruch w lewo przełącza na następny utwór, a ruch w prawo na poprzedni.",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            HelpItem(
                icon = Icons.Default.TouchApp,
                title = "Przycisk fizyczny",
                description = "1× kliknięcie: Play/Pause. 2× kliknięcia: Polubienie utworu (Like). 3× kliknięcia: Odrzucenie utworu (Dislike).",
            )
        }
    }
}

@Composable
private fun HelpItem(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

private fun connectionSummary(state: MainUiState): String = when (state.ble.connectionState) {
    TrikiConnectionState.READY -> state.ble.selectedDevice?.address
        ?: state.settings.knownDeviceAddress
        ?: "Połączono"
    TrikiConnectionState.SCANNING -> "Wyszukiwanie…"
    TrikiConnectionState.FOUND -> "Wykryto kontroler"
    TrikiConnectionState.CONNECTING -> "Nawiązywanie połączenia…"
    TrikiConnectionState.CONNECTED -> "Konfigurowanie…"
    TrikiConnectionState.WAITING_FOR_WAKE -> "Oczekiwanie na wybudzenie…"
    TrikiConnectionState.RECONNECTING -> "Wznawianie połączenia…"
    TrikiConnectionState.ERROR -> state.ble.errorMessage ?: "Błąd połączenia"
    TrikiConnectionState.DISCONNECTED -> state.settings.knownDeviceAddress?.let { "Zapamiętano: $it" } ?: "Brak urządzenia"
}

private fun connectionActionLabel(state: TrikiConnectionState): String = when (state) {
    TrikiConnectionState.DISCONNECTED -> "Wyszukaj i połącz"
    TrikiConnectionState.SCANNING -> "Trwa skanowanie…"
    TrikiConnectionState.FOUND -> "Połącz"
    TrikiConnectionState.CONNECTING -> "Łączenie…"
    TrikiConnectionState.CONNECTED -> "Konfigurowanie…"
    TrikiConnectionState.WAITING_FOR_WAKE,
    TrikiConnectionState.RECONNECTING,
    -> "Zarządzaj połączeniem"
    TrikiConnectionState.ERROR -> "Spróbuj ponownie"
    TrikiConnectionState.READY -> "Szczegóły urządzenia"
}

private data class ControllerCardPresentation(
    val ready: Boolean,
    val error: Boolean,
    val title: String,
    val progress: Float?,
)

private fun controllerPresentation(state: MainUiState): ControllerCardPresentation {
    val volume = state.volumeControlPresentation()
    val rotationAngle = state.settings.rotationAngleDegrees
    return when {
        state.ble.connectionState != TrikiConnectionState.READY -> ControllerCardPresentation(
            ready = false,
            error = false,
            title = "Kontroler rozłączony",
            progress = null,
        )

        state.runtime.rotationGesturePhase in setOf(
            HoldGesturePhase.HOLDING,
            HoldGesturePhase.READY,
            HoldGesturePhase.TRACKING,
            HoldGesturePhase.COMPLETING,
            HoldGesturePhase.TRIGGERED,
        ) -> when (state.runtime.rotationGesturePhase) {
            HoldGesturePhase.HOLDING -> ControllerCardPresentation(
                ready = false,
                error = false,
                title = if (state.runtime.rotationGestureFaceDown) {
                    "Stabilizacja pozycji: %.0f%%".format(state.runtime.rotationGestureProgress * 100f)
                } else {
                    "Odwróć kapsel górą w dół"
                },
                progress = state.runtime.rotationGestureProgress,
            )
            HoldGesturePhase.READY -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Gotowy do obrotu ($rotationAngle°)",
                progress = null,
            )
            HoldGesturePhase.TRACKING -> {
                val dir = when (state.runtime.rotationGestureDirection) {
                    RotationGestureDirection.LEFT -> "w lewo (następny)"
                    RotationGestureDirection.RIGHT -> "w prawo (poprzedni)"
                    null -> "obrót"
                }
                ControllerCardPresentation(
                    ready = true,
                    error = false,
                    title = "Obrót $dir: %.0f°".format(state.runtime.rotationGestureProgress * rotationAngle),
                    progress = state.runtime.rotationGestureProgress,
                )
            }
            HoldGesturePhase.COMPLETING -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Dokończ ruch do $rotationAngle°",
                progress = 1f,
            )
            HoldGesturePhase.TRIGGERED -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Zmieniono utwór",
                progress = 1f,
            )
            else -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Gest obrotu",
                progress = null,
            )
        }

        volume.ready -> ControllerCardPresentation(
            ready = true,
            error = false,
            title = "Regulacja głośności aktywna",
            progress = null,
        )

        volume.state == VolumeGateState.STABILIZING -> ControllerCardPresentation(
            ready = false,
            error = false,
            title = "Stabilizacja głośności…",
            progress = state.runtime.volumeStabilizationProgress,
        )

        else -> ControllerCardPresentation(
            ready = false,
            error = false,
            title = volume.title,
            progress = null,
        )
    }
}
