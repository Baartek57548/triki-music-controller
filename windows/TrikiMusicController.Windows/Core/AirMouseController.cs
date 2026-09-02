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
    private const float GyroDeadbandDps = 3.0f;
    private const float ScrollThresholdDegrees = 8.0f;
    private const long MaximumSampleGapNanos = 250_000_000;
    private const long ClickSuppressionDurationNanos = 90_000_000; // 90 ms tłumienia drgań kliknięcia

    private long? _lastTimestampNanos;
    private float _accumulatedScrollAngle;
    private float _smoothedDeltaX;
    private float _smoothedDeltaY;
    private float _subpixelX;
    private float _subpixelY;
    private long _suppressMotionUntilNanos;

    public bool IsActive { get; set; }
    public bool IsScrollMode { get; private set; }

    public void Reset()
    {
        _lastTimestampNanos = null;
        _accumulatedScrollAngle = 0f;
        _smoothedDeltaX = 0f;
        _smoothedDeltaY = 0f;
        _subpixelX = 0f;
        _subpixelY = 0f;
        _suppressMotionUntilNanos = 0;
        IsScrollMode = false;
    }

    /// <summary>
    /// Tłumi mikroruchy dłoni powstałe w momencie fizycznego wciskania lub puszczania przycisku na kapslu.
    /// </summary>
    public void NotifyClickTransient(long now)
    {
        _suppressMotionUntilNanos = Math.Max(_suppressMotionUntilNanos, now + ClickSuppressionDurationNanos);
        _smoothedDeltaX *= 0.1f;
        _smoothedDeltaY *= 0.1f;
        _subpixelX = 0f;
        _subpixelY = 0f;
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
            _subpixelX = 0f;
            _subpixelY = 0f;
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
            _subpixelX = 0f;
            _subpixelY = 0f;

            var rawGyroZ = sample.GyroscopeDps.Z;
            if (MathF.Abs(rawGyroZ) >= GyroDeadbandDps)
            {
                _accumulatedScrollAngle += rawGyroZ * dt;
            }

            var scrollSteps = 0;
            if (_accumulatedScrollAngle >= ScrollThresholdDegrees)
            {
                scrollSteps = -(int)(_accumulatedScrollAngle / ScrollThresholdDegrees); // Obrót w prawo -> scroll w dół
                _accumulatedScrollAngle %= ScrollThresholdDegrees;
            }
            else if (_accumulatedScrollAngle <= -ScrollThresholdDegrees)
            {
                scrollSteps = (int)(MathF.Abs(_accumulatedScrollAngle) / ScrollThresholdDegrees); // Obrót w lewo -> scroll w górę
                _accumulatedScrollAngle %= ScrollThresholdDegrees;
            }

            return new AirMouseOutput(
                IsActive: true,
                IsScrollMode: true,
                DeltaX: 0,
                DeltaY: 0,
                ScrollDelta: scrollSteps);
        }

        // Tryb kursora myszy (w dłoni, pełny zasięg pionowy i poziomy)
        IsScrollMode = false;
        _accumulatedScrollAngle = 0f;

        // Jeśli trwa tłumienie drgań kliknięcia, zablokuj ruch kursora
        if (now < _suppressMotionUntilNanos)
        {
            return new AirMouseOutput(
                IsActive: true,
                IsScrollMode: false,
                DeltaX: 0,
                DeltaY: 0,
                ScrollDelta: 0);
        }

        // Panning poziomy (Yaw): oś Z żyroskopu (obrót w prawo = ujemny Z -> dodatni DeltaX)
        // Przechylanie pionowe (Pitch): oś X żyroskopu (ruch w górę = ujemny X -> ujemny DeltaY / w dół = dodatni X -> dodatni DeltaY)
        var rawYaw = -sample.GyroscopeDps.Z;
        var rawPitch = sample.GyroscopeDps.X;

        var deadYaw = ApplySoftDeadband(rawYaw, GyroDeadbandDps);
        var deadPitch = ApplySoftDeadband(rawPitch, GyroDeadbandDps);

        var accelX = ApplyVelocityCurve(deadYaw);
        var accelY = ApplyVelocityCurve(deadPitch) * 1.15f; // Dopasowanie do naturalnego zakresu ruchu nadgarstka

        // Adaptacyjny współczynnik wygładzania EMA:
        // Przy małych prędkościach silniejsze tłumienie drżenia (0.50), przy szybkich ruchach natychmiastowa reakcja (0.80)
        var speedMag = MathF.Sqrt(deadYaw * deadYaw + deadPitch * deadPitch);
        var alpha = Math.Clamp(0.50f + (speedMag / 60f) * 0.30f, 0.50f, 0.85f);

        _smoothedDeltaX = _smoothedDeltaX * (1f - alpha) + accelX * alpha;
        _smoothedDeltaY = _smoothedDeltaY * (1f - alpha) + accelY * alpha;

        // Akumulator sub-pikselowy zapobiegający utracie mikroruchów
        _subpixelX += _smoothedDeltaX;
        _subpixelY += _smoothedDeltaY;

        var stepX = (int)MathF.Truncate(_subpixelX);
        var stepY = (int)MathF.Truncate(_subpixelY);

        _subpixelX -= stepX;
        _subpixelY -= stepY;

        return new AirMouseOutput(
            IsActive: true,
            IsScrollMode: false,
            DeltaX: stepX,
            DeltaY: stepY,
            ScrollDelta: 0);
    }

    private static float ApplySoftDeadband(float value, float deadband)
    {
        var abs = MathF.Abs(value);
        if (abs <= deadband) return 0f;
        // Płynne przejście soft-knee ponad martwą strefą
        var excess = abs - deadband;
        return MathF.Sign(value) * excess;
    }

    private static float ApplyVelocityCurve(float angularVelocityDps)
    {
        var abs = MathF.Abs(angularVelocityDps);
        if (abs <= 0f) return 0f;
        // Zoptymalizowana balistyka: precyzja piksel po pikselu przy wolnym ruchu + płynne przyspieszenie przy zamachu
        var speed = abs * 0.20f + (abs * abs) * 0.0030f;
        return MathF.Sign(angularVelocityDps) * speed;
    }
}
