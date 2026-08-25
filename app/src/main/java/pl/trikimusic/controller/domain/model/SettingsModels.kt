package pl.trikimusic.controller.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CalibrationProfile(
    val accelerometerBiasX: Float = 0f,
    val accelerometerBiasY: Float = 0f,
    val accelerometerBiasZ: Float = 0f,
    val gyroscopeBiasX: Float = 0f,
    val gyroscopeBiasY: Float = 0f,
    val gyroscopeBiasZ: Float = 0f,
    val neutralPitch: Float = 0f,
    val neutralRoll: Float = 0f,
    val accelerometerNoise: Float = 0f,
    val gyroscopeNoise: Float = 0f,
    val sampleCount: Int = 0,
    val calibratedAtMillis: Long? = null,
) {
    val isValid: Boolean
        get() = sampleCount >= 50 && calibratedAtMillis != null
}

@Serializable
enum class ThemePreference(val displayName: String) {
    SYSTEM("Systemowy"),
    LIGHT("Jasny"),
    DARK("Ciemny"),
}

@Serializable
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val knownDeviceAddress: String? = null,
    val knownDeviceName: String? = null,
    val activeProfileId: String = DEFAULT_PROFILE_ID,
    val profiles: List<ControlProfile> = defaultProfiles(),
    val calibration: CalibrationProfile = CalibrationProfile(),
    val developerMode: Boolean = false,
    val backgroundEnabled: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
) {
    val activeProfile: ControlProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
}

const val DEFAULT_PROFILE_ID = "music"

fun defaultProfiles(): List<ControlProfile> = listOf(
    ControlProfile(
        id = DEFAULT_PROFILE_ID,
        name = "Music",
        builtIn = true,
    ),
)
