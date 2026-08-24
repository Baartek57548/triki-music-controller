package pl.trikimusic.controller.data.bluetooth

import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class FakeTrikiDataSource {
    fun generate(gesture: GestureType, startNanos: Long = System.nanoTime()): List<TrikiSensorData> {
        check(BuildConfig.DEBUG) { "FakeTrikiDataSource jest dostępne tylko w buildzie debug." }
        val sequence = when (gesture) {
            GestureType.TILT_LEFT -> ramp { index -> Vector3(0f, -0.58f * index / 35f, 1f - 0.45f * index / 35f) }
            GestureType.TILT_RIGHT -> ramp { index -> Vector3(0f, 0.58f * index / 35f, 1f - 0.45f * index / 35f) }
            GestureType.ROTATE_LEFT -> pulse(gyroscope = Vector3(0f, 0f, -420f))
            GestureType.ROTATE_RIGHT -> pulse(gyroscope = Vector3(0f, 0f, 420f))
            GestureType.THROW_UP -> listOf(
                Motion(20, accelerometer = Vector3(0f, 0f, 1f)),
                Motion(10, accelerometer = Vector3(0f, 0f, 0.08f)),
                Motion(8, accelerometer = Vector3(0f, 0f, 2.8f)),
                Motion(20, accelerometer = Vector3(0f, 0f, 1f)),
            )
            GestureType.SHAKE -> shakePulses(1)
            GestureType.DOUBLE_SHAKE -> shakePulses(2)
            GestureType.FLIP -> listOf(
                Motion(15, accelerometer = Vector3(0f, 0f, 1f)),
                Motion(20, gyroscope = Vector3(250f, 0f, 0f), accelerometer = Vector3(0f, 0.2f, -0.98f)),
                Motion(20, accelerometer = Vector3(0f, 0f, -1f)),
            )
        }
        var frame = 0L
        return sequence.flatMap { motion ->
            List(motion.samples) {
                val timestamp = startNanos + frame * SAMPLE_PERIOD_NANOS
                TrikiSensorData(
                    frameIndex = frame++,
                    timestampNanos = timestamp,
                    gyroscopeDps = motion.gyroscope,
                    accelerometerG = motion.accelerometer,
                    rawGyroscope = RawVector3(0, 0, 0),
                    rawAccelerometer = RawVector3(0, 0, 0),
                    status = 0,
                )
            }
        }
    }

    private fun ramp(accelerometer: (Int) -> Vector3): List<Motion> =
        (0 until 36).map { Motion(1, accelerometer = accelerometer(it)) } + Motion(20)

    private fun pulse(gyroscope: Vector3): List<Motion> = listOf(
        Motion(15),
        Motion(6, gyroscope = gyroscope, accelerometer = Vector3(0.2f, 0f, 1.15f)),
        Motion(20),
    )

    private fun shakePulses(count: Int): List<Motion> = buildList {
        add(Motion(15))
        repeat(count) {
            add(Motion(5, Vector3(360f, 300f, 120f), Vector3(0.5f, 0f, 1.2f)))
            add(Motion(5, Vector3(-360f, -300f, -120f), Vector3(-0.5f, 0f, 0.8f)))
            add(Motion(18))
        }
        add(Motion(60))
    }

    private data class Motion(
        val samples: Int,
        val gyroscope: Vector3 = Vector3(0f, 0f, 0f),
        val accelerometer: Vector3 = Vector3(0f, 0f, 1f),
    )

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 10_000_000L
    }
}
