package pl.trikimusic.controller.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPersistenceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `settings round trip preserves button mappings calibration and preferences`() {
        val custom = ControlProfile(
            id = "custom-id",
            name = "Workout",
            buttonMappings = listOf(
                ButtonMapping(ButtonClickType.SINGLE, MediaAction.PAUSE),
                ButtonMapping(ButtonClickType.DOUBLE, MediaAction.NEXT),
                ButtonMapping(ButtonClickType.TRIPLE, MediaAction.STOP),
            ),
        )
        val original = AppSettings(
            onboardingComplete = true,
            knownDeviceAddress = "AA:BB:CC:DD:EE:FF",
            knownDeviceName = "Triki 42",
            activeProfileId = custom.id,
            profiles = defaultProfiles() + custom,
            calibration = CalibrationProfile(
                sampleCount = 200,
                calibratedAtMillis = 123L,
                orientationConventionVersion = CURRENT_ORIENTATION_CONVENTION_VERSION,
            ),
            developerMode = true,
            backgroundEnabled = false,
            theme = ThemePreference.DARK,
        )

        val encoded = json.encodeToString(AppSettings.serializer(), original)
        val restored = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(original, restored)
        assertEquals(MediaAction.PAUSE, restored.activeProfile.actionFor(ButtonClickType.SINGLE))
        assertEquals(MediaAction.NEXT, restored.activeProfile.actionFor(ButtonClickType.DOUBLE))
        assertEquals(MediaAction.STOP, restored.activeProfile.actionFor(ButtonClickType.TRIPLE))
        assertTrue(restored.calibration.isValid)
    }

    @Test
    fun `legacy face-up calibration migrates from 180 degrees to current convention`() {
        val positive = CalibrationProfile(
            neutralRoll = 178f,
            sampleCount = 200,
            calibratedAtMillis = 123L,
        ).withCurrentOrientationConvention()
        val negative = CalibrationProfile(
            neutralRoll = -176f,
            sampleCount = 200,
            calibratedAtMillis = 123L,
        ).withCurrentOrientationConvention()

        assertEquals(2f, positive.neutralRoll, 0.001f)
        assertEquals(-4f, negative.neutralRoll, 0.001f)
        assertEquals(CURRENT_ORIENTATION_CONVENTION_VERSION, positive.orientationConventionVersion)
        assertEquals(CURRENT_ORIENTATION_CONVENTION_VERSION, negative.orientationConventionVersion)
    }

    @Test
    fun `invalid default calibration is not promoted by orientation migration`() {
        val result = CalibrationProfile().withCurrentOrientationConvention()

        assertEquals(CalibrationProfile(), result)
    }

    @Test
    fun `unknown future fields do not break persisted settings`() {
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings())
            .dropLast(1) + ",\"futureField\":42}"

        val restored = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(DEFAULT_PROFILE_ID, restored.activeProfileId)
    }

    @Test
    fun `legacy gesture fields are ignored without losing button settings`() {
        val legacy = """
            {
              "onboardingComplete": true,
              "gestureWizardCompleted": true,
              "gestureLearningVersion": 4,
              "sensitivity": "HIGH",
              "personalizedGestureModel": {"enabled":true,"samples":[]},
              "activeProfileId": "$DEFAULT_PROFILE_ID",
              "profiles": ${json.encodeToString(defaultProfiles())}
            }
        """.trimIndent()

        val restored = json.decodeFromString(AppSettings.serializer(), legacy)

        assertTrue(restored.onboardingComplete)
        assertEquals(MediaAction.PLAY_PAUSE, restored.activeProfile.actionFor(ButtonClickType.SINGLE))
    }

    @Test
    fun `legacy profile without button mappings receives safe media defaults`() {
        val legacy = """
            {
              "activeProfileId": "legacy",
              "profiles": [
                {
                  "id": "legacy",
                  "name": "Legacy",
                  "mappings": [],
                  "builtIn": false
                }
              ]
            }
        """.trimIndent()

        val restored = json.decodeFromString(AppSettings.serializer(), legacy)

        assertEquals(MediaAction.PLAY_PAUSE, restored.activeProfile.actionFor(ButtonClickType.SINGLE))
        assertEquals(MediaAction.NEXT, restored.activeProfile.actionFor(ButtonClickType.DOUBLE))
        assertEquals(MediaAction.PREVIOUS, restored.activeProfile.actionFor(ButtonClickType.TRIPLE))
    }
}
