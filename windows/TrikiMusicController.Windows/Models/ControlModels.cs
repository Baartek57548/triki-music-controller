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

public sealed record MediaActionOption(MediaAction Action, string DisplayName)
{
    public string Name => DisplayName;
    public override string ToString() => DisplayName;
}

public sealed record ThemeOption(string Value, string DisplayName)
{
    public string Name => DisplayName;
    public override string ToString() => DisplayName;
}

public sealed record MultiDeviceArbitrationOption(MultiDeviceArbitrationMode Mode, string DisplayName)
{
    public string Name => DisplayName;
    public override string ToString() => DisplayName;
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
    bool AccelerationStable,
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

public enum RotationGestureDirection
{
    Right,
    Left,
}

public static class RotationGestureDirectionActions
{
    public static MediaAction ToInvertedCapsuleNavigationAction(this RotationGestureDirection direction) => direction switch
    {
        RotationGestureDirection.Left => MediaAction.Next,
        RotationGestureDirection.Right => MediaAction.Previous,
        _ => MediaAction.None,
    };
}

public enum HoldGesturePhase
{
    Idle,
    Holding,
    Ready,
    Tracking,
    Completing,
    Rearming,
    Triggered,
}

public sealed record HoldArcGestureResult(
    RatingGestureAction? Action,
    RatingGestureAction? Direction,
    HoldGesturePhase Phase,
    float HoldProgress,
    bool FaceDown,
    float EstimatedHorizontalDisplacementMeters,
    float EstimatedArcDepthMeters);

public sealed record FullRotationGestureResult(
    bool Triggered,
    RotationGestureDirection? Direction,
    HoldGesturePhase Phase,
    float StabilizationProgress,
    bool FaceDown,
    float EstimatedRotationDegrees,
    float GyroscopeZDps);
