namespace TrikiMusicController_Windows.Models;

public readonly record struct Vector3f(float X, float Y, float Z)
{
    public float Magnitude => MathF.Sqrt(X * X + Y * Y + Z * Z);

    public static Vector3f operator -(Vector3f left, Vector3f right) =>
        new(left.X - right.X, left.Y - right.Y, left.Z - right.Z);
}

public readonly record struct RawVector3(short X, short Y, short Z);

public sealed record TrikiSensorData(
    long FrameIndex,
    long TimestampNanos,
    Vector3f GyroscopeDps,
    Vector3f AccelerometerG,
    RawVector3 RawGyroscope,
    RawVector3 RawAccelerometer,
    int Status);

public readonly record struct OrientationData(float Pitch, float Roll, float Yaw);

public sealed record FilteredSensorData(
    TrikiSensorData Source,
    Vector3f GyroscopeDps,
    Vector3f AccelerometerG,
    OrientationData Orientation)
{
    public float AccelerationMagnitude => AccelerometerG.Magnitude;
    public float GyroscopeMagnitude => GyroscopeDps.Magnitude;
}

public sealed record CalibrationProfile(
    float AccelerometerBiasX = 0,
    float AccelerometerBiasY = 0,
    float AccelerometerBiasZ = 0,
    float GyroscopeBiasX = 0,
    float GyroscopeBiasY = 0,
    float GyroscopeBiasZ = 0,
    float NeutralPitch = 0,
    float NeutralRoll = 0,
    float AccelerometerNoise = 0,
    float GyroscopeNoise = 0,
    int SampleCount = 0);
