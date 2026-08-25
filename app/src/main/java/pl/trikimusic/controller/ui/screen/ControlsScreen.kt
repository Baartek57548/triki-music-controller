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
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.SectionTitle

@Composable
fun ControlsScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    viewModel: MainViewModel,
) {
    var selectedClick by remember { mutableStateOf<ButtonClickType?>(null) }
    val sample = state.runtime.latestSample
    val accelerationMagnitude = sample?.accelerationMagnitude

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
            SectionTitle(
                "Sterowanie",
                subtitle = "Obrót kapsla reguluje głośność, a przycisk obsługuje pozostałe akcje",
            )
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.runtime.volumeControlStationary) {
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
                            Text("Głośność z osi Z", style = MaterialTheme.typography.titleLarge)
                            Text(
                                volumeStatusText(state),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Icon(
                            if (state.runtime.volumeControlStationary) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (state.runtime.volumeControlStationary) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    HorizontalDivider()
                    SensorValueRow("Żyroskop Z", "%+.1f °/s".format(state.runtime.volumeGyroscopeZDps))
                    SensorValueRow("Akcelerometr |a|", accelerationMagnitude?.let { "%.3f g".format(it) } ?: "—")
                    SensorValueRow("Dozwolony zakres", "0,800–1,200 g")
                    Text(
                        "Dodatnia wartość Z podgłaśnia, ujemna ścisza. Szybszy obrót daje szybszą zmianę; martwa strefa i krótka stabilizacja chronią przed przypadkowymi skokami.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionTitle("Przycisk", subtitle = "Ustaw akcję dla liczby kliknięć") }
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

private fun volumeStatusText(state: MainUiState): String = when {
    state.runtime.latestSample == null -> "Połącz Triki, aby uruchomić regulator."
    state.runtime.volumeControlStationary -> "Gotowe — obróć kapsel w miejscu."
    state.runtime.volumeAccelerometerWithinTolerance -> "Stabilizacja bezruchu…"
    else -> "Zmiana zablokowana — akcelerometr jest poza tolerancją ±20%."
}

@Composable
private fun SensorValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
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
