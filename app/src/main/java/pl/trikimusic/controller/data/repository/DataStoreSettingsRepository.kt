package pl.trikimusic.controller.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.ButtonMapping
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.domain.model.defaultProfiles
import pl.trikimusic.controller.domain.model.withCurrentOrientationConvention
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

    override suspend fun rememberDevice(address: String, name: String) {
        require(address.isNotBlank())
        require(name.isNotBlank())
        update { copy(knownDeviceAddress = address, knownDeviceName = name) }
    }

    override suspend fun forgetDevice() = update { copy(knownDeviceAddress = null, knownDeviceName = null) }

    override suspend fun setButtonMapping(profileId: String, click: ButtonClickType, action: MediaAction) {
        update {
            val changed = profiles.map { profile ->
                if (profile.id != profileId) {
                    profile
                } else {
                    profile.copy(
                        buttonMappings = profile.buttonMappings
                            .filterNot { it.click == click } + ButtonMapping(click, action),
                    )
                }
            }
            copy(profiles = changed)
        }
    }

    override suspend fun saveCalibration(profile: CalibrationProfile) {
        require(profile.isValid) { "Profil kalibracji zawiera zbyt mało próbek." }
        update { copy(calibration = profile) }
    }

    override suspend fun setDeveloperMode(enabled: Boolean) = update { copy(developerMode = enabled) }

    override suspend fun setBackgroundEnabled(enabled: Boolean) = update { copy(backgroundEnabled = enabled) }

    override suspend fun setConnectOnlyWhenNeeded(enabled: Boolean) = update {
        copy(connectOnlyWhenNeeded = enabled)
    }

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
            calibration = calibration.sanitized(),
        )
    }

    private fun CalibrationProfile.sanitized(): CalibrationProfile {
        val safe = isValid &&
            calibratedAtMillis?.let { it >= 0L } == true &&
            listOf(accelerometerBiasX, accelerometerBiasY, accelerometerBiasZ).all { it.isFinite() && abs(it) <= 4f } &&
            listOf(gyroscopeBiasX, gyroscopeBiasY, gyroscopeBiasZ).all { it.isFinite() && abs(it) <= 2_000f } &&
            accelerometerNoise.isFinite() && accelerometerNoise in 0f..4f &&
            gyroscopeNoise.isFinite() && gyroscopeNoise in 0f..2_000f &&
            neutralPitch.isFinite() && neutralRoll.isFinite()
        if (!safe) return CalibrationProfile()

        val migrated = withCurrentOrientationConvention()
        return migrated.copy(
            neutralPitch = normalizeDegrees(migrated.neutralPitch),
            neutralRoll = normalizeDegrees(migrated.neutralRoll),
        )
    }

    private fun normalizeDegrees(value: Float): Float {
        val normalized = Math.IEEEremainder(value.toDouble(), 360.0).toFloat()
        return if (normalized == -180f) 180f else normalized
    }

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("app_settings_v1")
    }
}
