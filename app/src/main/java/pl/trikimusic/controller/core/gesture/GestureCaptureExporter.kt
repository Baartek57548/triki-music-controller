package pl.trikimusic.controller.core.gesture

import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.Vector3

/** Produces a self-contained, locale-independent diagnostic capture for one labeled movement. */
object GestureCaptureExporter {
    fun toCsv(
        samples: List<FilteredSensorData>,
        expectedGesture: GestureType,
        detectedGesture: GestureType?,
        confidence: Float?,
        featureQuality: Float,
        thresholds: GestureThresholds,
        calibration: CalibrationProfile,
    ): String {
        require(samples.isNotEmpty()) { "Brak nagrania gestu do eksportu." }
        require(featureQuality.isFinite() && featureQuality in 0f..1f) { "Nieprawidłowa jakość cech." }
        require(confidence == null || confidence.isFinite() && confidence in 0f..1f) { "Nieprawidłowa pewność." }

        val ordered = samples.sortedBy { it.source.timestampNanos }
        val firstTimestamp = ordered.first().source.timestampNanos
        val durationNanos = ordered.last().source.timestampNanos - firstTimestamp
        return buildString(ordered.size * APPROXIMATE_ROW_CAPACITY) {
            appendLine("# triki_gesture_capture_schema=1")
            appendLine("# expected_gesture=${expectedGesture.name}")
            appendLine("# detected_gesture=${detectedGesture?.name ?: "NONE"}")
            appendLine("# confidence=${confidence?.let(Float::toString) ?: "NONE"}")
            appendLine("# feature_quality=${featureQuality}")
            appendLine("# sample_count=${ordered.size}")
            appendLine("# duration_ms=${durationNanos / NANOS_PER_MILLISECOND_F}")
            appendLine(
                "# thresholds=" +
                    "tilt:${thresholds.tiltDegrees};tilt_release:${thresholds.tiltReleaseDegrees};" +
                    "rotation_dps:${thresholds.rotationDps};shake_dps:${thresholds.shakeDps};" +
                    "impact_g:${thresholds.impactG};free_fall_g:${thresholds.freeFallG};" +
                    "filter_alpha:${thresholds.filterAlpha};cooldown_ms:${thresholds.cooldownMillis}",
            )
            appendLine(
                "# calibration=" +
                    "sample_count:${calibration.sampleCount};" +
                    "accel_bias:${calibration.accelerometerBiasX}|${calibration.accelerometerBiasY}|${calibration.accelerometerBiasZ};" +
                    "gyro_bias:${calibration.gyroscopeBiasX}|${calibration.gyroscopeBiasY}|${calibration.gyroscopeBiasZ};" +
                    "neutral_pitch:${calibration.neutralPitch};neutral_roll:${calibration.neutralRoll};" +
                    "accel_noise:${calibration.accelerometerNoise};gyro_noise:${calibration.gyroscopeNoise};" +
                    "calibrated_at_ms:${calibration.calibratedAtMillis ?: "NONE"}",
            )
            appendLine(CSV_HEADER)
            var previousTimestamp = firstTimestamp
            ordered.forEachIndexed { index, sample ->
                val source = sample.source
                val elapsedNanos = source.timestampNanos - firstTimestamp
                val dtNanos = if (index == 0) 0L else source.timestampNanos - previousTimestamp
                previousTimestamp = source.timestampNanos
                append(index).append(',')
                append(source.frameIndex).append(',')
                append(elapsedNanos).append(',')
                append(dtNanos).append(',')
                append(source.status).append(',')
                append(source.rawGyroscope.x).append(',')
                append(source.rawGyroscope.y).append(',')
                append(source.rawGyroscope.z).append(',')
                append(source.rawAccelerometer.x).append(',')
                append(source.rawAccelerometer.y).append(',')
                append(source.rawAccelerometer.z).append(',')
                appendVector(source.gyroscopeDps)
                appendVector(source.accelerometerG)
                appendVector(sample.gyroscopeDps)
                appendVector(sample.accelerometerG)
                append(sample.orientation.pitch).append(',')
                append(sample.orientation.roll).append(',')
                append(sample.orientation.yaw).appendLine()
            }
        }
    }

    private fun StringBuilder.appendVector(vector: Vector3) {
        append(vector.x).append(',')
        append(vector.y).append(',')
        append(vector.z).append(',')
    }

    private const val APPROXIMATE_ROW_CAPACITY = 240
    private const val NANOS_PER_MILLISECOND_F = 1_000_000f
    private const val CSV_HEADER =
        "sample_index,frame_index,elapsed_ns,dt_ns,status," +
            "raw_gyro_x,raw_gyro_y,raw_gyro_z,raw_accel_x,raw_accel_y,raw_accel_z," +
            "decoded_gyro_x_dps,decoded_gyro_y_dps,decoded_gyro_z_dps," +
            "decoded_accel_x_g,decoded_accel_y_g,decoded_accel_z_g," +
            "filtered_gyro_x_dps,filtered_gyro_y_dps,filtered_gyro_z_dps," +
            "filtered_accel_x_g,filtered_accel_y_g,filtered_accel_z_g,pitch_deg,roll_deg,yaw_deg"
}
