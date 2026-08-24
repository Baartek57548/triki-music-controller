package pl.trikimusic.controller.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.ControlProfile
import pl.trikimusic.controller.domain.model.CURRENT_GESTURE_LEARNING_VERSION
import pl.trikimusic.controller.domain.model.GestureMapping
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.LearnedGestureSample
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.SensitivityLevel
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.domain.model.defaultProfiles
import pl.trikimusic.controller.domain.repository.SettingsRepository

private val Context.trikiDataStore: DataStore<Preferences> by preferencesDataStore(name = "triki_settings")

class DataStoreSettingsRepository(
    context: Context,
    private val logger: AppLogger,
) : SettingsRepository {
    private val dataStore = context.trikiDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                logger.log(LogCategory.PERMISSION, "Nie udało się odczytać ustawień; używam wartości domyślnych.", error)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences -> decode(preferences[SETTINGS_KEY]) }

    override suspend fun completeOnboarding() = update { copy(onboardingComplete = true) }

    override suspend fun completeGestureWizard() = update {
        copy(
            gestureWizardCompleted = true,
            gestureLearningVersion = CURRENT_GESTURE_LEARNING_VERSION,
        )
    }

    override suspend fun rememberDevice(address: String, name: String) {
        require(address.isNotBlank())
        require(name.isNotBlank())
        update { copy(knownDeviceAddress = address, knownDeviceName = name) }
    }

    override suspend fun forgetDevice() = update { copy(knownDeviceAddress = null, knownDeviceName = null) }

    override suspend fun setGestureMapping(profileId: String, gesture: GestureType, action: MediaAction) {
        update {
            val changed = profiles.map { profile ->
                if (profile.id != profileId) {
                    profile
                } else {
                    profile.copy(
                        mappings = profile.mappings
                            .filterNot { it.gesture == gesture } + GestureMapping(gesture, action),
                    )
                }
            }
            copy(profiles = changed)
        }
    }

    override suspend fun saveGestureTrainingSample(gesture: GestureType, features: GestureFeatureVector) {
        require(features.isValid) { "Nie można zapisać nieprawidłowej próbki modelu gestów." }
        val sample = LearnedGestureSample(
            gesture = gesture,
            features = features,
            capturedAtMillis = System.currentTimeMillis(),
        )
        update { copy(personalizedGestureModel = personalizedGestureModel.withSample(sample)) }
    }

    override suspend fun clearGestureTraining(gesture: GestureType) =
        update { copy(personalizedGestureModel = personalizedGestureModel.withoutGesture(gesture)) }

    override suspend fun createProfile(name: String): Result<ControlProfile> = runCatching {
        val validName = validateProfileName(name)
        val profile = ControlProfile(
            id = UUID.randomUUID().toString(),
            name = validName,
            mappings = defaultProfiles().first().mappings,
        )
        update { copy(profiles = profiles + profile, activeProfileId = profile.id) }
        profile
    }

    override suspend fun copyProfile(profileId: String, newName: String): Result<ControlProfile> = runCatching {
        val validName = validateProfileName(newName)
        var copy: ControlProfile? = null
        update {
            val source = profiles.firstOrNull { it.id == profileId }
                ?: error("Nie znaleziono profilu do skopiowania.")
            copy = source.copy(id = UUID.randomUUID().toString(), name = validName, builtIn = false)
            this.copy(profiles = profiles + requireNotNull(copy), activeProfileId = requireNotNull(copy).id)
        }
        requireNotNull(copy)
    }

    override suspend fun renameProfile(profileId: String, newName: String): Result<Unit> = runCatching {
        val validName = validateProfileName(newName)
        update {
            val target = profiles.firstOrNull { it.id == profileId } ?: error("Profil nie istnieje.")
            require(!target.builtIn) { "Wbudowanego profilu nie można zmienić." }
            copy(profiles = profiles.map { if (it.id == profileId) it.copy(name = validName) else it })
        }
    }

    override suspend fun deleteProfile(profileId: String): Result<Unit> = runCatching {
        update {
            val target = profiles.firstOrNull { it.id == profileId } ?: error("Profil nie istnieje.")
            require(!target.builtIn) { "Wbudowanego profilu nie można usunąć." }
            val remaining = profiles.filterNot { it.id == profileId }
            require(remaining.isNotEmpty()) { "Musi pozostać co najmniej jeden profil." }
            copy(
                profiles = remaining,
                activeProfileId = if (activeProfileId == profileId) remaining.first().id else activeProfileId,
            )
        }
    }

    override suspend fun setActiveProfile(profileId: String) {
        update {
            require(profiles.any { it.id == profileId }) { "Profil nie istnieje." }
            copy(activeProfileId = profileId)
        }
    }

    override suspend fun setSensitivity(level: SensitivityLevel) = update { copy(sensitivity = level) }

    override suspend fun setAdvancedThresholds(thresholds: GestureThresholds) =
        update { copy(advancedThresholds = thresholds, sensitivity = SensitivityLevel.ADVANCED) }

    override suspend fun saveCalibration(profile: CalibrationProfile) {
        require(profile.isValid) { "Profil kalibracji zawiera zbyt mało próbek." }
        update { copy(calibration = profile) }
    }

    override suspend fun setDeveloperMode(enabled: Boolean) = update { copy(developerMode = enabled) }

    override suspend fun setBackgroundEnabled(enabled: Boolean) = update { copy(backgroundEnabled = enabled) }

    override suspend fun setTheme(theme: ThemePreference) = update { copy(theme = theme) }

    private suspend fun update(transform: AppSettings.() -> AppSettings) {
        dataStore.edit { preferences ->
            val current = decode(preferences[SETTINGS_KEY])
            preferences[SETTINGS_KEY] = json.encodeToString(AppSettings.serializer(), current.transform())
        }
    }

    private fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), raw).normalized()
        } catch (error: SerializationException) {
            logger.log(LogCategory.PERMISSION, "Uszkodzone ustawienia zostały zastąpione domyślnymi.", error)
            AppSettings()
        } catch (error: IllegalArgumentException) {
            logger.log(LogCategory.PERMISSION, "Nieprawidłowe ustawienia zostały zastąpione domyślnymi.", error)
            AppSettings()
        }
    }

    private fun AppSettings.normalized(): AppSettings {
        val safeProfiles = profiles.ifEmpty { defaultProfiles() }
        val safeActive = activeProfileId.takeIf { id -> safeProfiles.any { it.id == id } } ?: safeProfiles.first().id
        return copy(
            profiles = safeProfiles,
            activeProfileId = safeActive,
            personalizedGestureModel = personalizedGestureModel.normalized(),
        )
    }

    private fun validateProfileName(name: String): String {
        val normalized = name.trim()
        require(normalized.length in 2..40) { "Nazwa profilu musi mieć od 2 do 40 znaków." }
        return normalized
    }

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("app_settings_v1")
    }
}
