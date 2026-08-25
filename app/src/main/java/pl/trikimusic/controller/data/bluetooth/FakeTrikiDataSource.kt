package pl.trikimusic.controller.data.bluetooth

import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class FakeTrikiDataSource {
    fun generateButtonClicks(clickCount: Int, startNanos: Long = System.nanoTime()): List<TrikiSensorData> {
        require(clickCount in 1..3) { "Generator obsługuje od jednego do trzech kliknięć." }
        val statuses = buildList {
            repeat(ARMING_REST_SAMPLES) { add(0) }
            repeat(clickCount) { clickIndex ->
                repeat(BUTTON_PRESS_SAMPLES) { add(1) }
                val releaseSamples = if (clickIndex == clickCount - 1) {
                    BUTTON_FINAL_RELEASE_SAMPLES
                } else {
                    BUTTON_BETWEEN_CLICKS_SAMPLES
                }
                repeat(releaseSamples) { add(0) }
            }
        }
        return statuses.mapIndexed { index, status ->
            TrikiSensorData(
                frameIndex = index.toLong(),
                timestampNanos = startNanos + index * SAMPLE_PERIOD_NANOS,
                gyroscopeDps = Vector3(0f, 0f, 0f),
                accelerometerG = Vector3(0f, 0f, -1f),
                rawGyroscope = RawVector3(0, 0, 0),
                rawAccelerometer = RawVector3(0, 0, (-2_048).toShort()),
                status = status,
            )
        }
    }

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 19_230_769L
        const val ARMING_REST_SAMPLES = 35
        const val BUTTON_PRESS_SAMPLES = 5
        const val BUTTON_BETWEEN_CLICKS_SAMPLES = 8
        const val BUTTON_FINAL_RELEASE_SAMPLES = 28
    }
}
