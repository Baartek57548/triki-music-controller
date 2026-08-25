using System.Runtime.InteropServices;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class FeedbackToneService
{
    public void PlayRatingResult(RatingGestureAction action, bool succeeded)
    {
        if (!succeeded)
        {
            MessageBeep(0x00000010);
            return;
        }
        MessageBeep(action == RatingGestureAction.Like ? 0x00000040u : 0x00000030u);
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool MessageBeep(uint type);
}
