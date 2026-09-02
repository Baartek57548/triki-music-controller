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
}
