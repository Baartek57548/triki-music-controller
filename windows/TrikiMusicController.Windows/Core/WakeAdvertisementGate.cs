namespace TrikiMusicController_Windows.Core;

public sealed class WakeAdvertisementGate
{
    public const long DefaultRequiredSilenceNanos = 5_000_000_000;
    public const long DefaultMaximumWaitNanos = 15_000_000_000;

    private readonly object _sync = new();
    private readonly long _requiredSilenceNanos;
    private readonly long _maximumWaitNanos;
    private long? _waitingSinceNanos;
    private long? _lastAdvertisementNanos;
    private bool _armed;

    public WakeAdvertisementGate(
        long requiredSilenceNanos = DefaultRequiredSilenceNanos,
        long maximumWaitNanos = DefaultMaximumWaitNanos)
    {
        if (requiredSilenceNanos <= 0) throw new ArgumentOutOfRangeException(nameof(requiredSilenceNanos));
        if (maximumWaitNanos < requiredSilenceNanos) throw new ArgumentOutOfRangeException(nameof(maximumWaitNanos));
        _requiredSilenceNanos = requiredSilenceNanos;
        _maximumWaitNanos = maximumWaitNanos;
    }

    public bool IsArmed
    {
        get
        {
            lock (_sync) return _armed;
        }
    }

    public void Reset(long nowNanos, bool waitForSilence = true)
    {
        lock (_sync)
        {
            _lastAdvertisementNanos = nowNanos >= 0 ? nowNanos : null;
            _waitingSinceNanos = _lastAdvertisementNanos;
            _armed = !waitForSilence && nowNanos >= 0;
        }
    }

    public bool ObserveAdvertisement(long nowNanos)
    {
        lock (_sync)
        {
            if (nowNanos < 0 || _lastAdvertisementNanos is not long previous || nowNanos < previous)
            {
                _lastAdvertisementNanos = nowNanos >= 0 ? nowNanos : null;
                _waitingSinceNanos = _lastAdvertisementNanos;
                _armed = false;
                return false;
            }
            // Some firmware advertises continuously after GATT closes, so silence cannot be mandatory forever.
            var mayConnect = _armed || nowNanos - previous >= _requiredSilenceNanos ||
                (_waitingSinceNanos is long started && nowNanos - started >= _maximumWaitNanos);
            _lastAdvertisementNanos = nowNanos;
            return mayConnect;
        }
    }

    public bool TryArm(long nowNanos)
    {
        lock (_sync)
        {
            if (_lastAdvertisementNanos is not long previous) return false;
            if (nowNanos < previous)
            {
                _lastAdvertisementNanos = nowNanos >= 0 ? nowNanos : null;
                _waitingSinceNanos = _lastAdvertisementNanos;
                _armed = false;
                return false;
            }
            if (_armed || (nowNanos - previous < _requiredSilenceNanos &&
                (_waitingSinceNanos is not long started || nowNanos - started < _maximumWaitNanos))) return false;
            _armed = true;
            return true;
        }
    }
}
