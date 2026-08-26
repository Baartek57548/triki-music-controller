package pl.trikimusic.controller.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.components.EmptyState
import pl.trikimusic.controller.ui.components.MetricCard
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.StatusPill
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
            top = contentPadding.calculateTopPadding() + 22.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sterowanie muzyką", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(state.ble.connectionState)
            }
        }

        item {
            if (state.ble.connectionState == TrikiConnectionState.READY) {
                BoxWithConstraints {
                    if (maxWidth >= 680.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TrikiVisual(state, Modifier.weight(1.1f))
                            DeviceMetrics(state, Modifier.weight(0.9f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            TrikiVisual(state, Modifier.fillMaxWidth())
                            DeviceMetrics(state, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                ConnectionCard(state, onOpenDevice)
            }
        }

        item { SectionTitle("Teraz gra") }
        item {
            when {
                !state.media.hasPermission -> MediaKeyFallbackCard(onMediaAction, onOpenPermissions)
                !state.media.hasActiveSession -> Card(shape = RoundedCornerShape(26.dp)) {
                    EmptyState("Brak aktywnego odtwarzacza", "Uruchom muzykę w dowolnej aplikacji obsługującej Android MediaSession.")
                }
                else -> NowPlayingCard(state, onMediaAction)
            }
        }

        state.runtime.lastActionError?.let { message ->
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nie udało się wykonać sterowania", style = MaterialTheme.typography.titleMedium)
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        item {
            val buttonClick = state.runtime.lastButtonClick
            val volumeTimestamp = state.runtime.lastVolumeChangeTimestampNanos
            val ratingTimestamp = state.runtime.lastRatingGestureTimestampNanos
            val buttonIsLatest = buttonClick != null &&
                buttonClick.timestampNanos >= maxOf(volumeTimestamp ?: Long.MIN_VALUE, ratingTimestamp ?: Long.MIN_VALUE)
            val ratingIsLatest = ratingTimestamp != null &&
                ratingTimestamp >= maxOf(volumeTimestamp ?: Long.MIN_VALUE, buttonClick?.timestampNanos ?: Long.MIN_VALUE)
            if (volumeTimestamp != null || ratingTimestamp != null || buttonClick != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Icon(Icons.Default.TouchApp, null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("Ostatnie sterowanie", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    buttonIsLatest -> buttonClick!!.type.displayName
                                    ratingIsLatest -> "Przytrzymanie + ruch pionowy"
                                    else -> "Obrót kapsla · oś Z"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(state.runtime.lastAction?.displayName.orEmpty(), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: MainUiState, onOpenDevice: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.BluetoothDisabled, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                if (state.ble.connectionState == TrikiConnectionState.ERROR) "Połączenie wymaga uwagi" else "Połącz Triki",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                state.ble.errorMessage ?: if (
                    state.ble.connectionState == TrikiConnectionState.RECONNECTING &&
                    state.settings.knownDeviceAddress != null
                ) {
                    "Naciśnij przycisk zapamiętanego Triki. Telefon połączy się automatycznie, gdy kapsel się wybudzi."
                } else {
                    "Obudź kapsel przyciskiem, wyszukaj go i połącz pierwszy raz."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenDevice) { Text("Przejdź do urządzenia") }
        }
    }
}

@Composable
private fun TrikiVisual(state: MainUiState, modifier: Modifier = Modifier) {
    val orientation = state.runtime.latestSample?.orientation
    val volumePresentation = state.volumeControlPresentation()
    val pitch by animateFloatAsState(orientation?.pitch?.coerceIn(-40f, 40f) ?: 0f, label = "pitch")
    val roll by animateFloatAsState(orientation?.roll?.coerceIn(-40f, 40f) ?: 0f, label = "roll")
    val yaw by animateFloatAsState(orientation?.yaw ?: 0f, label = "yaw")
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (volumePresentation.ready) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            },
        ),
    ) {
        Box(
            Modifier.fillMaxWidth().height(250.dp).background(
                Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), Color.Transparent)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .size(148.dp)
                    .graphicsLayer {
                        rotationX = -pitch
                        rotationY = roll
                        rotationZ = yaw
                        cameraDistance = 18f * density
                    },
                shape = RoundedCornerShape(46.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 18.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.13f), modifier = Modifier.size(88.dp)) {
                        Icon(Icons.Default.MusicNote, null, Modifier.padding(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Text(
                volumePresentation.title,
                modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceMetrics(state: MainUiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                Icons.Default.BatteryFull,
                "Bateria",
                state.ble.battery.percent?.let { "$it%" } ?: "—",
                Modifier.weight(1f),
            )
            MetricCard(Icons.Default.SignalCellularAlt, "RSSI", state.ble.rssi?.let { "$it dBm" } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                Icons.Default.TouchApp,
                "Sterowanie",
                latestControlName(state),
                Modifier.weight(1f),
            )
            MetricCard(
                Icons.Default.MusicNote,
                "IMU",
                state.ble.measuredSampleRateHz?.let { "%.0f Hz".format(it) } ?: "—",
                Modifier.weight(1f),
            )
        }
    }
}

private fun latestControlName(state: MainUiState): String {
    val click = state.runtime.lastButtonClick
    val volumeTimestamp = state.runtime.lastVolumeChangeTimestampNanos
    val ratingTimestamp = state.runtime.lastRatingGestureTimestampNanos
    return when {
        click != null && click.timestampNanos >= maxOf(
            volumeTimestamp ?: Long.MIN_VALUE,
            ratingTimestamp ?: Long.MIN_VALUE,
        ) -> click.type.displayName
        ratingTimestamp != null && ratingTimestamp >= (volumeTimestamp ?: Long.MIN_VALUE) ->
            state.runtime.lastAction?.displayName ?: "Ocena utworu"
        volumeTimestamp != null -> state.runtime.lastAction?.displayName ?: "Głośność"
        else -> "—"
    }
}

@Composable
private fun MediaKeyFallbackCard(
    onMediaAction: (MediaAction) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Card(shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
            Text("Sterowanie systemowe jest dostępne", style = MaterialTheme.typography.titleLarge)
            Text(
                "Po połączeniu Triki przycisk i regulator głośności działają bez tego dostępu. Włącz go opcjonalnie tylko wtedy, gdy chcesz widzieć tytuł i okładkę utworu.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onMediaAction(MediaAction.VOLUME_DOWN) }) { Icon(Icons.AutoMirrored.Filled.VolumeDown, "Ciszej") }
                IconButton(onClick = { onMediaAction(MediaAction.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Poprzedni") }
                FilledIconButton(onClick = { onMediaAction(MediaAction.PLAY_PAUSE) }, modifier = Modifier.size(58.dp)) {
                    Icon(Icons.Default.PlayArrow, "Odtwórz lub wstrzymaj")
                }
                IconButton(onClick = { onMediaAction(MediaAction.NEXT) }) { Icon(Icons.Default.SkipNext, "Następny") }
                IconButton(onClick = { onMediaAction(MediaAction.VOLUME_UP) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Głośniej") }
            }
            OutlinedButton(onClick = onOpenPermissions) { Text("Opcjonalne informacje o utworze") }
        }
    }
}

@Composable
private fun NowPlayingCard(state: MainUiState, onMediaAction: (MediaAction) -> Unit) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (state.media.artworkUri != null) {
                    AsyncImage(
                        model = state.media.artworkUri,
                        contentDescription = "Okładka albumu",
                        modifier = Modifier.size(86.dp).clip(RoundedCornerShape(20.dp)),
                    )
                } else {
                    Box(
                        Modifier.size(86.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.MusicNote, null, Modifier.size(38.dp)) }
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(state.media.title ?: "Nieznany utwór", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(state.media.artist ?: state.media.album ?: "Nieznany wykonawca", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(state.media.appName.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onMediaAction(MediaAction.VOLUME_DOWN) }) { Icon(Icons.AutoMirrored.Filled.VolumeDown, "Ciszej") }
                IconButton(onClick = { onMediaAction(MediaAction.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Poprzedni") }
                FilledIconButton(onClick = { onMediaAction(MediaAction.PLAY_PAUSE) }, modifier = Modifier.size(58.dp)) {
                    AnimatedContent(state.media.isPlaying, label = "playpause") { playing ->
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pauza" else "Odtwórz")
                    }
                }
                IconButton(onClick = { onMediaAction(MediaAction.NEXT) }) { Icon(Icons.Default.SkipNext, "Następny") }
                IconButton(onClick = { onMediaAction(MediaAction.VOLUME_UP) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Głośniej") }
            }
            Text(
                "Głośność ${state.media.volume}/${state.media.maxVolume}${if (state.media.isMuted) " · wyciszona" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
