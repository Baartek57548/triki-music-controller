namespace TrikiMusicController_Windows.Models;

public enum ButtonClickType
{
    Single = 1,
    Double = 2,
    Triple = 3,
}

public enum MediaAction
{
    Play,
    Pause,
    PlayPause,
    Next,
    Previous,
    Like,
    Dislike,
    VolumeUp,
    VolumeDown,
    Mute,
    Unmute,
    Stop,
    None,
}

public static class MediaActionNames
{
    public static string DisplayName(this MediaAction action) => action switch
    {
        MediaAction.Play => "Odtwórz",
        MediaAction.Pause => "Wstrzymaj",
        MediaAction.PlayPause => "Odtwórz / wstrzymaj",
        MediaAction.Next => "Następny utwór",
        MediaAction.Previous => "Poprzedni utwór",
        MediaAction.Like => "Polub utwór",
        MediaAction.Dislike => "Odrzuć utwór",
        MediaAction.VolumeUp => "Głośniej",
        MediaAction.VolumeDown => "Ciszej",
        MediaAction.Mute => "Wycisz",
        MediaAction.Unmute => "Wyłącz wyciszenie",
        MediaAction.Stop => "Zatrzymaj",
        _ => "Brak akcji",
    };
}

public sealed record ButtonClickEvent(ButtonClickType Type, long TimestampNanos);

public sealed record MediaActionOption(MediaAction Action, string Name)
{
    public override string ToString() => Name;
}

public sealed record ThemeOption(string Value, string Name)
{
    public override string ToString() => Name;
}

public enum TrikiButtonProtocolMode
{
    Unknown,
    ButtonFlag,
    SequenceCounter,
}

public sealed record VolumeControlResult(
    MediaAction? Action,
    bool SensorValid,
    bool WithinTiltRange,
    bool TiltStable,
    float StabilizationProgress,
    bool Active,
    float TiltDegrees,
    float GyroscopeZDps);

public enum RatingGestureAction
{
    Like,
    Dislike,
}

public enum HoldGesturePhase
{
    Idle,
    Holding,
    Ready,
    Tracking,
    Rearming,
    Triggered,
}

public sealed record HoldVerticalGestureResult(
    RatingGestureAction? Action,
    RatingGestureAction? Direction,
    HoldGesturePhase Phase,
    float HoldProgress,
    float EstimatedDisplacementMeters);
