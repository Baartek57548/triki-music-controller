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
            GestureType.LEAN -> ramp(Vector3(85f, 0f, 0f)) { index ->
                Vector3(0f, -0.58f * index / 15f, 1f - 0.45f * index / 15f)
            }
            GestureType.SLIDE -> listOf(
                Motion(18),
                Motion(6, gyroscope = Vector3(12f, 5f, 0f), accelerometer = Vector3(0.28f, 0f, 1f)),
                Motion(6, gyroscope = Vector3(-12f, -5f, 0f), accelerometer = Vector3(-0.28f, 0f, 1f)),
                Motion(24),
            )
            GestureType.ROTATE_LEFT -> pulse(gyroscope = Vector3(0f, 0f, -420f))
            GestureType.ROTATE_RIGHT -> pulse(gyroscope = Vector3(0f, 0f, 420f))
            GestureType.TAP -> listOf(
                Motion(18),
                Motion(1, accelerometer = Vector3(0f, 0f, 1.7f)),
                Motion(24),
            )
            GestureType.SHAKE -> shakePulses(1)
            GestureType.DOUBLE_SHAKE -> shakePulses(2)
            GestureType.FLIP -> listOf(
                Motion(18),
                Motion(6, gyroscope = Vector3(250f, 0f, 0f), accelerometer = Vector3(0f, 0.2f, -0.98f)),
                Motion(100, accelerometer = Vector3(0f, 0f, -1f)),
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

    private fun ramp(gyroscope: Vector3, accelerometer: (Int) -> Vector3): List<Motion> =
        listOf(Motion(18)) +
            (0 until 16).map { Motion(1, gyroscope = gyroscope, accelerometer = accelerometer(it)) } +
            Motion(70)

    private fun pulse(gyroscope: Vector3): List<Motion> = listOf(
        Motion(18),
        Motion(8, gyroscope = gyroscope, accelerometer = Vector3(0.12f, 0f, 1.08f)),
        Motion(24),
    )

    private fun shakePulses(count: Int): List<Motion> = buildList {
        add(Motion(18))
        repeat(count) {
            add(Motion(8, Vector3(420f, 350f, 140f), Vector3(0.2f, 0f, 1.25f)))
            add(Motion(8, Vector3(-420f, -350f, -140f), Vector3(-0.2f, 0f, 0.75f)))
            add(Motion(10))
        }
        add(Motion(40))
    }

    private data class Motion(
        val samples: Int,
        val gyroscope: Vector3 = Vector3(0f, 0f, 0f),
        val accelerometer: Vector3 = Vector3(0f, 0f, 1f),
    )

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 19_230_769L
    }
}
