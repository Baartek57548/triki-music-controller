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
        item { ControllerLiveCard(state) }
        state.runtime.lastActionError?.let { message ->
            item { InlineControlError(message) }
        }
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
                        style = MaterialTheme.typography.bodyMedium,
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
                        value = state.ble.battery.percent?.let { "$it%" } ?: "Brak danych",
                    )
                    MetricTile(
                        icon = Icons.Default.SignalCellularAlt,
                        label = "Sygnał",
                        value = signalQualityLabel(state.ble.rssi),
                    )
                    OutlinedButton(
                        onClick = onOpenDevice,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("Zarządzaj")
                    }
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
                Button(
                    onClick = onOpenDevice,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(connectionActionLabel(state.ble.connectionState), fontWeight = FontWeight.SemiBold)
                }
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
    val hasMetadata = state.media.hasPermission && state.media.hasActiveSession
    TrikiCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "TERAZ ODTWARZANE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (hasMetadata && state.media.appName != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    ) {
                        Text(
                            state.media.appName,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (hasMetadata && state.media.artworkUri != null) {
                    AsyncImage(
                        model = state.media.artworkUri,
                        contentDescription = "Okładka albumu",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(18.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            hasMetadata -> state.media.title ?: "Nieznany utwór"
                            !state.media.hasPermission -> "Sterowanie muzyką"
                            else -> "Brak aktywnego odtwarzania"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            hasMetadata -> state.media.artist ?: state.media.album ?: "Nieznany wykonawca"
                            !state.media.hasPermission -> "Sterowanie działa w tle; dostęp do tytułu i okładki jest opcjonalny."
                            else -> "Uruchom odtwarzanie w Spotify, YouTube Music lub innej aplikacji."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasMetadata) {
                        Text(
                            if (state.media.isPlaying) "Odtwarzanie aktywne" else "Wstrzymano",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.media.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            MediaControls(
                isPlaying = state.media.isPlaying,
                onMediaAction = onMediaAction,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

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
                        Text("Głośność systemowa", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Nadaj dostęp do okładek i tytułów")
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
private fun ControllerLiveCard(state: MainUiState) {
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
                state.runtime.lastAction?.takeIf { it != MediaAction.NONE }?.let { action ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            action.displayName,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Text(
                presentation.instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            presentation.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            ControlHintRow(Icons.AutoMirrored.Filled.VolumeUp, "Pochylenie i obrót", "Płynna regulacja głośności")
            ControlHintRow(Icons.Default.SwapHoriz, "Odwrócenie + obrót $angle°", "Lewo: następny • prawo: poprzedni")
            ControlHintRow(Icons.Default.TouchApp, "Fizyczny przycisk", "1× Play/Pause • 2× Like • 3× Dislike")
        }
    }
}

@Composable
private fun ControlHintRow(icon: ImageVector, gesture: String, result: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(7.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(gesture, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    TrikiConnectionState.SCANNING -> "Wyszukiwanie aktywnego kontrolera…"
    TrikiConnectionState.FOUND -> "Wykryto kontroler Triki"
    TrikiConnectionState.CONNECTING -> "Nawiązywanie połączenia…"
    TrikiConnectionState.CONNECTED -> "Konfigurowanie usług urządzenia…"
    TrikiConnectionState.WAITING_FOR_WAKE -> if (state.ble.wakeWatcherArmed) {
        "Nasłuch w toku — naciśnij przycisk na kapslu"
    } else {
        "Oczekiwanie na przejście w stan uśpienia…"
    }
    TrikiConnectionState.RECONNECTING -> "Oczekiwanie na wybudzenie Triki…"
    TrikiConnectionState.ERROR -> state.ble.errorMessage ?: "Wystąpił błąd połączenia"
    TrikiConnectionState.DISCONNECTED -> state.settings.knownDeviceAddress?.let { "Zapamiętano: $it" } ?: "Brak połączonego urządzenia"
}

private fun connectionInstruction(state: MainUiState): String = when (state.ble.connectionState) {
    TrikiConnectionState.DISCONNECTED -> if (state.settings.knownDeviceAddress != null) {
        "Naciśnij przycisk na kapslu Triki, aby wybudzić urządzenie i wznowić połączenie."
    } else {
        "Naciśnij przycisk na kapslu Triki, a następnie kliknij przycisk poniżej, aby wyszukać kontroler."
    }
    TrikiConnectionState.SCANNING -> "Szukam urządzenia Triki w pobliżu. Upewnij się, że kapsel jest wybudzony."
    TrikiConnectionState.FOUND -> "Znaleziono kontroler Triki. Trwa przygotowanie do połączenia."
    TrikiConnectionState.CONNECTING -> "Łączę z kontrolerem Triki…"
    TrikiConnectionState.CONNECTED -> "Odczytuję stan czujników i informacje o urządzeniu…"
    TrikiConnectionState.WAITING_FOR_WAKE,
    TrikiConnectionState.RECONNECTING,
    -> "Naciśnij przycisk na kapslu Triki — telefon automatycznie wznowi połączenie."
    TrikiConnectionState.ERROR -> state.ble.errorMessage ?: "Nie udało się połączyć z Triki. Naciśnij przycisk na kapslu i spróbuj ponownie."
    TrikiConnectionState.READY -> "Sterowanie jest aktywne."
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
    val instruction: String,
    val progress: Float?,
)

private fun controllerPresentation(state: MainUiState): ControllerCardPresentation {
    val volume = state.volumeControlPresentation()
    val rotationAngle = state.settings.rotationAngleDegrees
    return when {
        state.ble.connectionState != TrikiConnectionState.READY -> ControllerCardPresentation(
            ready = false,
            error = false,
            title = "Kontroler uśpiony lub rozłączony",
            instruction = "Naciśnij przycisk na kapslu Triki, aby uruchomić sterowanie.",
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
                title = "Przygotowanie zmiany utworu",
                instruction = if (state.runtime.rotationGestureFaceDown) {
                    "Kapsel odwrócony — stabilizacja %.0f%%".format(state.runtime.rotationGestureProgress * 100f)
                } else {
                    "Odwróć kapsel górą w dół i uspokój go przed ruchem."
                },
                progress = state.runtime.rotationGestureProgress,
            )
            HoldGesturePhase.READY -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Obróć o $rotationAngle°",
                instruction = "Ruch w lewo = następny utwór, ruch w prawo = poprzedni.",
                progress = null,
            )
            HoldGesturePhase.TRACKING -> {
                val dir = when (state.runtime.rotationGestureDirection) {
                    RotationGestureDirection.LEFT -> "w lewo (następny)"
                    RotationGestureDirection.RIGHT -> "w prawo (poprzedni)"
                    null -> "obrotu"
                }
                ControllerCardPresentation(
                    ready = true,
                    error = false,
                    title = "Rozpoznawanie: $dir",
                    instruction = "Postęp obrotu: %.0f° / $rotationAngle°".format(state.runtime.rotationGestureProgress * rotationAngle),
                    progress = state.runtime.rotationGestureProgress,
                )
            }
            HoldGesturePhase.COMPLETING -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Dokończ obrót do $rotationAngle°",
                instruction = "Wykonaj pełny ruch, aby zmienić utwór.",
                progress = 1f,
            )
            HoldGesturePhase.TRIGGERED -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Zmieniono utwór",
                instruction = "Gest został rozpoznany i wysłany.",
                progress = 1f,
            )
            else -> ControllerCardPresentation(
                ready = true,
                error = false,
                title = "Gest obrotu",
                instruction = "Odwróć kapsel i obróć o $rotationAngle°.",
                progress = null,
            )
        }

        volume.ready -> ControllerCardPresentation(
            ready = true,
            error = false,
            title = "Płynna regulacja głośności",
            instruction = "Obracaj kapsel wokół osi Z, aby łagodnie zmieniać poziom dźwięku.",
            progress = null,
        )

        volume.state == VolumeGateState.STABILIZING -> ControllerCardPresentation(
            ready = false,
            error = false,
            title = "Stabilizacja głośności…",
            instruction = "Utrzymuj kapsel prawie poziomo (0–25°) i unikaj gwałtownych wstrząsów.",
            progress = state.runtime.volumeStabilizationProgress,
        )

        else -> ControllerCardPresentation(
            ready = false,
            error = false,
            title = volume.title,
            instruction = volume.instruction,
            progress = null,
        )
    }
}
