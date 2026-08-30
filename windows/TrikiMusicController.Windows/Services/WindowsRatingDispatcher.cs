using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public static class WindowsRatingDispatcher
{
    private const int VK_SHIFT = 0x10;
    private const int VK_CONTROL = 0x11;
    private const int VK_MENU = 0x12; // ALT
    private const int VK_L = 0x4C;
    private const int VK_D = 0x44;
    private const int VK_B = 0x42;
    private const int VK_F = 0x46;

    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUT
    {
        [FieldOffset(0)]
        public uint type;
        [FieldOffset(8)]
        public KEYBDINPUT ki;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    public static async Task<(bool Succeeded, string Message)> DispatchRatingAsync(
        MediaAction action,
        string? sourceAppId)
    {
        if (action is not (MediaAction.Like or MediaAction.Dislike))
            return (false, "Akcja nie jest oceną utworu.");

        await Task.Yield();
        var appIdentifier = sourceAppId ?? string.Empty;

        // 1. YouTube Music (th-ch desktop app lub wersja PWA / przeglądarkowa)
        if (appIdentifier.Contains("youtube-music", StringComparison.OrdinalIgnoreCase) ||
            appIdentifier.Contains("th-ch", StringComparison.OrdinalIgnoreCase))
        {
            var sent = SendYouTubeMusicRating(action);
            if (sent)
            {
                return (true, action == MediaAction.Like
                    ? "Polubiono utwór w YouTube Music (Like)"
                    : "Odrzucono utwór w YouTube Music (Dislike)");
            }
        }

        // 2. Spotify Desktop App
        if (appIdentifier.Contains("spotify", StringComparison.OrdinalIgnoreCase))
        {
            if (action == MediaAction.Like)
            {
                var sent = SendSpotifyLike();
                if (sent) return (true, "Zapisano utwór w Ulubionych Spotify (Like)");
            }
            else
            {
                return (false, "Spotify na Windows nie posiada skrótu dla akcji Dislike.");
            }
        }

        // 3. Apple Music / iTunes
        if (appIdentifier.Contains("applemusic", StringComparison.OrdinalIgnoreCase) ||
            appIdentifier.Contains("itunes", StringComparison.OrdinalIgnoreCase))
        {
            if (action == MediaAction.Like)
            {
                SendKeyCombination([VK_CONTROL, VK_SHIFT, VK_F]);
                return (true, "Polubiono utwór w Apple Music (Like)");
            }
        }

        // 4. Tidal
        if (appIdentifier.Contains("tidal", StringComparison.OrdinalIgnoreCase))
        {
            if (action == MediaAction.Like)
            {
                SendKeyCombination([VK_CONTROL, VK_L]);
                return (true, "Polubiono utwór w Tidal (Like)");
            }
        }

        // 5. Fallback ogólny: przeszukanie aktywnych okien odtwarzaczy
        var fallbackSent = TryFallbackWindowRating(action);
        if (fallbackSent)
        {
            return (true, action == MediaAction.Like
                ? "Wysłano polubienie utworu (Like)"
                : "Wysłano odrzucenie utworu (Dislike)");
        }

        return (false, $"Nie znaleziono aktywnego okna obsługującego skrót dla: {action.DisplayName()} ({appIdentifier}).");
    }

    private static bool SendYouTubeMusicRating(MediaAction action)
    {
        var targetHwnd = FindWindowByProcessOrTitle("YouTube Music", "YouTube Music");
        if (targetHwnd != IntPtr.Zero)
        {
            var key = action == MediaAction.Like ? VK_L : VK_D;
            SendKeyCombinationToWindow(targetHwnd, [VK_CONTROL, VK_SHIFT, key]);
            return true;
        }

        var fallbackKey = action == MediaAction.Like ? VK_L : VK_D;
        SendKeyCombination([VK_CONTROL, VK_SHIFT, fallbackKey]);
        return true;
    }

    private static bool SendSpotifyLike()
    {
        var targetHwnd = FindWindowByProcessOrTitle("Spotify", "Spotify");
        if (targetHwnd != IntPtr.Zero)
        {
            SendKeyCombinationToWindow(targetHwnd, [VK_MENU, VK_SHIFT, VK_B]);
            return true;
        }

        SendKeyCombination([VK_MENU, VK_SHIFT, VK_B]);
        return true;
    }

    private static bool TryFallbackWindowRating(MediaAction action)
    {
        var ytmHwnd = FindWindowByProcessOrTitle("YouTube Music", "YouTube Music");
        if (ytmHwnd != IntPtr.Zero)
        {
            var key = action == MediaAction.Like ? VK_L : VK_D;
            SendKeyCombinationToWindow(ytmHwnd, [VK_CONTROL, VK_SHIFT, key]);
            return true;
        }

        var spotifyHwnd = FindWindowByProcessOrTitle("Spotify", "Spotify");
        if (spotifyHwnd != IntPtr.Zero && action == MediaAction.Like)
        {
            SendKeyCombinationToWindow(spotifyHwnd, [VK_MENU, VK_SHIFT, VK_B]);
            return true;
        }

        return false;
    }

    private static IntPtr FindWindowByProcessOrTitle(string processNamePart, string titlePart)
    {
        IntPtr foundHwnd = IntPtr.Zero;
        EnumWindows((hWnd, lParam) =>
        {
            var sbTitle = new StringBuilder(256);
            GetWindowText(hWnd, sbTitle, sbTitle.Capacity);
            var title = sbTitle.ToString();

            GetWindowThreadProcessId(hWnd, out var processId);
            if (processId != 0)
            {
                try
                {
                    using var proc = Process.GetProcessById((int)processId);
                    if (proc.ProcessName.Contains(processNamePart, StringComparison.OrdinalIgnoreCase) ||
                        title.Contains(titlePart, StringComparison.OrdinalIgnoreCase))
                    {
                        foundHwnd = hWnd;
                        return false; // Stop enumeration
                    }
                }
                catch
                {
                    // Ignore access errors
                }
            }
            return true;
        }, IntPtr.Zero);

        return foundHwnd;
    }

    private static void SendKeyCombinationToWindow(IntPtr hWnd, int[] keys)
    {
        var currentForeground = GetForegroundWindow();
        if (currentForeground != hWnd)
        {
            SetForegroundWindow(hWnd);
            Thread.Sleep(35);
        }

        SendKeyCombination(keys);

        if (currentForeground != IntPtr.Zero && currentForeground != hWnd)
        {
            Thread.Sleep(35);
            SetForegroundWindow(currentForeground);
        }
    }

    private static void SendKeyCombination(int[] keys)
    {
        var inputs = new List<INPUT>();

        // Key down in order
        foreach (var key in keys)
        {
            inputs.Add(new INPUT
            {
                type = INPUT_KEYBOARD,
                ki = new KEYBDINPUT { wVk = (ushort)key, dwFlags = 0 }
            });
        }

        // Key up in reverse order
        for (var i = keys.Length - 1; i >= 0; i--)
        {
            inputs.Add(new INPUT
            {
                type = INPUT_KEYBOARD,
                ki = new KEYBDINPUT { wVk = (ushort)keys[i], dwFlags = KEYEVENTF_KEYUP }
            });
        }

        SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<INPUT>());
    }
}
