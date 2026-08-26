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
fun InfoScreen(
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { DetailTopBar("Informacje", onBack) }) { padding ->
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
                    InfoSection(
                        "Połączenie",
                        "Przy pierwszym użyciu wybudź Triki przyciskiem, wyszukaj je i połącz. Telefon zapamięta kapsel; kolejne naciśnięcie przycisku wybudzi urządzenie i uruchomi automatyczne łączenie.",
                    )
                    InfoSection(
                        "Głośność",
                        "Utrzymuj przechył kapsla w zakresie 0–25° przez 2 sekundy. Kapsel nie musi leżeć nieruchomo, ale gwałtowne przyspieszenie poza 0,80–1,20 g wstrzymuje regulację i rozpoczyna stabilizację od nowa. Po aktywacji wygładzona wartość żyroskopu osi Z łagodnie reguluje głośność.",
                    )
                    InfoSection(
                        "Like i Dislike",
                        "Dwa kliknięcia przycisku lubią utwór, a trzy kliknięcia go odrzucają. Niezależnie od przycisku możesz odwrócić kapsel, odczekać pół sekundy i wykonać jeden pełny obrót wokół osi Z: w prawo przechodzi do następnego utworu, w lewo wraca do poprzedniego.",
                    )
                    InfoSection(
                        "Prywatność i zgodność",
                        "Dane IMU, profile i kalibracja pozostają lokalnie w telefonie. Aplikacja korzysta ze standardowego Bluetooth LE i publicznych API Androida; sieć służy do sprawdzania wydań GitHub.",
                    )
                    InfoSection(
                        "Protokół",
                        "Komunikacja używa Nordic UART Service. Inspektor zachowuje pakiety RAW dla wariantów firmware, których format nie został jeszcze potwierdzony.",
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
