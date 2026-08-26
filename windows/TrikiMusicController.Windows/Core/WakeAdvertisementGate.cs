namespace TrikiMusicController_Windows.Core;

public sealed class WakeAdvertisementGate
{
    public const long DefaultRequiredSilenceNanos = 5_000_000_000;

    private readonly object _sync = new();
    private readonly long _requiredSilenceNanos;
    private long? _lastAdvertisementNanos;
    private bool _armed;

    public WakeAdvertisementGate(long requiredSilenceNanos = DefaultRequiredSilenceNanos)
    {
        if (requiredSilenceNanos <= 0) throw new ArgumentOutOfRangeException(nameof(requiredSilenceNanos));
        _requiredSilenceNanos = requiredSilenceNanos;
    }

    public bool IsArmed
    {
        get
        {
            lock (_sync) return _armed;
        }
    }

    public void Reset(long nowNanos)
    {
        lock (_sync)
        {
            _lastAdvertisementNanos = nowNanos >= 0 ? nowNanos : null;
            _armed = false;
        }
    }

    public bool ObserveAdvertisement(long nowNanos)
    {
        lock (_sync)
        {
            if (nowNanos < 0 || _lastAdvertisementNanos is not long previous || nowNanos < previous)
            {
                _lastAdvertisementNanos = nowNanos >= 0 ? nowNanos : null;
                _armed = false;
                return false;
            }
            var mayConnect = _armed || nowNanos - previous >= _requiredSilenceNanos;
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
                _armed = false;
                return false;
            }
            if (_armed || nowNanos - previous < _requiredSilenceNanos) return false;
            _armed = true;
            return true;
        }
    }
}
