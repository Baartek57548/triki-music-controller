package pl.trikimusic.controller.data.repository

import kotlin.math.abs
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.defaultProfiles
import pl.trikimusic.controller.domain.model.withCurrentOrientationConvention

internal fun AppSettings.normalized(): AppSettings {
    // Stored mappings may intentionally match old defaults; their values cannot identify a schema version.
    val safeProfiles = profiles.ifEmpty { defaultProfiles() }
    val safeActive = activeProfileId.takeIf { id -> safeProfiles.any { it.id == id } } ?: safeProfiles.first().id
    val safeAddress = knownDeviceAddress?.takeIf { it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) }
    return copy(
        profiles = safeProfiles,
        activeProfileId = safeActive,
        knownDeviceAddress = safeAddress,
        knownDeviceName = safeAddress?.let { knownDeviceName?.takeIf(String::isNotBlank) ?: "Triki" },
        rotationAngleDegrees = rotationAngleDegrees.takeIf { it in 90..360 } ?: 200,
        volumeSensitivityPercent = volumeSensitivityPercent.takeIf { it in 50..200 } ?: 100,
        brightnessSensitivityPercent = brightnessSensitivityPercent.takeIf { it in 50..200 } ?: 100,
        calibration = calibration.sanitized(),
    )
}

private fun CalibrationProfile.sanitized(): CalibrationProfile {
    val safe = isValid && calibratedAtMillis?.let { it >= 0L } == true &&
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
