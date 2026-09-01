package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.ui.UpdateStage
import pl.trikimusic.controller.ui.UpdateUiState
import pl.trikimusic.controller.ui.components.DetailTopBar
import pl.trikimusic.controller.ui.components.TrikiCard

@Composable
fun InfoScreen(
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val gitHubUrl = "https://github.com/Baartek57548/triki-music-controller"

    Scaffold(topBar = { DetailTopBar("O aplikacji", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TrikiCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(28.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column {
                            Text("Triki Music Controller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Wersja ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        enabled = updateState.stage !in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (updateState.stage == UpdateStage.CHECKING) "Sprawdzanie…" else "Sprawdź aktualizacje", fontWeight = FontWeight.SemiBold)
                    }

                    if (updateState.stage == UpdateStage.DOWNLOADING) {
                        LinearProgressIndicator(
                            progress = { updateState.downloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                        )
                    }

                    Text(
                        updateStatus(updateState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TrikiCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    InfoSection(
                        "O projekcie",
                        "Triki Music Controller to otwartoźródłowy kontroler multimedialny bazujący na sensorach IMU (żyroskop, akcelerometr) oraz łączności Bluetooth LE.",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    InfoSection(
                        "Prywatność i bezpieczeństwo",
                        "Dane telemetryczne, kalibracja i profile przetwarzane są wyłącznie lokalnie na Twoim telefonie bez wysyłania do chmury.",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    OutlinedButton(
                        onClick = { uriHandler.openUri(gitHubUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Kod źródłowy na GitHubie", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun updateStatus(state: UpdateUiState): String = when (state.stage) {
    UpdateStage.IDLE -> "Aplikacja automatycznie sprawdza nowe stabilne wydania na GitHubie."
    UpdateStage.CHECKING -> "Sprawdzam dostępność nowej wersji na GitHub…"
    UpdateStage.AVAILABLE -> "Dostępna jest nowa wersja ${state.info?.versionName ?: "stabilna"}."
    UpdateStage.DOWNLOADING -> "Pobieranie aktualizacji: ${(state.downloadProgress.coerceIn(0f, 1f) * 100f).roundToInt()}%."
    UpdateStage.AWAITING_INSTALL_PERMISSION -> "Wymagana jest zgoda na instalowanie aktualizacji APK."
    UpdateStage.READY_TO_INSTALL -> "Aktualizacja została pobrana i jest gotowa do instalacji."
    UpdateStage.ERROR -> state.errorMessage ?: "Nie udało się sprawdzić aktualizacji."
}

@Composable
private fun InfoSection(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

