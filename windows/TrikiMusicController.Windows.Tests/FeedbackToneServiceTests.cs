using System.Text;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController_Windows.Tests;

public sealed class FeedbackToneServiceTests
{
    [Fact]
    public void LikeUsesShortAscendingConfirmation()
    {
        var sequence = FeedbackToneService.SequenceFor(MediaAction.Like);

        Assert.Equal(2, sequence.Count);
        Assert.True(sequence[0].FrequencyHz < sequence[1].FrequencyHz);
        Assert.InRange(sequence.Sum(step => step.DurationMilliseconds), 80, 180);
    }

    [Fact]
    public void DislikeUsesShortDescendingConfirmation()
    {
        var sequence = FeedbackToneService.SequenceFor(MediaAction.Dislike);

        Assert.Equal(2, sequence.Count);
        Assert.True(sequence[0].FrequencyHz > sequence[1].FrequencyHz);
        Assert.InRange(sequence.Sum(step => step.DurationMilliseconds), 80, 180);
    }

    [Fact]
    public void GeneratedSignalIsValidMonoPcmWave()
    {
        var wave = FeedbackToneService.BuildWave(FeedbackToneService.SequenceFor(MediaAction.Like));

        Assert.Equal("RIFF", Encoding.ASCII.GetString(wave, 0, 4));
        Assert.Equal("WAVE", Encoding.ASCII.GetString(wave, 8, 4));
        Assert.Equal("data", Encoding.ASCII.GetString(wave, 36, 4));
        Assert.Equal(wave.Length - 8, BitConverter.ToInt32(wave, 4));
        Assert.Equal(wave.Length - 44, BitConverter.ToInt32(wave, 40));
    }

    [Fact]
    public void NonRatingActionHasNoConfirmationSequence()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => FeedbackToneService.SequenceFor(MediaAction.PlayPause));
    }
}
