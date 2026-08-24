package pl.trikimusic.controller.ui.screen

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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
    onAbout: () -> Unit,
    onSensor: () -> Unit,
    onInspector: () -> Unit,
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
        item { SectionTitle("Ustawienia", subtitle = "Zachowanie, wygląd i narzędzia deweloperskie") }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Wygląd", style = MaterialTheme.typography.titleMedium)
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
        item {
            SettingSwitchRow(
                title = "Sterowanie w tle",
                description = "Utrzymuj połączenie tylko wtedy, gdy Triki służy jako aktywny pilot.",
                checked = state.settings.backgroundEnabled,
                onCheckedChange = viewModel::setBackgroundEnabled,
            )
        }
        item {
            SettingSwitchRow(
                title = "Developer Mode",
                description = "Pokazuje inspector GATT, RAW packets, logi i generator Fake Triki.",
                checked = state.settings.developerMode,
                onCheckedChange = viewModel::setDeveloperMode,
            )
        }
        item { SectionTitle("System") }
        item { NavigationRow(Icons.Default.Security, "Uprawnienia", "Sprawdź dostęp do Bluetooth i aktywnych MediaSession.", onPermissions) }
        item { NavigationRow(Icons.Default.NotificationsActive, "Usługa w tle", if (state.settings.backgroundEnabled) "Włączona dla aktywnego połączenia." else "Wyłączona.", onPermissions) }
        if (state.settings.developerMode) {
            item { SectionTitle("Developer") }
            item { NavigationRow(Icons.Default.Sensors, "Sensor Monitor", "Dane IMU i generator ruchu Fake Triki.", onSensor) }
            item { NavigationRow(Icons.Default.BugReport, "BLE Inspector", "GATT, RAW packets i eksport sesji.", onInspector) }
        }
        item { NavigationRow(Icons.Default.Info, "O aplikacji", "Protokół, prywatność, architektura i ograniczenia.", onAbout) }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
