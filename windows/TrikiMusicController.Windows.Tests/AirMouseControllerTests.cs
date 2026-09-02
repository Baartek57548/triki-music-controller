using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;
using Xunit;

namespace TrikiMusicController.Windows.Tests;

public sealed class AirMouseControllerTests
{
    [Fact]
    public void Process_WhenInactive_ReturnsZeroMovement()
    {
        var controller = new AirMouseController { IsActive = false };
        var sample = SensorTestData.Filtered(
            1_000_000_000L,
            new Vector3f(0f, 0f, 1.0f),
            new Vector3f(30f, 0f, -30f));

        var output = controller.Process(sample);

        Assert.False(output.IsActive);
        Assert.False(output.IsScrollMode);
        Assert.Equal(0, output.DeltaX);
        Assert.Equal(0, output.DeltaY);
        Assert.Equal(0, output.ScrollDelta);
    }

    [Fact]
    public void Process_WhenActiveAndInverted_GeneratesCursorMovement()
    {
        var controller = new AirMouseController { IsActive = true };

        // Panning right (negative gyro Z in inverted pose -> positive DeltaX)
        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -25f));
        var s2 = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -25f));

        controller.Process(s1);
        var out2 = controller.Process(s2);

        Assert.True(out2.IsActive);
        Assert.False(out2.IsScrollMode);
        Assert.True(out2.DeltaX > 0, $"Oczekiwano ruchu w prawo (DeltaX > 0), otrzymano {out2.DeltaX}");
    }

    [Fact]
    public void Process_WhenTiltingUpAndDown_GeneratesCorrectVerticalMovement()
    {
        var controller = new AirMouseController { IsActive = true };

        // Tilting UP: Gyroscope X is negative in sensor frame -> DeltaY < 0 (cursor moves UP)
        var sUp1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(-25f, 0f, 0f));
        var sUp2 = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(-25f, 0f, 0f));
        controller.Process(sUp1);
        var outUp = controller.Process(sUp2);
        Assert.True(outUp.DeltaY < 0, $"Oczekiwano ruchu w górę (DeltaY < 0), otrzymano {outUp.DeltaY}");

        controller.Reset();

        // Tilting DOWN: Gyroscope X is positive in sensor frame -> DeltaY > 0 (cursor moves DOWN)
        var sDown1 = SensorTestData.Filtered(2_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(25f, 0f, 0f));
        var sDown2 = SensorTestData.Filtered(2_020_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(25f, 0f, 0f));
        controller.Process(sDown1);
        var outDown = controller.Process(sDown2);
        Assert.True(outDown.DeltaY > 0, $"Oczekiwano ruchu w dół (DeltaY > 0), otrzymano {outDown.DeltaY}");
    }

    [Fact]
    public void Process_WhenTiltingSteeplyUpwards_ContinuesCursorMovement()
    {
        var controller = new AirMouseController { IsActive = true };

        // Steep upward tilt (pointing almost straight up: AccX is -0.90g, AccZ is 0.10g, AccY is 0)
        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(-0.90f, 0f, 0.10f), new Vector3f(-30f, 0f, 0f));
        var s2 = SensorTestData.Filtered(1_020_000_000L, new Vector3f(-0.90f, 0f, 0.10f), new Vector3f(-30f, 0f, 0f));

        controller.Process(s1);
        var output = controller.Process(s2);

        Assert.True(output.IsActive);
        Assert.False(output.IsScrollMode);
        Assert.True(output.DeltaY < 0, $"Oczekiwano ruchu w górę przy stromym uniesieniu (DeltaY < 0), otrzymano {output.DeltaY}");
    }

    [Fact]
    public void Process_WhenClickTransientActive_SuppressesMotion()
    {
        var controller = new AirMouseController { IsActive = true };
        var t = 1_000_000_000L;

        var s1 = SensorTestData.Filtered(t, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -30f));
        controller.Process(s1);

        controller.NotifyClickTransient(t);

        // W oknie 90 ms od kliknięcia ruch jest tłumiony (ochrona przed zerwaniem celownika)
        var sDuring = SensorTestData.Filtered(t + 40_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -30f));
        var outDuring = controller.Process(sDuring);
        Assert.Equal(0, outDuring.DeltaX);
        Assert.Equal(0, outDuring.DeltaY);

        // Po wygaśnięciu okna tłumienia ruch zostaje wznowiony
        var sAfter = SensorTestData.Filtered(t + 120_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -30f));
        var outAfter = controller.Process(sAfter);
        Assert.True(outAfter.DeltaX > 0);
    }

    [Fact]
    public void Process_WhenBelowDeadband_SuppressesJitter()
    {
        var controller = new AirMouseController { IsActive = true };
        var sample = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(2.0f, 1.0f, -2.0f));

        var output = controller.Process(sample);

        Assert.Equal(0, output.DeltaX);
        Assert.Equal(0, output.DeltaY);
    }

    [Fact]
    public void Process_WhenIn90DegreeEdge_ActivatesScrollMode()
    {
        var controller = new AirMouseController { IsActive = true };

        // Pozycja 90° na bocznej krawędzi (AccY ~ 1.0g, AccZ bliskie 0)
        // Obrót wokół Z o 30 dps przez 500 ms = 15 stopni obrotu (> 10 stopni progu)
        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 1.0f, 0.05f), new Vector3f(0f, 0f, 30f));
        controller.Process(s1);

        var totalScrollDelta = 0;
        var sawScrollMode = false;
        for (int i = 1; i <= 25; i++)
        {
            var s = SensorTestData.Filtered(1_000_000_000L + i * 20_000_000L, new Vector3f(0f, 1.0f, 0.05f), new Vector3f(0f, 0f, 30f));
            var output = controller.Process(s);
            if (output.IsScrollMode) sawScrollMode = true;
            totalScrollDelta += output.ScrollDelta;
        }

        Assert.True(sawScrollMode);
        Assert.True(totalScrollDelta < 0, $"Oczekiwano sumarycznego scrolla w dół (wartość ujemna), otrzymano {totalScrollDelta}");
    }

    [Fact]
    public void ButtonInterpreter_CheckAndConsumeHoldDuration_TriggersAt4Seconds()
    {
        var interpreter = new TrikiButtonInterpreter();
        var t = 1_000_000_000L;

        // 12 próbek protokołu button flag co 20ms
        for (int i = 0; i < 12; i++)
        {
            t += 20_000_000L;
            interpreter.Process(new TrikiSensorData(i, t, default, default, default, default, 0));
        }

        Assert.Equal(TrikiButtonProtocolMode.ButtonFlag, interpreter.ProtocolMode);

        // Naciśnięcie przycisku i debouncing
        t += 20_000_000L;
        var pressTime = t;
        interpreter.Process(new TrikiSensorData(100, t, default, default, default, default, 1));
        t += 20_000_000L;
        interpreter.Process(new TrikiSensorData(101, t, default, default, default, default, 1));

        Assert.True(interpreter.IsPressed);

        // Podtrzymanie stanu wciśnięcia przez 2 sekundy
        for (int i = 0; i < 100; i++)
        {
            t += 20_000_000L;
            interpreter.Process(new TrikiSensorData(102 + i, t, default, default, default, default, 1));
        }

        // Po 2 sekundach -> nie powinno jeszcze wyzwolić 4s
        var at2s = interpreter.CheckAndConsumeHoldDuration(t, 4_000_000_000L);
        Assert.False(at2s);

        // Kontynuacja wciśnięcia do ponad 4 sekund
        for (int i = 0; i < 110; i++)
        {
            t += 20_000_000L;
            interpreter.Process(new TrikiSensorData(202 + i, t, default, default, default, default, 1));
        }

        // Po ponad 4 sekundach -> powinno wyzwolić i skonsumować
        var at4s = interpreter.CheckAndConsumeHoldDuration(t, 4_000_000_000L);
        Assert.True(at4s);

        // Ponowne sprawdzenie w tym samym naciśnięciu -> false (hold już skonsumowany)
        var at4sAgain = interpreter.CheckAndConsumeHoldDuration(t + 20_000_000L, 4_000_000_000L);
        Assert.False(at4sAgain);

        // Zwolnienie przycisku po skonsumowanym holdzie nie powinno zwrócić kliknięcia
        t += 20_000_000L;
        var release = interpreter.Process(new TrikiSensorData(400, t, default, default, default, default, 0));
        Assert.Null(release);
    }

    [Fact]
    public void Process_WhenPanningLeft_GeneratesNegativeDeltaX()
    {
        var controller = new AirMouseController { IsActive = true };

        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, 25f));
        var s2 = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, 25f));

        controller.Process(s1);
        var out2 = controller.Process(s2);

        Assert.True(out2.IsActive);
        Assert.False(out2.IsScrollMode);
        Assert.True(out2.DeltaX < 0, $"Oczekiwano ruchu w lewo (DeltaX < 0), otrzymano {out2.DeltaX}");
    }

    [Theory]
    [InlineData(-25f, -25f, true, true)]   // Top-Right: Yaw right (DeltaX > 0), Pitch up (DeltaY < 0)
    [InlineData(25f, -25f, false, true)]   // Top-Left: Yaw left (DeltaX < 0), Pitch up (DeltaY < 0)
    [InlineData(-25f, 25f, true, false)]   // Bottom-Right: Yaw right (DeltaX > 0), Pitch down (DeltaY > 0)
    [InlineData(25f, 25f, false, false)]   // Bottom-Left: Yaw left (DeltaX < 0), Pitch down (DeltaY > 0)
    public void Process_WhenMovingDiagonallyToCorners_GeneratesCorrectDeltaSign(
        float gyroZ, float gyroX, bool expectPositiveX, bool expectNegativeY)
    {
        var controller = new AirMouseController { IsActive = true };

        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(gyroX, 0f, gyroZ));
        var s2 = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(gyroX, 0f, gyroZ));

        controller.Process(s1);
        var out2 = controller.Process(s2);

        Assert.True(expectPositiveX ? out2.DeltaX > 0 : out2.DeltaX < 0);
        Assert.True(expectNegativeY ? out2.DeltaY < 0 : out2.DeltaY > 0);
    }

    [Fact]
    public void Process_WhenSlowMicroAiming_AccumulatesSubpixels()
    {
        var controller = new AirMouseController { IsActive = true };
        var totalDeltaX = 0;

        // Ruch tuż ponad martwą strefą (3.0 dps): 4.0 dps daje ułamkowe piksele na próbkę
        for (int i = 0; i < 20; i++)
        {
            var s = SensorTestData.Filtered(1_000_000_000L + i * 20_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -4.0f));
            var output = controller.Process(s);
            totalDeltaX += output.DeltaX;
        }

        Assert.True(totalDeltaX > 0, "Sub-pikselowa akumulacja powinna wygenerować przemieszczenie przy powolnym mikro-celowaniu.");
    }

    [Fact]
    public void Process_WhenRestingFlatOnTable_SuppressesMovement()
    {
        var controller = new AirMouseController { IsActive = true };

        // Kapsel leży płasko na stole: AccZ = -0.98g (<= -0.65g)
        var s = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, -0.98f), new Vector3f(50f, 0f, -50f));
        var output = controller.Process(s);

        Assert.True(output.IsActive);
        Assert.False(output.IsScrollMode);
        Assert.Equal(0, output.DeltaX);
        Assert.Equal(0, output.DeltaY);
        Assert.Equal(0, output.ScrollDelta);
    }

    [Fact]
    public void Process_WhenIn90DegreeEdge_RotatingLeft_ScrollsUp()
    {
        var controller = new AirMouseController { IsActive = true };

        // Pozycja 90° na bocznej krawędzi (AccY ~ 1.0g, AccZ ~ 0)
        // Obrót wokół Z o -30 dps (w lewo) -> scroll w górę (wartość dodatnia)
        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 1.0f, 0.05f), new Vector3f(0f, 0f, -30f));
        controller.Process(s1);

        var totalScrollDelta = 0;
        for (int i = 1; i <= 25; i++)
        {
            var s = SensorTestData.Filtered(1_000_000_000L + i * 20_000_000L, new Vector3f(0f, 1.0f, 0.05f), new Vector3f(0f, 0f, -30f));
            var output = controller.Process(s);
            totalScrollDelta += output.ScrollDelta;
        }

        Assert.True(totalScrollDelta > 0, $"Oczekiwano sumarycznego scrolla w górę (wartość dodatnia), otrzymano {totalScrollDelta}");
    }

    [Fact]
    public void Process_WhenNonFiniteValues_ReturnsZeroMovement()
    {
        var controller = new AirMouseController { IsActive = true };

        var nanSample = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(float.NaN, 0f, 0f));
        var out1 = controller.Process(nanSample);

        Assert.Equal(0, out1.DeltaX);
        Assert.Equal(0, out1.DeltaY);
        Assert.Equal(0, out1.ScrollDelta);
    }

    [Fact]
    public void Process_WhenScrollModeHysteresis_PreventsFlickerNearBoundary()
    {
        var controller = new AirMouseController { IsActive = true };

        // 1. Start in pointer mode (|Y| = 0.60g, |Z| = 0.40g) -> below enter threshold (0.70g, 0.35g)
        var sPointer = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0.60f, 0.40f), new Vector3f(0f, 0f, 0f));
        var out1 = controller.Process(sPointer);
        Assert.False(out1.IsScrollMode);

        // 2. Enter scroll mode (|Y| = 0.75g, |Z| = 0.20g) -> crosses enter threshold
        var sEnter = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, 0.75f, 0.20f), new Vector3f(0f, 0f, 0f));
        var out2 = controller.Process(sEnter);
        Assert.True(out2.IsScrollMode);

        // 3. Small hand wobble back to (|Y| = 0.60g, |Z| = 0.40g) -> remains in scroll mode thanks to hysteresis (exit is < 0.55g / > 0.50g)
        var sWobble = SensorTestData.Filtered(1_040_000_000L, new Vector3f(0f, 0.60f, 0.40f), new Vector3f(0f, 0f, 0f));
        var out3 = controller.Process(sWobble);
        Assert.True(out3.IsScrollMode, "Histereza powinna zapobiec wyjściu z trybu Scroll przy drobnym zachwianiu dłoni.");

        // 4. Intentional return to pointer mode (|Y| = 0.45g, |Z| = 0.60g) -> crosses exit threshold
        var sExit = SensorTestData.Filtered(1_060_000_000L, new Vector3f(0f, 0.45f, 0.60f), new Vector3f(0f, 0f, 0f));
        var out4 = controller.Process(sExit);
        Assert.False(out4.IsScrollMode, "Powinno wyjść z trybu Scroll po wyraźnym powrocie do pozycji wskaźnika.");
    }

    [Fact]
    public void Process_WhenFastRotationInScrollMode_ProducesMultipleScrollSteps()
    {
        var controller = new AirMouseController { IsActive = true };

        // 90° edge pose
        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0.95f, 0.05f), new Vector3f(0f, 0f, 0f));
        controller.Process(s1);

        // Fast flick: 900 dps over 20ms = 18 degrees (> 2 * 8.0 deg threshold) -> 2 steps down
        var sFlick = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, 0.95f, 0.05f), new Vector3f(0f, 0f, 900f));
        var output = controller.Process(sFlick);

        Assert.True(output.IsScrollMode);
        Assert.Equal(-2, output.ScrollDelta);
    }

    [Fact]
    public void Process_WhenTimestampDoesNotAdvance_HandlesGracefully()
    {
        var controller = new AirMouseController { IsActive = true };

        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -25f));
        controller.Process(s1);

        // Same timestamp
        var sSame = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -25f));
        var outSame = controller.Process(sSame);

        Assert.True(outSame.IsActive);
    }

    [Fact]
    public void Process_WhenIn90DegreeLeftEdge_ActivatesScrollMode()
    {
        var controller = new AirMouseController { IsActive = true };

        // Left edge: AccY = -0.95g, AccZ = 0.05g
        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, -0.95f, 0.05f), new Vector3f(0f, 0f, 0f));
        var out1 = controller.Process(s1);
        Assert.True(out1.IsScrollMode);

        // Rotate right: 400 dps over 20ms = 8.0 degrees -> 1 step down (-1)
        var sRot = SensorTestData.Filtered(1_020_000_000L, new Vector3f(0f, -0.95f, 0.05f), new Vector3f(0f, 0f, 400f));
        var outRot = controller.Process(sRot);
        Assert.True(outRot.IsScrollMode);
        Assert.Equal(-1, outRot.ScrollDelta);
    }

    [Fact]
    public void Process_WhenSlowMicroAimingNegative_AccumulatesSubpixels()
    {
        var controller = new AirMouseController { IsActive = true };
        var totalDeltaX = 0;
        var totalDeltaY = 0;

        // Slow micro-movement left (+4.0 dps yaw in inverted frame -> negative DeltaX) and up (-4.0 dps pitch -> negative DeltaY)
        for (int i = 0; i < 20; i++)
        {
            var s = SensorTestData.Filtered(1_000_000_000L + i * 20_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(-4.0f, 0f, 4.0f));
            var output = controller.Process(s);
            totalDeltaX += output.DeltaX;
            totalDeltaY += output.DeltaY;
        }

        Assert.True(totalDeltaX < 0, "Sub-pikselowa akumulacja powinna wygenerować ruch w lewo przy powolnym mikro-celowaniu.");
        Assert.True(totalDeltaY < 0, "Sub-pikselowa akumulacja powinna wygenerować ruch w górę przy powolnym mikro-celowaniu.");
    }

    [Fact]
    public void Process_WhenDoubleClickTransientSequence_SuppressesJitterAcrossClicks()
    {
        var controller = new AirMouseController { IsActive = true };
        var t = 1_000_000_000L;

        // Initial movement sample
        var s0 = SensorTestData.Filtered(t, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -50f));
        controller.Process(s0);

        // Click 1 down
        controller.NotifyClickTransient(t);

        // Sample during click 1
        var s1 = SensorTestData.Filtered(t + 30_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -50f));
        var out1 = controller.Process(s1);
        Assert.Equal(0, out1.DeltaX);

        // Click 2 down at 80ms (extends suppression by 90ms -> until 170ms)
        controller.NotifyClickTransient(t + 80_000_000L);

        // Sample during click 2 (at 120ms)
        var s2 = SensorTestData.Filtered(t + 120_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -50f));
        var out2 = controller.Process(s2);
        Assert.Equal(0, out2.DeltaX);

        // Sample after double click sequence completed (at 200ms > 170ms)
        var s3 = SensorTestData.Filtered(t + 200_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -50f));
        var out3 = controller.Process(s3);
        Assert.True(out3.DeltaX > 0, "Ruch powinien zostać wznowiony po zakończeniu sekwencji podwójnego kliknięcia.");
    }

    [Fact]
    public void Process_WhenSampleGapExceedsThreshold_ResetsAccumulatorsGracefully()
    {
        var controller = new AirMouseController { IsActive = true };

        var s1 = SensorTestData.Filtered(1_000_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -50f));
        controller.Process(s1);

        // Gap of 500ms (> 250ms MaximumSampleGapNanos)
        var sGap = SensorTestData.Filtered(1_500_000_000L, new Vector3f(0f, 0f, 1.0f), new Vector3f(0f, 0f, -50f));
        var outGap = controller.Process(sGap);

        // Should process smoothly without throwing or unbounded jumps
        Assert.True(outGap.IsActive);
    }
}
