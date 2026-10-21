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
    val orientationConventionVersion: Int = 0,
) {
    val isValid: Boolean
        get() = sampleCount >= 50 && calibratedAtMillis != null
}

const val CURRENT_ORIENTATION_CONVENTION_VERSION = 1

/**
 * Converts calibration profiles saved before the hardware's face-up −Z convention was
 * accounted for. A legacy face-up roll close to ±180° becomes the equivalent angle close
 * to 0°, while an invalid legacy face-down calibration remains non-neutral for safety.
 */
fun CalibrationProfile.withCurrentOrientationConvention(): CalibrationProfile {
    if (!isValid || orientationConventionVersion >= CURRENT_ORIENTATION_CONVENTION_VERSION) return this
    val migratedRoll = when {
        neutralRoll > 90f -> 180f - neutralRoll
        neutralRoll < -90f -> -180f - neutralRoll
        else -> neutralRoll
    }
    return copy(
        neutralRoll = migratedRoll,
        orientationConventionVersion = CURRENT_ORIENTATION_CONVENTION_VERSION,
    )
}

@Serializable
enum class ThemePreference(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Jasny"),
    DARK("Ciemny"),
}

@Serializable
enum class MultiDeviceArbitrationMode(val displayName: String) {
    MEDIA_PRIORITY("Priorytet aktywnej muzyki (Zalecany)"),
    ALWAYS_CONNECT("Zawsze łącz (Agresywny)"),
    ONLY_WHEN_PLAYING("Tylko podczas odtwarzania"),
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
    val connectionPowerMode: ConnectionPowerMode = ConnectionPowerMode.NORMAL,
    val volumeSensitivityPercent: Int = 100,
    val brightnessSensitivityPercent: Int = 100,
    val volumeGestureEnabled: Boolean = true,
    val brightnessGestureEnabled: Boolean = true,
    val multiDeviceArbitration: MultiDeviceArbitrationMode = MultiDeviceArbitrationMode.MEDIA_PRIORITY,
    val rotationAngleDegrees: Int = 200,
    val enableSoundFeedback: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
) {
    val connectOnlyWhenNeeded: Boolean get() = connectionPowerMode != ConnectionPowerMode.ALWAYS_ON
    val activeProfile: ControlProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
}

@Serializable
enum class ConnectionPowerMode(val displayName: String, val idleMinutes: Int?) {
    NORMAL("Normal — 30 minut", 30),
    ECO_2("Eco — 2 minuty", 2),
    ECO_5("Eco — 5 minut", 5),
    ECO_10("Eco — 10 minut", 10),
    ALWAYS_ON("Stałe połączenie", null);

    val idleTimeoutNanos: Long get() = (idleMinutes ?: 30) * 60_000_000_000L
}

const val DEFAULT_PROFILE_ID = "music"

fun defaultProfiles(): List<ControlProfile> = listOf(
    ControlProfile(
        id = DEFAULT_PROFILE_ID,
        name = "Music",
        builtIn = true,
    ),
)
