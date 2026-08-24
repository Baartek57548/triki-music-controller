package pl.trikimusic.controller.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SensitivityLevel(val displayName: String) {
    LOW("Niska"),
    NORMAL("Normalna"),
    HIGH("Wysoka"),
    VERY_HIGH("Bardzo wysoka"),
    ADVANCED("Advanced"),
}

@Serializable
data class GestureThresholds(
    val tiltDegrees: Float = 28f,
    val tiltReleaseDegrees: Float = 12f,
    val rotationDps: Float = 220f,
    val shakeDps: Float = 285f,
    val impactG: Float = 2.4f,
    val freeFallG: Float = 0.38f,
    val filterAlpha: Float = 0.28f,
    val cooldownMillis: Long = 650L,
) {
    init {
        require(tiltDegrees in 10f..80f)
        require(tiltReleaseDegrees >= 2f && tiltReleaseDegrees < tiltDegrees)
        require(rotationDps in 50f..2_000f)
        require(shakeDps in 50f..2_000f)
        require(impactG in 1.1f..16f)
        require(freeFallG in 0.05f..0.9f)
        require(filterAlpha in 0.02f..1f)
        require(cooldownMillis in 100L..5_000L)
    }
}

fun SensitivityLevel.thresholds(custom: GestureThresholds): GestureThresholds = when (this) {
    SensitivityLevel.LOW -> GestureThresholds(
        tiltDegrees = 38f,
        rotationDps = 310f,
        shakeDps = 380f,
        impactG = 3.1f,
        filterAlpha = 0.2f,
        cooldownMillis = 850L,
    )
    SensitivityLevel.NORMAL -> GestureThresholds()
    SensitivityLevel.HIGH -> GestureThresholds(
        tiltDegrees = 22f,
        rotationDps = 175f,
        shakeDps = 225f,
        impactG = 2.0f,
        freeFallG = 0.45f,
        filterAlpha = 0.36f,
        cooldownMillis = 520L,
    )
    SensitivityLevel.VERY_HIGH -> GestureThresholds(
        tiltDegrees = 17f,
        tiltReleaseDegrees = 8f,
        rotationDps = 130f,
        shakeDps = 175f,
        impactG = 1.7f,
        freeFallG = 0.5f,
        filterAlpha = 0.45f,
        cooldownMillis = 420L,
    )
    SensitivityLevel.ADVANCED -> custom
}

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
    val gestureWizardCompleted: Boolean = false,
    val knownDeviceAddress: String? = null,
    val knownDeviceName: String? = null,
    val activeProfileId: String = DEFAULT_PROFILE_ID,
    val profiles: List<ControlProfile> = defaultProfiles(),
    val sensitivity: SensitivityLevel = SensitivityLevel.NORMAL,
    val advancedThresholds: GestureThresholds = GestureThresholds(),
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
        mappings = listOf(
            GestureMapping(GestureType.TILT_LEFT, MediaAction.PREVIOUS),
            GestureMapping(GestureType.TILT_RIGHT, MediaAction.NEXT),
            GestureMapping(GestureType.THROW_UP, MediaAction.PLAY_PAUSE),
            GestureMapping(GestureType.ROTATE_LEFT, MediaAction.VOLUME_DOWN),
            GestureMapping(GestureType.ROTATE_RIGHT, MediaAction.VOLUME_UP),
            GestureMapping(GestureType.SHAKE, MediaAction.MUTE),
            GestureMapping(GestureType.DOUBLE_SHAKE, MediaAction.PLAY_PAUSE),
            GestureMapping(GestureType.FLIP, MediaAction.STOP),
        ),
    ),
    ControlProfile(
        id = "spotify",
        name = "Spotify",
        builtIn = true,
        mappings = listOf(
            GestureMapping(GestureType.TILT_LEFT, MediaAction.PREVIOUS),
            GestureMapping(GestureType.TILT_RIGHT, MediaAction.NEXT),
            GestureMapping(GestureType.THROW_UP, MediaAction.PLAY_PAUSE),
            GestureMapping(GestureType.ROTATE_LEFT, MediaAction.VOLUME_DOWN),
            GestureMapping(GestureType.ROTATE_RIGHT, MediaAction.VOLUME_UP),
            GestureMapping(GestureType.SHAKE, MediaAction.PLAY_PAUSE),
            GestureMapping(GestureType.DOUBLE_SHAKE, MediaAction.NEXT),
            GestureMapping(GestureType.FLIP, MediaAction.STOP),
        ),
    ),
)
