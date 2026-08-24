package pl.trikimusic.controller.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trikimusic.controller.domain.model.ControlProfile
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MIN_PERSONALIZED_SAMPLES_PER_GESTURE
import pl.trikimusic.controller.domain.model.SensitivityLevel
import pl.trikimusic.controller.ui.MainUiState
import pl.trikimusic.controller.ui.MainViewModel
import pl.trikimusic.controller.ui.components.NavigationRow
import pl.trikimusic.controller.ui.components.SectionTitle

@Composable
fun GesturesScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    viewModel: MainViewModel,
    onOpenTrainer: () -> Unit,
    onOpenWizard: () -> Unit,
) {
    var selectedGesture by remember { mutableStateOf<GestureType?>(null) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var profileDialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    val profile = state.settings.activeProfile
    val haptics = LocalHapticFeedback.current

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
        item { SectionTitle("Sterowanie", subtitle = "Przypisz akcje do ruchów Triki") }
        if (!state.settings.calibration.isValid) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Sterowanie gestami jest zablokowane", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Wykonaj kalibrację nieruchomego Triki w zakładce Device. Bez kalibracji aplikacja nie uruchomi żadnej akcji multimedialnej.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Aktywny profil", style = MaterialTheme.typography.bodyMedium)
                        Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    }
                    ProfilePicker(state.settings.profiles, profile, viewModel::setActiveProfile)
                    IconButton(onClick = { showProfileMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Opcje profilu")
                    }
                    DropdownMenu(expanded = showProfileMenu, onDismissRequest = { showProfileMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Nowy profil") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { showProfileMenu = false; profileDialog = ProfileDialog.Create },
                        )
                        DropdownMenuItem(
                            text = { Text("Kopiuj profil") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { showProfileMenu = false; profileDialog = ProfileDialog.Copy },
                        )
                        DropdownMenuItem(
                            text = { Text("Zmień nazwę") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            enabled = !profile.builtIn,
                            onClick = { showProfileMenu = false; profileDialog = ProfileDialog.Rename },
                        )
                        DropdownMenuItem(
                            text = { Text("Usuń") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            enabled = !profile.builtIn,
                            onClick = { showProfileMenu = false; profileDialog = ProfileDialog.Delete },
                        )
                    }
                }
            }
        }
        item { SectionTitle("Mapowanie gestów") }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column {
                    GestureType.entries.forEachIndexed { index, gesture ->
                        val action = profile.actionFor(gesture)
                        MappingRow(
                            gesture = gesture,
                            action = action,
                            learnedSamples = state.settings.personalizedGestureModel.sampleCountFor(gesture),
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedGesture = gesture
                        }
                        if (index != GestureType.entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    }
                }
            }
        }
        item { SectionTitle("Czułość ruchu", subtitle = "Presety zmieniają progi, filtr i cooldown jako spójny zestaw") }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SensitivityLevel.entries.filterNot { it == SensitivityLevel.ADVANCED }.forEach { level ->
                    FilterChip(
                        selected = state.settings.sensitivity == level,
                        onClick = { viewModel.setSensitivity(level) },
                        label = { Text(level.displayName) },
                    )
                }
            }
        }
        item {
            AssistChip(
                onClick = { showAdvanced = true },
                label = { Text("Advanced: konkretne progi") },
                leadingIcon = { Icon(Icons.Default.Tune, null) },
            )
        }
        item {
            NavigationRow(
                Icons.Default.Tune,
                "Kreator gestów",
                "Przejdź przez wszystkie gesty, sprawdź ruchy i ustaw własne akcje.",
                onOpenWizard,
            )
        }
        item {
            NavigationRow(
                Icons.Default.Psychology,
                "Naucz gest",
                "Nagraj dokładny zakres przyciskami Start/Stop i zweryfikuj klasyfikację.",
                onOpenTrainer,
            )
        }
    }

    selectedGesture?.let { gesture ->
        ActionPickerDialog(
            gesture = gesture,
            current = profile.actionFor(gesture),
            onDismiss = { selectedGesture = null },
            onSelect = { action ->
                viewModel.setMapping(gesture, action)
                selectedGesture = null
            },
        )
    }
    profileDialog?.let { dialog ->
        ProfileDialogContent(
            dialog = dialog,
            activeProfile = profile,
            onDismiss = { profileDialog = null },
            onConfirm = { name ->
                when (dialog) {
                    ProfileDialog.Create -> viewModel.createProfile(name)
                    ProfileDialog.Copy -> viewModel.copyActiveProfile(name)
                    ProfileDialog.Rename -> viewModel.renameActiveProfile(name)
                    ProfileDialog.Delete -> viewModel.deleteActiveProfile()
                }
                profileDialog = null
            },
        )
    }
    if (showAdvanced) {
        AdvancedThresholdsDialog(
            initial = state.settings.advancedThresholds,
            onDismiss = { showAdvanced = false },
            onSave = { viewModel.setAdvancedThresholds(it); showAdvanced = false },
        )
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<ControlProfile>,
    active: ControlProfile,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text("Zmień") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        profiles.forEach { profile ->
            DropdownMenuItem(
                text = { Text(profile.name, fontWeight = if (profile.id == active.id) FontWeight.Bold else FontWeight.Normal) },
                onClick = { onSelect(profile.id); expanded = false },
            )
        }
    }
}

