using System.Runtime.InteropServices;

namespace TrikiMusicController_Windows.Services;

public sealed class SystemMouseService
{
    private const uint InputMouse = 0;
    private const uint MouseEventfMove = 0x0001;
    private const uint MouseEventfLeftDown = 0x0002;
    private const uint MouseEventfLeftUp = 0x0004;
    private const uint MouseEventfRightDown = 0x0008;
    private const uint MouseEventfRightUp = 0x0010;
    private const uint MouseEventfWheel = 0x0800;
    private const int WheelDelta = 120;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUT
    {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public MOUSEINPUT mi;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, [In] INPUT[] pInputs, int cbSize);

    public void Move(int dx, int dy)
    {
        if (dx == 0 && dy == 0) return;
        var inputs = new INPUT[]
        {
            new()
            {
                type = InputMouse,
                mi = new MOUSEINPUT
                {
                    dx = dx,
                    dy = dy,
                    dwFlags = MouseEventfMove,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero,
                }
            }
        };
        SendInput(1, inputs, Marshal.SizeOf<INPUT>());
    }

    public void LeftClick()
    {
        var inputs = new INPUT[]
        {
            new() { type = InputMouse, mi = new MOUSEINPUT { dwFlags = MouseEventfLeftDown } },
            new() { type = InputMouse, mi = new MOUSEINPUT { dwFlags = MouseEventfLeftUp } }
        };
        SendInput(2, inputs, Marshal.SizeOf<INPUT>());
    }

    public void RightClick()
    {
        var inputs = new INPUT[]
        {
            new() { type = InputMouse, mi = new MOUSEINPUT { dwFlags = MouseEventfRightDown } },
            new() { type = InputMouse, mi = new MOUSEINPUT { dwFlags = MouseEventfRightUp } }
        };
        SendInput(2, inputs, Marshal.SizeOf<INPUT>());
    }

    public void Scroll(int steps)
    {
        if (steps == 0) return;
        var inputs = new INPUT[]
        {
            new()
            {
                type = InputMouse,
                mi = new MOUSEINPUT
                {
                    dwFlags = MouseEventfWheel,
                    mouseData = unchecked((uint)(steps * WheelDelta)),
                }
            }
        };
        SendInput(1, inputs, Marshal.SizeOf<INPUT>());
    }
}
