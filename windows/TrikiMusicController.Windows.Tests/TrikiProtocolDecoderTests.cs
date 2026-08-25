using System.Buffers.Binary;
using TrikiMusicController_Windows.Core;

namespace TrikiMusicController.Windows.Tests;

public sealed class TrikiProtocolDecoderTests
{
    [Fact]
    public void FragmentedNotification_DecodesSignedLittleEndianFrame()
    {
        var decoder = new TrikiProtocolDecoder(startupFramesToDiscard: 0);
        var frame = Frame(1, -100, 200, -300, 2048, 0, -2048);

        Assert.Empty(decoder.Decode(frame.AsSpan(0, 5), 1_000_000_000));
        var result = Assert.Single(decoder.Decode(frame.AsSpan(5), 1_020_000_000));

        Assert.InRange(result.GyroscopeDps.X, -7.001f, -6.999f);
        Assert.InRange(result.GyroscopeDps.Y, 13.999f, 14.001f);
        Assert.InRange(result.AccelerometerG.X, 0.9999f, 1.0001f);
        Assert.InRange(result.AccelerometerG.Z, -1.0001f, -0.9999f);
        Assert.Equal(1, result.Status);
    }

    [Fact]
    public void GarbageBeforeFrame_IsDroppedAndCounted()
    {
        var decoder = new TrikiProtocolDecoder(startupFramesToDiscard: 0);
        var frame = Frame(0, 0, 0, 0, 0, 0, -2048);
        var packet = new byte[] { 0x7F, 0x55, 0x01 }.Concat(frame).ToArray();

        Assert.Single(decoder.Decode(packet, 1_000_000_000));
        Assert.Equal(3, decoder.Statistics.DroppedBytes);
    }

    private static byte[] Frame(byte status, short gx, short gy, short gz, short ax, short ay, short az)
    {
        var frame = new byte[TrikiProtocol.FrameLength];
        frame[0] = TrikiProtocol.FrameHeader;
        frame[1] = status;
        BinaryPrimitives.WriteInt16LittleEndian(frame.AsSpan(2, 2), gx);
        BinaryPrimitives.WriteInt16LittleEndian(frame.AsSpan(4, 2), gy);
        BinaryPrimitives.WriteInt16LittleEndian(frame.AsSpan(6, 2), gz);
        BinaryPrimitives.WriteInt16LittleEndian(frame.AsSpan(8, 2), ax);
        BinaryPrimitives.WriteInt16LittleEndian(frame.AsSpan(10, 2), ay);
        BinaryPrimitives.WriteInt16LittleEndian(frame.AsSpan(12, 2), az);
        return frame;
    }
}
