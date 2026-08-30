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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.NavigationRow
import pl.trikimusic.controller.ui.components.SectionTitle
import pl.trikimusic.controller.ui.components.TrikiCard

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
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionTitle(
                title = "Połączenie i zasilanie",
                subtitle = "Automatyczne wznawianie pracy i oszczędzanie baterii.",
                icon = Icons.Default.BluetoothConnected,
            )
        }

        item {
            TrikiCard {
                Column {
                    SettingSwitchRow(
                        title = "Sterowanie w tle",
                        description = "Po naciśnięciu przycisku na kapslu telefon automatycznie wznawia połączenie w tle.",
                        checked = state.settings.backgroundEnabled,
                        onCheckedChange = viewModel::setBackgroundEnabled,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    SettingSwitchRow(
                        title = "Łącz tylko podczas użycia",
                        description = if (state.settings.connectOnlyWhenNeeded) {
                            "Po 12 sekundach bezczynności sesja jest usypiana w celu oszczędzania energii."
                        } else {
                            "Połączenie pozostaje aktywne przez cały czas działania aplikacji."
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
                subtitle = "Konfiguracja wymaganego kąta obrotu odwróconym kontrolerem.",
                icon = Icons.Default.SwapHoriz,
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
                        Text("Kąt wymaganego obrotu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                    Text(
                        "Obrót odwróconym kapslem w lewo przełącza na następny utwór, w prawo na poprzedni.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionTitle(
                title = "Dźwięki i wygląd",
                subtitle = "Informacje zwrotne oraz motyw interfejsu aplikacji.",
                icon = Icons.Default.Palette,
            )
        }

        item {
            TrikiCard {
                Column {
                    SettingSwitchRow(
                        title = "Sygnał dźwiękowy ocen (Like / Dislike)",
                        description = "Odtwarzaj krótki ton potwierdzający polubienie (2× klik) lub odrzucenie utworu (3× klik).",
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
                title = "Uprawnienia",
                subtitle = "Zarządzanie dostępem do Bluetooth i odtwarzacza systemowego.",
                icon = Icons.Default.Security,
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Security,
                title = "Uprawnienia systemowe",
                subtitle = "Sprawdź status dostępu do urządzeń w pobliżu i powiadomień.",
                onClick = onPermissions,
            )
        }

        item {
            SectionTitle(
                title = "Narzędzia diagnostyczne",
                subtitle = "Funkcje dla programistów oraz inspekcja pakietów BLE.",
                icon = Icons.Default.Code,
            )
        }

        item {
            TrikiCard {
                SettingSwitchRow(
                    title = "Tryb deweloperski",
                    description = "Aktywuje podgląd surowych danych telemetrycznych IMU, inspektor GATT i analizator pakietów.",
                    checked = state.settings.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )
            }
        }

        if (state.settings.developerMode) {
            item {
                NavigationRow(
                    icon = Icons.Default.Sensors,
                    title = "Monitor czujników (IMU)",
                    subtitle = "Wykresy przyspieszenia, żyroskopu i symulacja kliknięć.",
                    onClick = onSensor,
                )
            }
            item {
                NavigationRow(
                    icon = Icons.Default.BugReport,
                    title = "Inspektor Bluetooth LE",
                    subtitle = "Struktura usług GATT, pakiety RAW i dziennik zdarzeń.",
                    onClick = onInspector,
                )
            }
        }

        item {
            SectionTitle(
                title = "Informacje o projekcie",
                subtitle = "Wersja, licencja, dziennik zmian i autorzy.",
                icon = Icons.Default.Info,
            )
        }

        item {
            NavigationRow(
                icon = Icons.Default.Info,
                title = "O aplikacji Triki",
                subtitle = "Informacje o wydaniu, repozytorium GitHub i diagnostyka.",
                onClick = onInfo,
            )
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
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