@Composable
private fun MappingRow(
    gesture: GestureType,
    action: MediaAction,
    learnedSamples: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(gesture.displayName, style = MaterialTheme.typography.bodyLarge)
            if (learnedSamples > 0) {
                Text(
                    if (learnedSamples >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE) {
                        "ML lokalny · $learnedSamples próbek"
                    } else {
                        "ML lokalny · 1/$MIN_PERSONALIZED_SAMPLES_PER_GESTURE próbek"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        TextButton(onClick = onClick) { Text(action.displayName) }
    }
}

@Composable
private fun ActionPickerDialog(
    gesture: GestureType,
    current: MediaAction,
    onDismiss: () -> Unit,
    onSelect: (MediaAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(gesture.displayName) },
        text = {
            LazyColumn {
                items(MediaAction.entries) { action ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onSelect(action) }, modifier = Modifier.fillMaxWidth()) {
                            Text(action.displayName, fontWeight = if (action == current) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private enum class ProfileDialog { Create, Copy, Rename, Delete }

@Composable
private fun ProfileDialogContent(
    dialog: ProfileDialog,
    activeProfile: ControlProfile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(dialog, activeProfile.id) {
        mutableStateOf(
            when (dialog) {
                ProfileDialog.Copy -> "${activeProfile.name} — kopia"
                ProfileDialog.Rename -> activeProfile.name
                ProfileDialog.Create -> "Mój profil"
                ProfileDialog.Delete -> activeProfile.name
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (dialog) {
                    ProfileDialog.Create -> "Nowy profil"
                    ProfileDialog.Copy -> "Kopiuj profil"
                    ProfileDialog.Rename -> "Zmień nazwę"
                    ProfileDialog.Delete -> "Usunąć profil?"
                },
            )
        },
        text = {
            if (dialog == ProfileDialog.Delete) {
                Text("Profil „${activeProfile.name}” i jego mapowania zostaną usunięte.")
            } else {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nazwa") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = dialog == ProfileDialog.Delete || name.trim().length >= 2) {
                Text(if (dialog == ProfileDialog.Delete) "Usuń" else "Zapisz")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun AdvancedThresholdsDialog(
    initial: GestureThresholds,
    onDismiss: () -> Unit,
    onSave: (GestureThresholds) -> Unit,
) {
    var tilt by remember { mutableFloatStateOf(initial.tiltDegrees) }
    var rotation by remember { mutableFloatStateOf(initial.rotationDps) }
    var shake by remember { mutableFloatStateOf(initial.shakeDps) }
    var impact by remember { mutableFloatStateOf(initial.impactG) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaawansowana czułość") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThresholdSlider("Przechylenie", tilt, 12f..60f, "%.0f°") { tilt = it }
                ThresholdSlider("Obrót", rotation, 80f..600f, "%.0f°/s") { rotation = it }
                ThresholdSlider("Potrząśnięcie", shake, 100f..700f, "%.0f°/s") { shake = it }
                ThresholdSlider("Impuls podrzucenia", impact, 1.2f..5f, "%.1f g") { impact = it }
                Text(
                    "Niższy próg oznacza większą czułość. Akcja jest wykonywana dopiero po pełnym cyklu: spoczynek, ruch i ponowny spoczynek.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(initial.copy(tiltDegrees = tilt, rotationDps = rotation, shakeDps = shake, impactG = impact))
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit,
) {
    Text("$label · ${format.format(value)}", style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
