package pl.trikimusic.controller.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.ControlProfile
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.SensitivityLevel
import pl.trikimusic.controller.domain.model.ThemePreference

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun completeOnboarding()
    suspend fun completeGestureWizard()
    suspend fun rememberDevice(address: String, name: String)
    suspend fun forgetDevice()
    suspend fun setGestureMapping(profileId: String, gesture: GestureType, action: MediaAction)
    suspend fun setButtonMapping(profileId: String, click: ButtonClickType, action: MediaAction)
    suspend fun saveGestureTrainingSample(gesture: GestureType, features: GestureFeatureVector)
    suspend fun clearGestureTraining(gesture: GestureType)
    suspend fun createProfile(name: String): Result<ControlProfile>
    suspend fun copyProfile(profileId: String, newName: String): Result<ControlProfile>
    suspend fun renameProfile(profileId: String, newName: String): Result<Unit>
    suspend fun deleteProfile(profileId: String): Result<Unit>
    suspend fun setActiveProfile(profileId: String)
    suspend fun setSensitivity(level: SensitivityLevel)
    suspend fun setAdvancedThresholds(thresholds: GestureThresholds)
    suspend fun saveCalibration(profile: CalibrationProfile)
    suspend fun setDeveloperMode(enabled: Boolean)
    suspend fun setBackgroundEnabled(enabled: Boolean)
    suspend fun setTheme(theme: ThemePreference)
}
