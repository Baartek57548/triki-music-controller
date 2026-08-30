package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.NavigationRow
import pl.trikimusic.controller.ui.components.SectionTitle

@OptIn(ExperimentalMaterial3Api::class)
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
    LazyColumn(
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
                title = "Triki",
                subtitle = "Automatyczne łączenie i gotowość fizycznego kontrolera.",
            )
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column {
                    SettingSwitchRow(
                        title = "Sterowanie w tle",
                        description = "Po naciśnięciu przycisku uśpione Triki połączy się automatycznie.",
                        checked = state.settings.backgroundEnabled,
                        onCheckedChange = viewModel::setBackgroundEnabled,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingSwitchRow(
                        title = "Łącz tylko podczas użycia",
                        description = if (state.settings.connectOnlyWhenNeeded) {
                            "Po 12 sekundach bezczynności połączenie jest zamykane do kolejnego wybudzenia."
                        } else {
                            "Pozostaw wyłączone, jeśli Triki ma być stale gotowe podczas działania aplikacji."
                        },
                        checked = state.settings.connectOnlyWhenNeeded,
                        onCheckedChange = viewModel::setConnectOnlyWhenNeeded,
                    )
                }
            }
        }
        item {
            SectionTitle(
                title = "Gest obrotu (Zmiana utworu)",
                subtitle = "Ustawienie wymaganego kąta obrotu odwróconym kontrolerem.",
            )
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Kąt wymaganego obrotu", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.settings.rotationAngleDegrees}°",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    androidx.compose.material3.Slider(
                        value = state.settings.rotationAngleDegrees.toFloat(),
                        onValueChange = { viewModel.setRotationAngleDegrees(kotlin.math.round(it).toInt()) },
                        valueRange = 90f..360f,
                        steps = 26,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(180, 200, 270, 360).forEach { preset ->
                            val selected = state.settings.rotationAngleDegrees == preset
                            androidx.compose.material3.FilterChip(
                                selected = selected,
                                onClick = { viewModel.setRotationAngleDegrees(preset) },
                                label = { Text(if (preset == 200) "200° (Domyślny)" else "$preset°") },
                            )
                        }
                    }
                    Text(
                        "Obrót odwróconym kapslem w lewo przełącza utwór na następny, w prawo na poprzedni.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionTitle("Aplikacja") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column {
                    SettingSwitchRow(
                        title = "Dźwięki ocen (Like / Dislike)",
                        description = "Odtwarzaj krótki sygnał dźwiękowy po naciśnięciu 2x lub 3x fizycznego przycisku.",
                        checked = state.settings.enableSoundFeedback,
                        onCheckedChange = viewModel::setEnableSoundFeedback,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Motyw", style = MaterialTheme.typography.titleMedium)
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
        item { SectionTitle("Uprawnienia") }
        item { NavigationRow(Icons.Default.Security, "Uprawnienia", "Sprawdź dostęp do Bluetooth i informacji o odtwarzaniu.", onPermissions) }
        item { SectionTitle("Zaawansowane", subtitle = "Narzędzia diagnostyczne są ukryte podczas codziennego użycia.") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                SettingSwitchRow(
                    title = "Tryb deweloperski",
                    description = "Pokazuje monitor IMU, inspektor BLE, pakiety RAW i narzędzia testowe.",
                    checked = state.settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )
            }
        }
        if (state.settings.developerMode) {
            item { NavigationRow(Icons.Default.Sensors, "Monitor czujników", "Dane IMU i generator testowych kliknięć.", onSensor) }
            item { NavigationRow(Icons.Default.BugReport, "Inspektor BLE", "GATT, pakiety RAW i eksport sesji.", onInspector) }
        }
        item { SectionTitle("Informacje") }
        item { NavigationRow(Icons.Default.Info, "O aplikacji i aktualizacje", "Wersja, aktualizacje, prywatność i projekt GitHub.", onInfo) }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
