using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed class TrikiButtonInterpreter
{
    private const int MinimumProtocolObservations = 12;
    private const int MinimumRepeatedStatusRun = 4;
    private const long DebounceNanos = 18_000_000;
    private const long MinimumClickPressNanos = 25_000_000;
    private const long MaximumClickPressNanos = 2_000_000_000;
    private const long MultiClickTimeoutNanos = 450_000_000;
    private const long MaximumStreamGapNanos = 300_000_000;
    private long? _lastTimestampNanos;
    private int? _observedStatus;
    private int _observedRunLength;
    private int _longestObservedRun;
    private int _observationCount;
    private bool _stablePressed;
    private bool _candidatePressed;
    private long _candidateSinceNanos;
    private long? _pressedAtNanos;
    private int _pendingClickCount;
    private long? _clickDeadlineNanos;
    private bool _currentHoldConsumed;

    public TrikiButtonProtocolMode ProtocolMode { get; private set; } = TrikiButtonProtocolMode.Unknown;
    public bool IsPressed => ProtocolMode == TrikiButtonProtocolMode.ButtonFlag && _stablePressed;
    public bool ShouldSuppressMotionControl => ProtocolMode == TrikiButtonProtocolMode.ButtonFlag &&
        (_stablePressed || _candidatePressed || _pendingClickCount > 0);

    public bool ConsumeCurrentHold()
    {
        if (!IsPressed) return false;
        _currentHoldConsumed = true;
        _pendingClickCount = 0;
        _clickDeadlineNanos = null;
        return true;
    }

    public bool CheckAndConsumeHoldDuration(long now, long requiredDurationNanos)
    {
        if (!IsPressed || _currentHoldConsumed || _pressedAtNanos is not long pressedAt)
            return false;

        if (now - pressedAt >= requiredDurationNanos)
        {
            ConsumeCurrentHold();
            return true;
        }
        return false;
    }

    public void Reset()
    {
        ProtocolMode = TrikiButtonProtocolMode.Unknown;
        _lastTimestampNanos = null;
        _observedStatus = null;
        _observedRunLength = 0;
        _longestObservedRun = 0;
        _observationCount = 0;
        ClearInteraction();
    }

    public ButtonClickEvent? Process(TrikiSensorData sample)
    {
        var now = sample.TimestampNanos;
        if (_lastTimestampNanos is long previous && (now <= previous || now - previous > MaximumStreamGapNanos)) Reset();
        _lastTimestampNanos = now;

        if (ProtocolMode == TrikiButtonProtocolMode.Unknown)
        {
            ObserveProtocol(sample.Status, now);
            return null;
        }
        if (ProtocolMode == TrikiButtonProtocolMode.SequenceCounter) return null;
        if (sample.Status is < 0 or > 1)
        {
            ProtocolMode = TrikiButtonProtocolMode.SequenceCounter;
            ClearInteraction();
            return null;
        }
        return ProcessButtonState(sample.Status == 1, now);
    }

    private void ObserveProtocol(int status, long now)
    {
        if (status is < 0 or > 1)
        {
            ProtocolMode = TrikiButtonProtocolMode.SequenceCounter;
            ClearInteraction();
            return;
        }

        _observationCount++;
        if (_observedStatus == status) _observedRunLength++;
        else
        {
            _observedStatus = status;
            _observedRunLength = 1;
        }
        _longestObservedRun = Math.Max(_longestObservedRun, _observedRunLength);
        if (_observationCount < MinimumProtocolObservations || _longestObservedRun < MinimumRepeatedStatusRun) return;

        ProtocolMode = TrikiButtonProtocolMode.ButtonFlag;
        _stablePressed = status == 1;
        _candidatePressed = _stablePressed;
        _candidateSinceNanos = now;
        _pressedAtNanos = _stablePressed ? now : null;
        _pendingClickCount = 0;
        _clickDeadlineNanos = null;
        _currentHoldConsumed = false;
    }

    private ButtonClickEvent? ProcessButtonState(bool rawPressed, long now)
    {
        var completed = FinalizeExpiredSequence(rawPressed, now);
        if (rawPressed != _candidatePressed)
        {
            _candidatePressed = rawPressed;
            _candidateSinceNanos = now;
        }
        if (_candidatePressed == _stablePressed || now - _candidateSinceNanos < DebounceNanos) return completed;

        _stablePressed = _candidatePressed;
        if (_stablePressed)
        {
            _pressedAtNanos = now;
            _currentHoldConsumed = false;
        }
        else
        {
            completed ??= RegisterRelease(now);
        }
        return completed;
    }

    private ButtonClickEvent? FinalizeExpiredSequence(bool rawPressed, long now) =>
        _clickDeadlineNanos is long deadline && now >= deadline && !_stablePressed && !rawPressed
            ? CompletePendingSequence(now)
            : null;

    private ButtonClickEvent? RegisterRelease(long now)
    {
        var pressedAt = _pressedAtNanos;
        _pressedAtNanos = null;
        if (pressedAt is null) return null;
        if (_currentHoldConsumed)
        {
            _currentHoldConsumed = false;
            _pendingClickCount = 0;
            _clickDeadlineNanos = null;
            return null;
        }

        var duration = now - pressedAt.Value;
        if (duration is < MinimumClickPressNanos or > MaximumClickPressNanos)
        {
            _pendingClickCount = 0;
            _clickDeadlineNanos = null;
            return null;
        }
        _pendingClickCount++;
        if (_pendingClickCount >= 3) return CompletePendingSequence(now);
        _clickDeadlineNanos = now + MultiClickTimeoutNanos;
        return null;
    }

    private ButtonClickEvent? CompletePendingSequence(long now)
    {
        var type = _pendingClickCount switch
        {
            1 => ButtonClickType.Single,
            2 => ButtonClickType.Double,
            3 => ButtonClickType.Triple,
            _ => (ButtonClickType?)null,
        };
        _pendingClickCount = 0;
        _clickDeadlineNanos = null;
        _currentHoldConsumed = false;
        return type is ButtonClickType value ? new ButtonClickEvent(value, now) : null;
    }

    private void ClearInteraction()
    {
        _stablePressed = false;
        _candidatePressed = false;
        _candidateSinceNanos = 0;
        _pressedAtNanos = null;
        _pendingClickCount = 0;
        _clickDeadlineNanos = null;
        _currentHoldConsumed = false;
    }
}
