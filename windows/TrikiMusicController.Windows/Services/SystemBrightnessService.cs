using System.Diagnostics;
using System.Management;
using System.Runtime.InteropServices;

namespace TrikiMusicController_Windows.Services;

public sealed class SystemBrightnessService
{
    private byte _cachedBrightness = 60;
    private DateTimeOffset _lastErrorTime = DateTimeOffset.MinValue;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct PHYSICAL_MONITOR
    {
        public IntPtr hPhysicalMonitor;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szPhysicalMonitorDescription;
    }

    private delegate bool MonitorEnumProc(IntPtr hMonitor, IntPtr hdcMonitor, IntPtr lprcMonitor, IntPtr dwData);

    [DllImport("user32.dll")]
    private static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr lprcClip, MonitorEnumProc lpfnEnum, IntPtr dwData);

    [DllImport("dxva2.dll", SetLastError = true)]
    private static extern bool GetNumberOfPhysicalMonitorsFromHMONITOR(IntPtr hMonitor, out uint pdwNumberOfPhysicalMonitors);

    [DllImport("dxva2.dll", SetLastError = true)]
    private static extern bool GetPhysicalMonitorsFromHMONITOR(IntPtr hMonitor, uint dwPhysicalMonitorArraySize, [Out] PHYSICAL_MONITOR[] pPhysicalMonitorArray);

    [DllImport("dxva2.dll", SetLastError = true)]
    private static extern bool DestroyPhysicalMonitors(uint dwPhysicalMonitorArraySize, [In] PHYSICAL_MONITOR[] pPhysicalMonitorArray);

    [DllImport("dxva2.dll", SetLastError = true)]
    private static extern bool GetMonitorBrightness(IntPtr hMonitor, out uint pdwMinimumBrightness, out uint pdwCurrentBrightness, out uint pdwMaximumBrightness);

    [DllImport("dxva2.dll", SetLastError = true)]
    private static extern bool SetMonitorBrightness(IntPtr hMonitor, uint dwNewBrightness);

    public byte GetBrightness()
    {
        if (TryGetWmiBrightness(out var wmiBrightness))
        {
            _cachedBrightness = wmiBrightness;
            return wmiBrightness;
        }

        if (TryGetDdcCiBrightness(out var ddcBrightness))
        {
            _cachedBrightness = ddcBrightness;
            return ddcBrightness;
        }

        return _cachedBrightness;
    }

    public void SetBrightness(byte percent)
    {
        percent = (byte)Math.Clamp((int)percent, 0, 100);
        _cachedBrightness = percent;

        var setViaWmi = TrySetWmiBrightness(percent);
        var setViaDdc = TrySetDdcCiBrightness(percent);

        if (!setViaWmi && !setViaDdc)
        {
            if (DateTimeOffset.Now - _lastErrorTime > TimeSpan.FromSeconds(5))
            {
                Debug.WriteLine($"Nie udało się ustawić jasności {percent}% ani przez WMI, ani przez DDC/CI.");
                _lastErrorTime = DateTimeOffset.Now;
            }
        }
    }

    public void StepBrightness(float deltaPercent)
    {
        var current = GetBrightness();
        var next = (byte)Math.Clamp((int)MathF.Round(current + deltaPercent), 0, 100);
        SetBrightness(next);
    }

    private static bool TryGetWmiBrightness(out byte brightness)
    {
        brightness = 60;
        try
        {
            using var searcher = new ManagementObjectSearcher(@"root\wmi", "SELECT CurrentBrightness FROM WmiMonitorBrightness");
            foreach (ManagementObject obj in searcher.Get())
            {
                var val = obj["CurrentBrightness"];
                if (val is byte b)
                {
                    brightness = b;
                    return true;
                }
                if (val is int i)
                {
                    brightness = (byte)Math.Clamp(i, 0, 100);
                    return true;
                }
                if (val is uint u)
                {
                    brightness = (byte)Math.Clamp((int)u, 0, 100);
                    return true;
                }
            }
        }
        catch (Exception error)
        {
            Debug.WriteLine($"WMI Monitor Brightness get: {error.Message}");
        }
        return false;
    }

    private static bool TrySetWmiBrightness(byte percent)
    {
        var success = false;
        try
        {
            using var searcher = new ManagementObjectSearcher(@"root\wmi", "SELECT * FROM WmiMonitorBrightnessMethods");
            foreach (ManagementObject obj in searcher.Get())
            {
                // Timeout w sekundach (1u) oraz docelowy procent jasności (uint8 / byte)
                obj.InvokeMethod("WmiSetBrightness", [1u, (byte)percent]);
                success = true;
            }
        }
        catch (Exception error)
        {
            Debug.WriteLine($"WMI Monitor Brightness set: {error.Message}");
        }
        return success;
    }

    private static bool TryGetDdcCiBrightness(out byte brightness)
    {
        byte foundBrightness = 60;
        var success = false;

        try
        {
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hMonitor, IntPtr _, IntPtr _, IntPtr _) =>
            {
                if (!GetNumberOfPhysicalMonitorsFromHMONITOR(hMonitor, out var count) || count == 0)
                    return true;

                var monitors = new PHYSICAL_MONITOR[count];
                if (GetPhysicalMonitorsFromHMONITOR(hMonitor, count, monitors))
                {
                    try
                    {
                        foreach (var mon in monitors)
                        {
                            if (GetMonitorBrightness(mon.hPhysicalMonitor, out var min, out var cur, out var max) && max > min)
                            {
                                var pct = (float)(cur - min) / (max - min) * 100f;
                                foundBrightness = (byte)Math.Clamp((int)MathF.Round(pct), 0, 100);
                                success = true;
                                return false; // Zatrzymanie na pierwszym monitorze
                            }
                        }
                    }
                    finally
                    {
                        DestroyPhysicalMonitors(count, monitors);
                    }
                }
                return true;
            }, IntPtr.Zero);
        }
        catch (Exception error)
        {
            Debug.WriteLine($"DDC/CI Brightness get: {error.Message}");
        }

        brightness = foundBrightness;
        return success;
    }

    private static bool TrySetDdcCiBrightness(byte percent)
    {
        var anySuccess = false;

        try
        {
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hMonitor, IntPtr _, IntPtr _, IntPtr _) =>
            {
                if (!GetNumberOfPhysicalMonitorsFromHMONITOR(hMonitor, out var count) || count == 0)
                    return true;

                var monitors = new PHYSICAL_MONITOR[count];
                if (GetPhysicalMonitorsFromHMONITOR(hMonitor, count, monitors))
                {
                    try
                    {
                        foreach (var mon in monitors)
                        {
                            if (GetMonitorBrightness(mon.hPhysicalMonitor, out var min, out var _, out var max) && max > min)
                            {
                                var target = min + (uint)MathF.Round((max - min) * (percent / 100f));
                                if (SetMonitorBrightness(mon.hPhysicalMonitor, target))
                                {
                                    anySuccess = true;
                                }
                            }
                        }
                    }
                    finally
                    {
                        DestroyPhysicalMonitors(count, monitors);
                    }
                }
                return true;
            }, IntPtr.Zero);
        }
        catch (Exception error)
        {
            Debug.WriteLine($"DDC/CI Brightness set: {error.Message}");
        }

        return anySuccess;
    }
}
