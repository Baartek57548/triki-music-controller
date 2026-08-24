package pl.trikimusic.controller.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPersistenceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `settings round trip preserves profiles mappings calibration and preferences`() {
        val custom = ControlProfile(
            id = "custom-id",
            name = "Workout",
            mappings = GestureType.entries.map { GestureMapping(it, MediaAction.PLAY_PAUSE) },
        )
        val learnedModel = PersonalizedGestureModel(
            samples = listOf(
                LearnedGestureSample(
                    gesture = GestureType.SHAKE,
                    features = GestureFeatureVector(values = List(GESTURE_FEATURE_DIMENSION) { it / 100f }),
                    capturedAtMillis = 100L,
                ),
                LearnedGestureSample(
                    gesture = GestureType.SHAKE,
                    features = GestureFeatureVector(values = List(GESTURE_FEATURE_DIMENSION) { it / 90f }),
                    capturedAtMillis = 200L,
                ),
            ),
        )
        val original = AppSettings(
            onboardingComplete = true,
            gestureWizardCompleted = true,
            gestureLearningVersion = CURRENT_GESTURE_LEARNING_VERSION,
            knownDeviceAddress = "AA:BB:CC:DD:EE:FF",
            knownDeviceName = "Triki 42",
            activeProfileId = custom.id,
            profiles = defaultProfiles() + custom,
            sensitivity = SensitivityLevel.HIGH,
            calibration = CalibrationProfile(sampleCount = 200, calibratedAtMillis = 123L),
            personalizedGestureModel = learnedModel,
            developerMode = true,
            backgroundEnabled = false,
            theme = ThemePreference.DARK,
        )

        val encoded = json.encodeToString(AppSettings.serializer(), original)
        val restored = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(original, restored)
        assertEquals(MediaAction.PLAY_PAUSE, restored.activeProfile.actionFor(GestureType.FLIP))
        assertTrue(restored.gestureWizardCompleted)
        assertEquals(CURRENT_GESTURE_LEARNING_VERSION, restored.gestureLearningVersion)
        assertTrue(restored.personalizedGestureModel.isTrained(GestureType.SHAKE))
        assertTrue(restored.calibration.isValid)
    }

    @Test
    fun `unknown future fields do not break persisted settings`() {
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings())
            .dropLast(1) + ",\"futureField\":42}"

        val restored = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(DEFAULT_PROFILE_ID, restored.activeProfileId)
    }

    @Test
    fun `settings saved by an older version require the gesture wizard`() {
        val encodedWithoutWizardFlag = """
            {
              "onboardingComplete": true,
              "activeProfileId": "$DEFAULT_PROFILE_ID",
              "profiles": ${json.encodeToString(defaultProfiles())}
            }
        """.trimIndent()

        val restored = json.decodeFromString(AppSettings.serializer(), encodedWithoutWizardFlag)

        assertEquals(false, restored.gestureWizardCompleted)
        assertEquals(0, restored.gestureLearningVersion)
        assertTrue(restored.personalizedGestureModel.samples.isEmpty())
    }
}
