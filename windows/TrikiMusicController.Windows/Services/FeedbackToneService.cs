using System.Media;
using System.Text;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class FeedbackToneService : IDisposable
{
    private const int SampleRate = 24_000;
    private const double Amplitude = 0.18;
    private static readonly ToneStep[] LikeSequence = [new(660, 55), new(880, 65)];
    private static readonly ToneStep[] DislikeSequence = [new(520, 60), new(330, 70)];
    private readonly object _sync = new();
    private readonly TonePlayback? _likeTone;
    private readonly TonePlayback? _dislikeTone;
    private bool _disposed;

    public FeedbackToneService()
    {
        _likeTone = TryCreatePlayback(LikeSequence);
        _dislikeTone = TryCreatePlayback(DislikeSequence);
    }

    public void PlayRatingAction(MediaAction action)
    {
        var selected = action switch
        {
            MediaAction.Like => _likeTone,
            MediaAction.Dislike => _dislikeTone,
            _ => throw new ArgumentOutOfRangeException(nameof(action), action, "Dźwięk oceny wymaga akcji Like albo Dislike."),
        };
        if (selected is null) return;

        lock (_sync)
        {
            if (_disposed) return;
            try
            {
                _likeTone?.Stop();
                _dislikeTone?.Stop();
                selected.Play();
            }
            catch (InvalidOperationException error)
            {
                System.Diagnostics.Trace.WriteLine($"Nie udało się odtworzyć sygnału {action}: {error}");
            }
        }
    }

    public void Dispose()
    {
        lock (_sync)
        {
            if (_disposed) return;
            _disposed = true;
            _likeTone?.Dispose();
            _dislikeTone?.Dispose();
        }
    }

    internal static IReadOnlyList<ToneStep> SequenceFor(MediaAction action) => action switch
    {
        MediaAction.Like => LikeSequence,
        MediaAction.Dislike => DislikeSequence,
        _ => throw new ArgumentOutOfRangeException(nameof(action), action, "Sekwencja dźwięku istnieje wyłącznie dla Like i Dislike."),
    };

    internal static byte[] BuildWave(IReadOnlyList<ToneStep> sequence)
    {
        ArgumentNullException.ThrowIfNull(sequence);
        if (sequence.Count == 0) throw new ArgumentException("Sekwencja dźwięku nie może być pusta.", nameof(sequence));
        if (sequence.Any(step => step.FrequencyHz is < 80 or > 4_000 || step.DurationMilliseconds is < 20 or > 500))
            throw new ArgumentOutOfRangeException(nameof(sequence), "Parametry dźwięku wykraczają poza bezpieczny zakres.");

        var sampleCount = sequence.Sum(step => SampleCount(step.DurationMilliseconds));
        var dataLength = checked(sampleCount * sizeof(short));
        using var stream = new MemoryStream(44 + dataLength);
        using var writer = new BinaryWriter(stream, Encoding.ASCII, leaveOpen: true);
        writer.Write(Encoding.ASCII.GetBytes("RIFF"));
        writer.Write(36 + dataLength);
        writer.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
        writer.Write(16);
        writer.Write((short)1);
        writer.Write((short)1);
        writer.Write(SampleRate);
        writer.Write(SampleRate * sizeof(short));
        writer.Write((short)sizeof(short));
        writer.Write((short)16);
        writer.Write(Encoding.ASCII.GetBytes("data"));
        writer.Write(dataLength);

        var phase = 0d;
        foreach (var step in sequence)
        {
            var stepSamples = SampleCount(step.DurationMilliseconds);
            var fadeSamples = Math.Min(stepSamples / 4, SampleRate / 200);
            var phaseIncrement = 2d * Math.PI * step.FrequencyHz / SampleRate;
            for (var index = 0; index < stepSamples; index++)
            {
                var attack = fadeSamples == 0 ? 1d : Math.Min(1d, index / (double)fadeSamples);
                var release = fadeSamples == 0 ? 1d : Math.Min(1d, (stepSamples - 1 - index) / (double)fadeSamples);
                var envelope = Math.Min(attack, release);
                var sample = (short)Math.Round(Math.Sin(phase) * short.MaxValue * Amplitude * envelope);
                writer.Write(sample);
                phase += phaseIncrement;
            }
        }
        writer.Flush();
        return stream.ToArray();
    }

    private static TonePlayback? TryCreatePlayback(IReadOnlyList<ToneStep> sequence)
    {
        try
        {
            return new TonePlayback(BuildWave(sequence));
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException)
        {
            System.Diagnostics.Trace.WriteLine($"Nie udało się przygotować sygnału oceny utworu: {error}");
            return null;
        }
    }

    private static int SampleCount(int durationMilliseconds) =>
        checked((int)Math.Round(durationMilliseconds * SampleRate / 1_000d));

    internal readonly record struct ToneStep(int FrequencyHz, int DurationMilliseconds);

    private sealed class TonePlayback : IDisposable
    {
        private readonly MemoryStream _stream;
        private readonly SoundPlayer _player;

        public TonePlayback(byte[] wave)
        {
            _stream = new MemoryStream(wave, writable: false);
            _player = new SoundPlayer(_stream);
            _player.Load();
        }

        public void Play() => _player.Play();

        public void Stop() => _player.Stop();

        public void Dispose()
        {
            _player.Dispose();
            _stream.Dispose();
        }
    }
}
