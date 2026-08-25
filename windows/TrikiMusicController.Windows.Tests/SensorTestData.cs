using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

internal static class SensorTestData
{
    public const long SamplePeriodNanos = 20_000_000;

    public static FilteredSensorData Filtered(
        long timestampNanos,
        Vector3f accelerometer,
        Vector3f? gyroscope = null,
        int status = 0)
    {
        var gyro = gyroscope ?? new Vector3f(0, 0, 0);
        var source = new TrikiSensorData(
            timestampNanos / SamplePeriodNanos,
            timestampNanos,
            gyro,
            accelerometer,
            new RawVector3(0, 0, 0),
            new RawVector3(0, 0, 0),
            status);
        return new FilteredSensorData(source, gyro, accelerometer, default);
    }
}
