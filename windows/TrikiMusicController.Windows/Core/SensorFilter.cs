using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed class SensorFilter
{
    private const float ComplementaryAlpha = 0.96f;
    private const float ReliableAccelerationMinimum = 0.72f;
    private const float ReliableAccelerationMaximum = 1.28f;
    private const float DefaultDeltaSeconds = 0.0192f;
    private const long MinimumDeltaNanos = 1_000_000;
    private const long MaximumDeltaNanos = 100_000_000;
    private const float MinimumGyroscopeNoiseFloorDps = 2.5f;
    private const float GyroscopeNoiseMultiplier = 2.8f;
    private readonly float _filterAlpha;
    private readonly MedianOfThreeVectorFilter _gyroscopeMedian = new();
    private readonly MedianOfThreeVectorFilter _accelerometerMedian = new();
    private Vector3f? _filteredGyroscope;
    private Vector3f? _filteredAccelerometer;
    private OrientationData _orientation;
    private long? _previousTimestampNanos;

    public SensorFilter(float filterAlpha = 0.32f)
    {
        if (!float.IsFinite(filterAlpha) || filterAlpha is < 0.02f or > 1f)
            throw new ArgumentOutOfRangeException(nameof(filterAlpha));
        _filterAlpha = filterAlpha;
    }

    public void Reset()
    {
        _filteredGyroscope = null;
        _filteredAccelerometer = null;
        _orientation = default;
        _previousTimestampNanos = null;
        _gyroscopeMedian.Reset();
        _accelerometerMedian.Reset();
    }

    public FilteredSensorData Process(TrikiSensorData sample, CalibrationProfile calibration)
    {
        var calibratedGyroscope = sample.GyroscopeDps - new Vector3f(
            calibration.GyroscopeBiasX, calibration.GyroscopeBiasY, calibration.GyroscopeBiasZ);
        var calibratedAccelerometer = sample.AccelerometerG - new Vector3f(
            calibration.AccelerometerBiasX, calibration.AccelerometerBiasY, calibration.AccelerometerBiasZ);

        // Median-of-three rejects isolated radio/IMU spikes before low-pass filtering can smear them.
        var medianGyroscope = _gyroscopeMedian.Process(calibratedGyroscope);
        var medianAccelerometer = _accelerometerMedian.Process(calibratedAccelerometer);
        var noiseFloor = Math.Max(MinimumGyroscopeNoiseFloorDps, calibration.GyroscopeNoise * GyroscopeNoiseMultiplier);
        var stabilizedGyroscope = new Vector3f(
            Math.Abs(medianGyroscope.X) <= noiseFloor ? 0 : medianGyroscope.X,
            Math.Abs(medianGyroscope.Y) <= noiseFloor ? 0 : medianGyroscope.Y,
            Math.Abs(medianGyroscope.Z) <= noiseFloor ? 0 : medianGyroscope.Z);
        _filteredGyroscope = LowPass(_filteredGyroscope, stabilizedGyroscope, _filterAlpha);
        _filteredAccelerometer = LowPass(_filteredAccelerometer, medianAccelerometer, _filterAlpha);

        var deltaSeconds = _previousTimestampNanos is long previous
            ? Math.Clamp(sample.TimestampNanos - previous, MinimumDeltaNanos, MaximumDeltaNanos) / 1_000_000_000f
            : DefaultDeltaSeconds;
        _previousTimestampNanos = sample.TimestampNanos;
        _orientation = UpdateOrientation(_orientation, _filteredGyroscope.Value, _filteredAccelerometer.Value, calibration, deltaSeconds);
        return new FilteredSensorData(sample, _filteredGyroscope.Value, _filteredAccelerometer.Value, _orientation);
    }

    private static Vector3f LowPass(Vector3f? previous, Vector3f current, float alpha) => previous is not Vector3f value
        ? current
        : new Vector3f(
            value.X + alpha * (current.X - value.X),
            value.Y + alpha * (current.Y - value.Y),
            value.Z + alpha * (current.Z - value.Z));

    private static OrientationData UpdateOrientation(
        OrientationData previous,
        Vector3f gyroscope,
        Vector3f accelerometer,
        CalibrationProfile calibration,
        float deltaSeconds)
    {
        var accelerationNorm = Math.Max(accelerometer.Magnitude, 0.0001f);
        var accelerationPitch = NormalizeDegrees(
            RadiansToDegrees(MathF.Atan2(-accelerometer.X, MathF.Sqrt(accelerometer.Y * accelerometer.Y + accelerometer.Z * accelerometer.Z))) -
            calibration.NeutralPitch);
        var accelerationRoll = NormalizeDegrees(
            RadiansToDegrees(MathF.Atan2(accelerometer.Y, -accelerometer.Z)) - calibration.NeutralRoll);
        var accelerometerReliable = accelerationNorm is >= ReliableAccelerationMinimum and <= ReliableAccelerationMaximum;
        var gyroPitch = NormalizeDegrees(previous.Pitch + gyroscope.Y * deltaSeconds);
        var gyroRoll = NormalizeDegrees(previous.Roll - gyroscope.X * deltaSeconds);
        var pitch = accelerometerReliable ? ComplementaryAngle(gyroPitch, accelerationPitch) : gyroPitch;
        var roll = accelerometerReliable ? ComplementaryAngle(gyroRoll, accelerationRoll) : gyroRoll;
        return new OrientationData(
            NormalizeDegrees(pitch),
            NormalizeDegrees(roll),
            NormalizeDegrees(previous.Yaw - gyroscope.Z * deltaSeconds));
    }

    private static float RadiansToDegrees(float radians) => radians * 180f / MathF.PI;

    private static float NormalizeDegrees(float value)
    {
        while (value > 180f) value -= 360f;
        while (value < -180f) value += 360f;
        return value;
    }

    private static float ComplementaryAngle(float gyroscopeAngle, float accelerometerAngle) =>
        NormalizeDegrees(gyroscopeAngle + (1f - ComplementaryAlpha) * NormalizeDegrees(accelerometerAngle - gyroscopeAngle));

    private sealed class MedianOfThreeVectorFilter
    {
        private Vector3f? _older;
        private Vector3f? _previous;

        public void Reset()
        {
            _older = null;
            _previous = null;
        }

        public Vector3f Process(Vector3f current)
        {
            var first = _older;
            var second = _previous;
            _older = second;
            _previous = current;
            if (first is not Vector3f firstValue || second is not Vector3f secondValue) return current;
            return new Vector3f(
                Median(firstValue.X, secondValue.X, current.X),
                Median(firstValue.Y, secondValue.Y, current.Y),
                Median(firstValue.Z, secondValue.Z, current.Z));
        }

        private static float Median(float first, float second, float third) =>
            first + second + third - Math.Min(first, Math.Min(second, third)) - Math.Max(first, Math.Max(second, third));
    }
}
