using System.Buffers.Binary;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public static class TrikiProtocol
{
    public static readonly Guid NusServiceUuid = Guid.Parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static readonly Guid NusRxUuid = Guid.Parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static readonly Guid NusTxUuid = Guid.Parse("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static readonly Guid LedUuid = Guid.Parse("6e400004-b5a3-f393-e0a9-e50e24dcca9e");
    public static readonly Guid BatteryServiceUuid = BluetoothUuid(0x180F);
    public static readonly Guid BatteryLevelUuid = BluetoothUuid(0x2A19);

    public static readonly byte[] StartStreamCommand = [0x20, 0x10, 0x00, 0xD0, 0x07, 0x34, 0x00, 0x03];

    public const int FrameLength = 14;
    public const byte FrameHeader = 0x22;
    public const float GyroscopeLsbPerDps = 1f / 0.070f;
    public const float AccelerometerLsbPerG = 2048f;

    private static Guid BluetoothUuid(int value) =>
        Guid.Parse($"0000{value:x4}-0000-1000-8000-00805f9b34fb");
}

public sealed record DecoderStatistics(long DecodedFrames, long DiscardedStartupFrames, long DroppedBytes);

public sealed class TrikiProtocolDecoder
{
    private const long ApproximateSamplePeriodNanos = 19_230_769;
    private const long MinimumMonotonicStepNanos = 1_000;
    private readonly int _startupFramesToDiscard;
    private readonly float _gyroscopeScale;
    private readonly float _accelerometerScale;
    private readonly List<byte> _buffer = new(64);
    private long _frameIndex;
    private long _discarded;
    private long _dropped;
    private long _lastTimestampNanos = long.MinValue;

    public TrikiProtocolDecoder(
        int startupFramesToDiscard = 20,
        float gyroscopeScale = TrikiProtocol.GyroscopeLsbPerDps,
        float accelerometerScale = TrikiProtocol.AccelerometerLsbPerG)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(startupFramesToDiscard);
        if (!float.IsFinite(gyroscopeScale) || gyroscopeScale <= 0) throw new ArgumentOutOfRangeException(nameof(gyroscopeScale));
        if (!float.IsFinite(accelerometerScale) || accelerometerScale <= 0) throw new ArgumentOutOfRangeException(nameof(accelerometerScale));
        _startupFramesToDiscard = startupFramesToDiscard;
        _gyroscopeScale = gyroscopeScale;
        _accelerometerScale = accelerometerScale;
    }

    public DecoderStatistics Statistics => new(_frameIndex, _discarded, _dropped);

    public void Reset()
    {
        _buffer.Clear();
        _frameIndex = 0;
        _discarded = 0;
        _dropped = 0;
        _lastTimestampNanos = long.MinValue;
    }

    public IReadOnlyList<TrikiSensorData> Decode(ReadOnlySpan<byte> notification, long receivedAtNanos)
    {
        if (notification.IsEmpty) return [];
        foreach (var value in notification) _buffer.Add(value);

        var frames = new List<byte[]>();
        while (true)
        {
            var headerIndex = FindHeader();
            if (headerIndex < 0)
            {
                RetainPossibleSplitHeader();
                break;
            }

            if (headerIndex > 0)
            {
                _dropped += headerIndex;
                _buffer.RemoveRange(0, headerIndex);
            }

            if (_buffer.Count < TrikiProtocol.FrameLength) break;
            frames.Add(_buffer.GetRange(0, TrikiProtocol.FrameLength).ToArray());
            _buffer.RemoveRange(0, TrikiProtocol.FrameLength);
        }

        if (frames.Count == 0) return [];
        var result = new List<TrikiSensorData>(frames.Count);
        var firstTimestamp = receivedAtNanos - (frames.Count - 1L) * ApproximateSamplePeriodNanos;
        for (var index = 0; index < frames.Count; index++)
        {
            if (_discarded < _startupFramesToDiscard)
            {
                _discarded++;
                continue;
            }

            var interpolated = firstTimestamp + index * ApproximateSamplePeriodNanos;
            var timestamp = _lastTimestampNanos == long.MinValue
                ? interpolated
                : Math.Max(interpolated, _lastTimestampNanos + MinimumMonotonicStepNanos);
            _lastTimestampNanos = timestamp;
            result.Add(DecodeFrame(frames[index], timestamp, _frameIndex++));
        }

        return result;
    }

    private TrikiSensorData DecodeFrame(ReadOnlySpan<byte> frame, long timestampNanos, long index)
    {
        if (frame.Length != TrikiProtocol.FrameLength || frame[0] != TrikiProtocol.FrameHeader || frame[1] > 0x0F)
        {
            throw new InvalidDataException("Nieprawidłowa ramka protokołu Triki.");
        }

        var payload = frame[2..];
        var gx = BinaryPrimitives.ReadInt16LittleEndian(payload[0..2]);
        var gy = BinaryPrimitives.ReadInt16LittleEndian(payload[2..4]);
        var gz = BinaryPrimitives.ReadInt16LittleEndian(payload[4..6]);
        var ax = BinaryPrimitives.ReadInt16LittleEndian(payload[6..8]);
        var ay = BinaryPrimitives.ReadInt16LittleEndian(payload[8..10]);
        var az = BinaryPrimitives.ReadInt16LittleEndian(payload[10..12]);
        return new TrikiSensorData(
            index,
            timestampNanos,
            new Vector3f(gx / _gyroscopeScale, gy / _gyroscopeScale, gz / _gyroscopeScale),
            new Vector3f(ax / _accelerometerScale, ay / _accelerometerScale, az / _accelerometerScale),
            new RawVector3(gx, gy, gz),
            new RawVector3(ax, ay, az),
            frame[1]);
    }

    private int FindHeader()
    {
        for (var index = 0; index < _buffer.Count - 1; index++)
        {
            if (_buffer[index] == TrikiProtocol.FrameHeader && _buffer[index + 1] <= 0x0F) return index;
        }
        return -1;
    }

    private void RetainPossibleSplitHeader()
    {
        if (_buffer.Count == 0) return;
        var keepLast = _buffer[^1] == TrikiProtocol.FrameHeader;
        var last = _buffer[^1];
        _dropped += _buffer.Count - (keepLast ? 1 : 0);
        _buffer.Clear();
        if (keepLast) _buffer.Add(last);
    }
}
