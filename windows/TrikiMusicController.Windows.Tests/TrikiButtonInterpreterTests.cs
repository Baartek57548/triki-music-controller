using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;
using Xunit;

namespace TrikiMusicController.Windows.Tests;

public sealed class TrikiButtonInterpreterTests
{
    private const long SampleDt = 20_000_000L; // 20 ms @ 50 Hz

    private static TrikiSensorData CreateSample(long timestampNanos, int status)
    {
        return new TrikiSensorData(
            timestampNanos / SampleDt,
            timestampNanos,
            new Vector3f(0, 0, 0),
            new Vector3f(0, 0, 1.0f),
            new RawVector3(0, 0, 0),
            new RawVector3(0, 0, 0),
            status);
    }

    private static void ArmButtonFlagProtocol(TrikiButtonInterpreter interpreter, ref long time)
    {
        for (int i = 0; i < 15; i++)
        {
            time += SampleDt;
            interpreter.Process(CreateSample(time, 0));
        }
        Assert.Equal(TrikiButtonProtocolMode.ButtonFlag, interpreter.ProtocolMode);
    }

    [Fact]
    public void Process_WhenStatusIsSequenceCounter_SwitchesToSequenceCounterMode()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;

        var result = interpreter.Process(CreateSample(time, 5));

        Assert.Null(result);
        Assert.Equal(TrikiButtonProtocolMode.SequenceCounter, interpreter.ProtocolMode);
        Assert.False(interpreter.IsPressed);
    }

    [Fact]
    public void Process_WhenButtonFlagProtocolObserved_EnablesButtonFlagMode()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;

        ArmButtonFlagProtocol(interpreter, ref time);

        Assert.Equal(TrikiButtonProtocolMode.ButtonFlag, interpreter.ProtocolMode);
        Assert.False(interpreter.IsPressed);
    }

    [Fact]
    public void Process_SingleClick_EmitsSingleClickEventAfterTimeout()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        // Press button for 100 ms (5 samples @ 20ms)
        for (int i = 0; i < 5; i++)
        {
            time += SampleDt;
            interpreter.Process(CreateSample(time, 1));
            if (i >= 1)
            {
                Assert.True(interpreter.IsPressed);
            }
        }

        // Release button
        time += SampleDt;
        var releaseResult = interpreter.Process(CreateSample(time, 0));
        Assert.Null(releaseResult);

        // Advance time past MultiClickTimeout (450ms)
        ButtonClickEvent? eventResult = null;
        for (int i = 0; i < 25; i++) // 500 ms
        {
            time += SampleDt;
            var res = interpreter.Process(CreateSample(time, 0));
            if (res is not null) eventResult = res;
        }

        Assert.NotNull(eventResult);
        Assert.Equal(ButtonClickType.Single, eventResult.Type);
    }

    [Fact]
    public void Process_DoubleClick_EmitsDoubleClickEventAfterTimeout()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        // First click (60 ms press, 60 ms release)
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 1)); }
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 0)); }

        // Second click (60 ms press, 60 ms release)
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 1)); }
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 0)); }

        // Advance time past timeout
        ButtonClickEvent? eventResult = null;
        for (int i = 0; i < 25; i++)
        {
            time += SampleDt;
            var res = interpreter.Process(CreateSample(time, 0));
            if (res is not null) eventResult = res;
        }

        Assert.NotNull(eventResult);
        Assert.Equal(ButtonClickType.Double, eventResult.Type);
    }

    [Fact]
    public void Process_TripleClick_EmitsImmediatelyOnThirdRelease()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        // Click 1
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 1)); }
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 0)); }

        // Click 2
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 1)); }
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 0)); }

        // Click 3 press
        for (int i = 0; i < 3; i++) { time += SampleDt; interpreter.Process(CreateSample(time, 1)); }

        // Click 3 release
        ButtonClickEvent? thirdRelease = null;
        for (int i = 0; i < 2; i++)
        {
            time += SampleDt;
            var res = interpreter.Process(CreateSample(time, 0));
            if (res is not null) thirdRelease = res;
        }

        Assert.NotNull(thirdRelease);
        Assert.Equal(ButtonClickType.Triple, thirdRelease.Type);
    }

    [Theory]
    [InlineData(210)]
    [InlineData(500)]
    public void LongHoldDoesNotGenerateClickAndNextNormalClickStillWorks(int heldSamples)
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);
        for (var index = 0; index < heldSamples; index++)
        {
            time += SampleDt;
            Assert.Null(interpreter.Process(CreateSample(time, 1)));
        }
        Assert.True(interpreter.IsPressed);
        for (var index = 0; index < 30; index++)
        {
            time += SampleDt;
            Assert.Null(interpreter.Process(CreateSample(time, 0)));
        }
        Assert.False(interpreter.ShouldSuppressMotionControl);

        for (var index = 0; index < 5; index++)
        {
            time += SampleDt;
            Assert.Null(interpreter.Process(CreateSample(time, 1)));
        }
        var clicks = new List<ButtonClickEvent>();
        for (var index = 0; index < 30; index++)
        {
            time += SampleDt;
            if (interpreter.Process(CreateSample(time, 0)) is { } click) clicks.Add(click);
        }
        Assert.Equal(ButtonClickType.Single, Assert.Single(clicks).Type);
    }

    [Fact]
    public void ConsumeCurrentHoldWithoutPressDoesNotSuppressNextInteraction()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);
        Assert.False(interpreter.ConsumeCurrentHold());
        Assert.False(interpreter.ShouldSuppressMotionControl);
    }

    [Fact]
    public void ConsumeCurrentHold_SuppressesPendingClickOnRelease()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        // Press button
        for (int i = 0; i < 10; i++)
        {
            time += SampleDt;
            interpreter.Process(CreateSample(time, 1));
        }

        Assert.True(interpreter.IsPressed);
        Assert.True(interpreter.ConsumeCurrentHold());

        // Release button
        time += SampleDt;
        interpreter.Process(CreateSample(time, 0));

        // Advance time
        ButtonClickEvent? eventResult = null;
        for (int i = 0; i < 25; i++)
        {
            time += SampleDt;
            var res = interpreter.Process(CreateSample(time, 0));
            if (res is not null) eventResult = res;
        }

        Assert.Null(eventResult);
    }

    [Fact]
    public void Process_StreamGap_ResetsInterpreter()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);
        Assert.Equal(TrikiButtonProtocolMode.ButtonFlag, interpreter.ProtocolMode);

        // Jump time by 500 ms (> 300 ms stream gap)
        time += 500_000_000L;
        interpreter.Process(CreateSample(time, 0));

        Assert.Equal(TrikiButtonProtocolMode.Unknown, interpreter.ProtocolMode);
    }
}
