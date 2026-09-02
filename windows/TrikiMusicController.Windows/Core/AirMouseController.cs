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
    private const float EdgeMaxAbsZ = 0.40f;
    private const float EdgeMinPlaneAcc = 0.70f;
    private const float InvertedMinZ = 0.35f;
    private const float GyroDeadbandDps = 4.0f;
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

        var accZ = sample.AccelerometerG.Z;
        var planeAcc = MathF.Sqrt(sample.AccelerometerG.X * sample.AccelerometerG.X + sample.AccelerometerG.Y * sample.AccelerometerG.Y);

        // Wykrywanie trybu kółka myszy (Scroll w pozycji 90°)
        if (MathF.Abs(accZ) <= EdgeMaxAbsZ && planeAcc >= EdgeMinPlaneAcc)
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

        // Tryb kursora myszy (pozycja odwrócona kapsla / w dłoni)
        IsScrollMode = false;
        _accumulatedScrollAngle = 0f;

        if (accZ >= InvertedMinZ)
        {
            // W pozycji odwróconej:
            // Panning poziomy (Yaw): oś Z żyroskopu (obrót w prawo = ujemny Z -> dodatni DeltaX)
            // Przechylanie pionowe (Pitch): oś X żyroskopu (ruch w górę = ujemny X -> ujemny DeltaY / w dół = dodatni X -> dodatni DeltaY)
            var rawYaw = -sample.GyroscopeDps.Z;
            var rawPitch = sample.GyroscopeDps.X;

            var deadYaw = ApplyDeadband(rawYaw, GyroDeadbandDps);
            var deadPitch = ApplyDeadband(rawPitch, GyroDeadbandDps);

            var accelX = ApplyVelocityCurve(deadYaw);
            var accelY = ApplyVelocityCurve(deadPitch);

            // Filtr wygładzający (EMA)
            _smoothedDeltaX = _smoothedDeltaX * 0.45f + accelX * 0.55f;
            _smoothedDeltaY = _smoothedDeltaY * 0.45f + accelY * 0.55f;

            var dx = (int)MathF.Round(_smoothedDeltaX);
            var dy = (int)MathF.Round(_smoothedDeltaY);

            return new AirMouseOutput(
                IsActive: true,
                IsScrollMode: false,
                DeltaX: dx,
                DeltaY: dy,
                ScrollDelta: 0);
        }

        _smoothedDeltaX = 0f;
        _smoothedDeltaY = 0f;
        return new AirMouseOutput(IsActive: true, IsScrollMode: false, 0, 0, 0);
    }

    private static float ApplyDeadband(float value, float deadband) =>
        MathF.Abs(value) < deadband ? 0f : (value > 0 ? value - deadband : value + deadband);

    private static float ApplyVelocityCurve(float angularVelocityDps)
    {
        var abs = MathF.Abs(angularVelocityDps);
        if (abs <= 0f) return 0f;
        // Obniżona, precyzyjna czułość liniowa + nieliniowe przyspieszenie przy dynamicznym ruchu
        var speed = abs * 0.18f + (abs * abs) * 0.0020f;
        return MathF.Sign(angularVelocityDps) * speed;
    }
}
