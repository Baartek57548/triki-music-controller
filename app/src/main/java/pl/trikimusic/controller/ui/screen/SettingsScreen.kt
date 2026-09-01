package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.InfoDialog
import pl.trikimusic.controller.ui.components.NavigationRow
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.TrikiCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    viewModel: MainViewModel,
    onPermissions: () -> Unit,
    onSensor: () -> Unit,
    onInspector: () -> Unit,
    onInfo: () -> Unit,
) {
    var showAngleInfo by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionTitle(
                title = "Połączenie i zasilanie",
                icon = Icons.Default.BluetoothConnected,
            )
        }

        item {
            TrikiCard {
                Column {
                    SettingSwitchRow(
                        title = "Sterowanie w tle",
                        description = "Automatyczne wznawianie połączenia po naciśnięciu przycisku.",
                        checked = state.settings.backgroundEnabled,
                        onCheckedChange = viewModel::setBackgroundEnabled,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    SettingSwitchRow(
                        title = "Oszczędzanie energii",
                        description = "Usypianie sesji po 12 sekundach bezczynności kontrolera.",
                        checked = state.settings.connectOnlyWhenNeeded,
                        onCheckedChange = viewModel::setConnectOnlyWhenNeeded,
                    )
                }
            }
        }

        item {
            SectionTitle(
                title = "Kąt obrotu (Zmiana utworu)",
                icon = Icons.Default.SwapHoriz,
                onInfoClick = { showAngleInfo = true },
            )
        }

        item {
            TrikiCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Wymagany kąt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${state.settings.rotationAngleDegrees}°",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Slider(
                        value = state.settings.rotationAngleDegrees.toFloat(),
                        onValueChange = { viewModel.setRotationAngleDegrees(it.roundToInt()) },
                        valueRange = 90f..360f,
                        steps = 26,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(180, 200, 270, 360).forEach { preset ->
                            val selected = state.settings.rotationAngleDegrees == preset
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setRotationAngleDegrees(preset) },
                                label = { Text(if (preset == 200) "200° (Domyślny)" else "$preset°") },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(
                title = "Dźwięki i wygląd",
                icon = Icons.Default.Palette,
            )
        }

        item {
            TrikiCard {
                Column {
                    SettingSwitchRow(
                        title = "Sygnał ocen Like / Dislike",
                        description = "Dźwiękowe potwierdzenie polubienia i odrzucenia utworu.",
                        checked = state.settings.enableSoundFeedback,
                        onCheckedChange = viewModel::setEnableSoundFeedback,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Motyw aplikacji", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemePreference.entries.forEachIndexed { index, theme ->
                                SegmentedButton(
                                    selected = state.settings.theme == theme,
                                    onClick = { viewModel.setTheme(theme) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemePreference.entries.size),
                                    label = { Text(theme.displayName) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(
                title = "Narzędzia i diagnostyka",
                icon = Icons.Default.Code,
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Security,
                title = "Uprawnienia systemowe",
                subtitle = "Bluetooth, powiadomienia i odtwarzacz.",
                onClick = onPermissions,
            )
        }

        item {
            TrikiCard {
                SettingSwitchRow(
                    title = "Tryb deweloperski",
                    description = "Dostęp do monitora IMU i inspektora Bluetooth LE.",
                    checked = state.settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )
            }
        }

        if (state.settings.developerMode) {
            item {
                NavigationRow(
                    icon = Icons.Default.Sensors,
                    title = "Monitor czujników IMU",
                    subtitle = "Wykresy przyspieszenia i żyroskopu na żywo.",
                    onClick = onSensor,
                )
            }
            item {
                NavigationRow(
                    icon = Icons.Default.BugReport,
                    title = "Inspektor Bluetooth LE",
                    subtitle = "Struktura GATT i dziennik pakietów RAW.",
                    onClick = onInspector,
                )
            }
        }

        item {
            NavigationRow(
                icon = Icons.Default.Info,
                title = "O aplikacji Triki",
                subtitle = "Wersja, licencja i informacje o projekcie.",
                onClick = onInfo,
            )
        }
    }

    if (showAngleInfo) {
        InfoDialog(
            title = "Kąt wymaganego obrotu",
            onDismiss = { showAngleInfo = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Parametr ten określa, o ile stopni należy obrócić odwróconym kontrolerem, aby aplikacja uznała gest za wykonany i przełączyła utwór.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Domyślna wartość wynosi 200°, co zapewnia idealny balans między wygodą ruchu dłoni a odpornością na przypadkowe poruszenia.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}


