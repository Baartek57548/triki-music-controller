using System.Diagnostics;
using System.Management;

namespace TrikiMusicController_Windows.Services;

public sealed class SystemBrightnessService
{
    private byte _cachedBrightness = 60;
    private bool _wmiAvailable = true;

    public byte GetBrightness()
    {
        if (!_wmiAvailable) return _cachedBrightness;

        try
        {
            using var searcher = new ManagementObjectSearcher(@"root\wmi", "SELECT CurrentBrightness FROM WmiMonitorBrightness");
            foreach (ManagementObject obj in searcher.Get())
            {
                var val = obj["CurrentBrightness"];
                if (val is byte b)
                {
                    _cachedBrightness = b;
                    return b;
                }
                if (val is int i)
                {
                    _cachedBrightness = (byte)Math.Clamp(i, 0, 100);
                    return _cachedBrightness;
                }
            }
        }
        catch (Exception error)
        {
            Debug.WriteLine($"WMI Monitor Brightness get niedostępne: {error.Message}");
            _wmiAvailable = false;
        }

        return _cachedBrightness;
    }

    public void SetBrightness(byte percent)
    {
        percent = (byte)Math.Clamp((int)percent, 0, 100);
        _cachedBrightness = percent;

        if (!_wmiAvailable) return;

        try
        {
            using var searcher = new ManagementObjectSearcher(@"root\wmi", "SELECT * FROM WmiMonitorBrightnessMethods");
            foreach (ManagementObject obj in searcher.Get())
            {
                obj.InvokeMethod("WmiSetBrightness", [uint.MaxValue, percent]);
            }
        }
        catch (Exception error)
        {
            Debug.WriteLine($"WMI Monitor Brightness set niedostępne: {error.Message}");
            _wmiAvailable = false;
        }
    }

    public void StepBrightness(float deltaPercent)
    {
        var current = GetBrightness();
        var next = (byte)Math.Clamp((int)MathF.Round(current + deltaPercent), 0, 100);
        SetBrightness(next);
    }
}
