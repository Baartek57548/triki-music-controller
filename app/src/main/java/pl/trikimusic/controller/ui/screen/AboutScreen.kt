package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.ui.UpdateStage
import pl.trikimusic.controller.ui.UpdateUiState
import pl.trikimusic.controller.ui.components.DetailTopBar

@Composable
fun AboutScreen(
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { DetailTopBar("O aplikacji", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Text("Triki Music Controller", style = MaterialTheme.typography.headlineMedium)
            Text("Wersja ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = onCheckForUpdates,
                enabled = updateState.stage !in setOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING),
            ) {
                Text(if (updateState.stage == UpdateStage.CHECKING) "Sprawdzanie…" else "Sprawdź aktualizacje")
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Interoperacyjność", style = MaterialTheme.typography.titleMedium)
                    Text("Aplikacja korzysta wyłącznie z publicznych API Androida i standardowego Bluetooth LE. Nie omija zabezpieczeń Żappki ani usług muzycznych.")
                    Text("Prywatność", style = MaterialTheme.typography.titleMedium)
                    Text("Dane IMU, profile i kalibracja pozostają lokalnie na urządzeniu. Logi są ograniczone rotacją i eksportowane tylko na wyraźne żądanie użytkownika.")
                    Text("Protokół", style = MaterialTheme.typography.titleMedium)
                    Text("Implementacja NUS i dekodera opiera się na zweryfikowanej analizie projektu Maku-hub/TrikiScope. Inspector zachowuje RAW dla wariantów firmware, których format nie został jeszcze potwierdzony.")
                }
            }
        }
    }
}
