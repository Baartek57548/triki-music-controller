package pl.trikimusic.controller.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.ui.UpdateStage
import pl.trikimusic.controller.ui.UpdateUiState

@Composable
fun AppUpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onRequestInstallPermission: () -> Unit,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val update = state.info
    when (state.stage) {
        UpdateStage.IDLE,
        UpdateStage.CHECKING,
        -> Unit

        UpdateStage.AVAILABLE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Dostępna aktualizacja ${update?.versionName.orEmpty()}") },
            text = {
                Column(
                    Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Zainstalowana wersja: ${BuildConfig.VERSION_NAME}")
                    update?.releaseNotes?.takeIf(String::isNotBlank)?.let { notes ->
                        Text("Co nowego", style = MaterialTheme.typography.titleMedium)
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    update?.apkSizeBytes?.let { size ->
                        Text(
                            "Rozmiar: ${formatFileSize(size)}. Po pobraniu Android poprosi o potwierdzenie instalacji.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDownload) { Text("Pobierz i zainstaluj") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Później") } },
        )

        UpdateStage.DOWNLOADING -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Pobieranie aktualizacji") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { state.downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(state.downloadProgress * 100f).toInt().coerceIn(0, 100)}% · weryfikacja APK nastąpi automatycznie")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        )

        UpdateStage.AWAITING_INSTALL_PERMISSION -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Zezwól na instalację") },
            text = {
                Text(
                    "Android wymaga jednorazowego zezwolenia „Instaluj nieznane aplikacje” dla Triki Music. Po jego włączeniu instalator otworzy się automatycznie.",
                )
            },
            confirmButton = { TextButton(onClick = onRequestInstallPermission) { Text("Otwórz ustawienia") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Później") } },
        )

        UpdateStage.READY_TO_INSTALL -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Aktualizacja jest gotowa") },
            text = {
                Text(
                    "Systemowy instalator powinien być otwarty. Jeśli został zamknięty, możesz uruchomić go ponownie.",
                )
            },
            confirmButton = { TextButton(onClick = onInstall) { Text("Otwórz instalator") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Później") } },
        )

        UpdateStage.ERROR -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Aktualizacja nie powiodła się") },
            text = { Text(state.errorMessage ?: "Wystąpił nieznany błąd aktualizacji.") },
            confirmButton = {
                TextButton(onClick = if (update == null) onRetryCheck else onDownload) {
                    Text("Spróbuj ponownie")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f kB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
