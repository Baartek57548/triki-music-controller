package pl.trikimusic.controller.core.sensor

import kotlin.math.atan2
import kotlin.math.sqrt
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.TrikiSensorData

object CalibrationCalculator {
    fun calculate(samples: List<TrikiSensorData>, calibratedAtMillis: Long): CalibrationProfile {
        require(samples.size >= MIN_SAMPLES) { "Kalibracja wymaga co najmniej $MIN_SAMPLES próbek." }
        val averageAx = samples.map { it.accelerometerG.x.toDouble() }.average().toFloat()
        val averageAy = samples.map { it.accelerometerG.y.toDouble() }.average().toFloat()
        val averageAz = samples.map { it.accelerometerG.z.toDouble() }.average().toFloat()
        val averageGx = samples.map { it.gyroscopeDps.x.toDouble() }.average().toFloat()
        val averageGy = samples.map { it.gyroscopeDps.y.toDouble() }.average().toFloat()
        val averageGz = samples.map { it.gyroscopeDps.z.toDouble() }.average().toFloat()

        val gravityMagnitude = sqrt(averageAx * averageAx + averageAy * averageAy + averageAz * averageAz)
        require(gravityMagnitude in MIN_GRAVITY..MAX_GRAVITY) {
            "Kontroler poruszał się podczas kalibracji (|a|=${"%.2f".format(gravityMagnitude)} g)."
        }
        val gravityX = averageAx / gravityMagnitude
        val gravityY = averageAy / gravityMagnitude
        val gravityZ = averageAz / gravityMagnitude
        val neutralPitch = Math.toDegrees(
            atan2(-gravityX.toDouble(), sqrt((gravityY * gravityY + gravityZ * gravityZ).toDouble())),
        ).toFloat()
        val neutralRoll = Math.toDegrees(atan2(gravityY.toDouble(), gravityZ.toDouble())).toFloat()
        val accelerometerNoise = rms(samples.map { it.accelerometerG.magnitude - gravityMagnitude })
        val gyroscopeNoise = rms(samples.map { it.gyroscopeDps.magnitude })
        require(gyroscopeNoise < MAX_GYROSCOPE_NOISE_DPS) {
            "Kontroler nie był nieruchomy (szum żyroskopu ${"%.1f".format(gyroscopeNoise)}°/s)."
        }
        return CalibrationProfile(
            accelerometerBiasX = averageAx - gravityX,
            accelerometerBiasY = averageAy - gravityY,
            accelerometerBiasZ = averageAz - gravityZ,
            gyroscopeBiasX = averageGx,
            gyroscopeBiasY = averageGy,
            gyroscopeBiasZ = averageGz,
            neutralPitch = neutralPitch,
            neutralRoll = neutralRoll,
            accelerometerNoise = accelerometerNoise,
            gyroscopeNoise = gyroscopeNoise,
            sampleCount = samples.size,
            calibratedAtMillis = calibratedAtMillis,
        )
    }

    private fun rms(values: List<Float>): Float =
        sqrt(values.sumOf { it.toDouble() * it.toDouble() } / values.size).toFloat()

    private const val MIN_SAMPLES = 50
    private const val MIN_GRAVITY = 0.75f
    private const val MAX_GRAVITY = 1.25f
    private const val MAX_GYROSCOPE_NOISE_DPS = 25f
}
