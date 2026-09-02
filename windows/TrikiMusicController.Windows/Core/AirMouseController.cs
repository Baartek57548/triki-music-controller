using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed record AirMouseOutput(
    bool IsActive,
    bool IsScrollMode,
    int DeltaX,
    int DeltaY,
    int ScrollDelta);

public sealed class AirMouseController
{
    private const float EdgeMinLateralAccY = 0.70f;
    private const float EdgeMaxAbsZ = 0.35f;
    private const float TableRestMaxZ = -0.65f;
    private const float GyroDeadbandDps = 3.5f;
    private const float ScrollThresholdDegrees = 10.0f;
    private const long MaximumSampleGapNanos = 250_000_000;

    private long? _lastTimestampNanos;
    private float _accumulatedScrollAngle;
    private float _smoothedDeltaX;
    private float _smoothedDeltaY;

    public bool IsActive { get; set; }
    public bool IsScrollMode { get; private set; }

    public void Reset()
    {
        _lastTimestampNanos = null;
        _accumulatedScrollAngle = 0f;
        _smoothedDeltaX = 0f;
        _smoothedDeltaY = 0f;
        IsScrollMode = false;
    }

    public AirMouseOutput Process(FilteredSensorData sample)
    {
        if (!IsActive)
        {
            Reset();
            return new AirMouseOutput(IsActive: false, IsScrollMode: false, 0, 0, 0);
        }

        var now = sample.Source.TimestampNanos;
        var dt = 0.02f;
        if (_lastTimestampNanos is long previous)
        {
            var deltaNanos = now - previous;
            if (deltaNanos is > 0 and <= MaximumSampleGapNanos)
            {
                dt = deltaNanos / 1_000_000_000f;
            }
        }
        _lastTimestampNanos = now;

        var accY = sample.AccelerometerG.Y;
        var accZ = sample.AccelerometerG.Z;

        // Jeśli urządzenie leży płasko na stole (pozycją muzyczną do góry, Z ujemne), wstrzymaj ruch kursora
        if (accZ <= TableRestMaxZ)
        {
            _smoothedDeltaX = 0f;
            _smoothedDeltaY = 0f;
            _accumulatedScrollAngle = 0f;
            IsScrollMode = false;
            return new AirMouseOutput(IsActive: true, IsScrollMode: false, 0, 0, 0);
        }

        // Wykrywanie trybu kółka myszy (Scroll): kapsel obrócony na boczną krawędź 90° (|Y| >= 0.70g, |Z| <= 0.35g)
        if (MathF.Abs(accY) >= EdgeMinLateralAccY && MathF.Abs(accZ) <= EdgeMaxAbsZ)
        {
            IsScrollMode = true;
            _smoothedDeltaX = 0f;
            _smoothedDeltaY = 0f;

            var rawGyroZ = sample.GyroscopeDps.Z;
            if (MathF.Abs(rawGyroZ) >= GyroDeadbandDps)
            {
                _accumulatedScrollAngle += rawGyroZ * dt;
            }

            var scrollSteps = 0;
            if (_accumulatedScrollAngle >= ScrollThresholdDegrees)
            {
                scrollSteps = -(int)(_accumulatedScrollAngle / ScrollThresholdDegrees); // Obrót w prawo -> scroll w dół (ujemna wartość)
                _accumulatedScrollAngle %= ScrollThresholdDegrees;
            }
            else if (_accumulatedScrollAngle <= -ScrollThresholdDegrees)
            {
                scrollSteps = (int)(MathF.Abs(_accumulatedScrollAngle) / ScrollThresholdDegrees); // Obrót w lewo -> scroll w górę (dodatnia wartość)
                _accumulatedScrollAngle %= ScrollThresholdDegrees;
            }

            return new AirMouseOutput(
                IsActive: true,
                IsScrollMode: true,
                DeltaX: 0,
                DeltaY: 0,
                ScrollDelta: scrollSteps);
        }

        // Tryb kursora myszy (w dłoni, w pełnym zakresie pochylenia w górę/dół bez ograniczeń kątowych)
        IsScrollMode = false;
        _accumulatedScrollAngle = 0f;

        // Panning poziomy (Yaw): oś Z żyroskopu (obrót w prawo = ujemny Z -> dodatni DeltaX)
        // Przechylanie pionowe (Pitch): oś X żyroskopu (ruch w górę = ujemny X -> ujemny DeltaY / w dół = dodatni X -> dodatni DeltaY)
        var rawYaw = -sample.GyroscopeDps.Z;
        var rawPitch = sample.GyroscopeDps.X;

        var deadYaw = ApplyDeadband(rawYaw, GyroDeadbandDps);
        var deadPitch = ApplyDeadband(rawPitch, GyroDeadbandDps);

        var accelX = ApplyVelocityCurve(deadYaw);
        var accelY = ApplyVelocityCurve(deadPitch) * 1.15f; // Dopasowanie do naturalnej ergonomii ruchu nadgarstka

        // Filtr wygładzający (EMA)
        _smoothedDeltaX = _smoothedDeltaX * 0.40f + accelX * 0.60f;
        _smoothedDeltaY = _smoothedDeltaY * 0.40f + accelY * 0.60f;

        var dx = (int)MathF.Round(_smoothedDeltaX);
        var dy = (int)MathF.Round(_smoothedDeltaY);

        return new AirMouseOutput(
            IsActive: true,
            IsScrollMode: false,
            DeltaX: dx,
            DeltaY: dy,
            ScrollDelta: 0);
    }

    private static float ApplyDeadband(float value, float deadband) =>
        MathF.Abs(value) < deadband ? 0f : (value > 0 ? value - deadband : value + deadband);

    private static float ApplyVelocityCurve(float angularVelocityDps)
    {
        var abs = MathF.Abs(angularVelocityDps);
        if (abs <= 0f) return 0f;
        // Płynna czułość bazowa z dynamicznym przyspieszeniem przy szybszym ruchu dłoni
        var speed = abs * 0.22f + (abs * abs) * 0.0035f;
        return MathF.Sign(angularVelocityDps) * speed;
    }
}
