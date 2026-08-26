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
                description = "Czekaj na wybudzenie zapamiętanego Triki i łącz je automatycznie po naciśnięciu przycisku.",
                checked = state.settings.backgroundEnabled,
                onCheckedChange = viewModel::setBackgroundEnabled,
            )
        }
        item {
            SettingSwitchRow(
                title = "Tryb deweloperski",
                description = "Pokazuje inspektor GATT, pakiety RAW, logi i generator testowych kliknięć.",
                checked = state.settings.developerMode,
                onCheckedChange = viewModel::setDeveloperMode,
            )
        }
        item { SectionTitle("System") }
        item { NavigationRow(Icons.Default.Security, "Uprawnienia", "Sprawdź dostęp do Bluetooth i informacji o odtwarzaniu.", onPermissions) }
        item { NavigationRow(Icons.Default.NotificationsActive, "Autołączenie w tle", if (state.settings.backgroundEnabled) "Telefon oczekuje na zapamiętane Triki także po jego uśpieniu." else "Wyłączone.", onPermissions) }
        if (state.settings.developerMode) {
            item { SectionTitle("Narzędzia deweloperskie") }
            item { NavigationRow(Icons.Default.Sensors, "Monitor czujników", "Dane IMU i generator testowych kliknięć.", onSensor) }
            item { NavigationRow(Icons.Default.BugReport, "Inspektor BLE", "GATT, pakiety RAW i eksport sesji.", onInspector) }
        }
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
