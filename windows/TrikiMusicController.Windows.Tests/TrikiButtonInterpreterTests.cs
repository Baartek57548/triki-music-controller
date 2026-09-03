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

    [Fact]
    public void CheckAndConsumeHoldDuration_WhenNotPressed_ReturnsFalse()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        Assert.False(interpreter.IsPressed);
        Assert.False(interpreter.CheckAndConsumeHoldDuration(time, 4_000_000_000L));
    }

    [Fact]
    public void CheckAndConsumeHoldDuration_WhenPressedShorterThanRequired_ReturnsFalse()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        // Press and hold for 2.0 seconds (100 samples)
        for (int i = 0; i < 100; i++)
        {
            time += SampleDt;
            interpreter.Process(CreateSample(time, 1));
        }

        Assert.True(interpreter.IsPressed);
        // Checking for 4.0s hold when only 2.0s elapsed
        Assert.False(interpreter.CheckAndConsumeHoldDuration(time, 4_000_000_000L));
    }

    [Fact]
    public void CheckAndConsumeHoldDuration_WhenPressedLongerThanRequired_ConsumesAndSuppressesClickOnRelease()
    {
        var interpreter = new TrikiButtonInterpreter();
        long time = 1_000_000_000L;
        ArmButtonFlagProtocol(interpreter, ref time);

        // Press and hold for 4.2 seconds (210 samples)
        for (int i = 0; i < 210; i++)
        {
            time += SampleDt;
            interpreter.Process(CreateSample(time, 1));
        }

        Assert.True(interpreter.IsPressed);

        // 1. First check: threshold reached (4.2s >= 4.0s) -> returns true and marks consumed
        Assert.True(interpreter.CheckAndConsumeHoldDuration(time, 4_000_000_000L));

        // 2. Second check: already consumed -> returns false
        Assert.False(interpreter.CheckAndConsumeHoldDuration(time + SampleDt, 4_000_000_000L));

        // 3. Keep holding for another 1 second
        for (int i = 0; i < 50; i++)
        {
            time += SampleDt;
            interpreter.Process(CreateSample(time, 1));
        }

        // 4. Release button
        time += SampleDt;
        var releaseResult = interpreter.Process(CreateSample(time, 0));
        Assert.Null(releaseResult);

        // 5. Advance time past timeout: NO click event emitted
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
