package pl.trikimusic.controller.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.ThemePreference

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun completeOnboarding()
    suspend fun rememberDevice(address: String, name: String)
    suspend fun forgetDevice()
    suspend fun setButtonMapping(profileId: String, click: ButtonClickType, action: MediaAction)
    suspend fun saveCalibration(profile: CalibrationProfile)
    suspend fun setDeveloperMode(enabled: Boolean)
    suspend fun setBackgroundEnabled(enabled: Boolean)
    suspend fun setConnectOnlyWhenNeeded(enabled: Boolean)
    suspend fun setRotationAngleDegrees(degrees: Int)
    suspend fun setEnableSoundFeedback(enabled: Boolean)
    suspend fun setTheme(theme: ThemePreference)
}
