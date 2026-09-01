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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
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
        // 1. Połączenie i zasilanie
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
                        description = "Automatyczne łączenie z kontrolerem po jego wybudzeniu.",
                        checked = state.settings.backgroundEnabled,
                        onCheckedChange = viewModel::setBackgroundEnabled,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    SettingSwitchRow(
                        title = "Oszczędzanie energii (Tryb Eco)",
                        description = "Usypianie sesji Bluetooth po 12 sekundach bezczynności.",
                        checked = state.settings.connectOnlyWhenNeeded,
                        onCheckedChange = viewModel::setConnectOnlyWhenNeeded,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Priorytet wielu urządzeń", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Gdy muzyka gra na telefonie, Triki łączy się natychmiast. Gdy nie gra, telefon ustępuje pierwszeństwa drugiemu urządzeniu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pl.trikimusic.controller.domain.model.MultiDeviceArbitrationMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.settings.multiDeviceArbitration == mode,
                                    onClick = { viewModel.setMultiDeviceArbitration(mode) },
                                    label = { Text(mode.displayName) },
                                    shape = RoundedCornerShape(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Działanie i wygląd
        item {
            SectionTitle(
                title = "Działanie i wygląd",
                icon = Icons.Default.Palette,
            )
        }

        item {
            TrikiCard {
                Column {
                    SettingSwitchRow(
                        title = "Dźwięki potwierdzenia",
                        description = "Krótki sygnał audio przy kliknięciach fizycznego przycisku.",
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

        // 3. Integracje
        item {
            SectionTitle(
                title = "Integracje",
                icon = Icons.Default.Cast,
            )
        }

        item {
            TrikiCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Spotify Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Zdalne sterowanie odtwarzaniem i głośnością na zewnętrznych głośnikach, telewizorach i konsolach w domowej sieci WiFi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 4. Narzędzia i o aplikacji
        item {
            SectionTitle(
                title = "Narzędzia i o aplikacji",
                icon = Icons.Default.Code,
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Security,
                title = "Uprawnienia systemowe",
                subtitle = "Stan uprawnień Bluetooth, powiadomień i odtwarzacza.",
                onClick = onPermissions,
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Info,
                title = "O aplikacji Triki",
                subtitle = "Wersja, licencja i informacje o projekcie.",
                onClick = onInfo,
            )
        }

        item {
            TrikiCard {
                SettingSwitchRow(
                    title = "Tryb zaawansowany (Deweloperski)",
                    description = "Dostęp do monitora wykresów IMU i inspektora GATT.",
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
