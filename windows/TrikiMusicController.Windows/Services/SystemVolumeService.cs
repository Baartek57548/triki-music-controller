using System.Runtime.InteropServices;

namespace TrikiMusicController_Windows.Services;

public sealed class SystemVolumeService
{
    // This service deliberately uses the endpoint master-volume interface. It never opens an
    // audio-session volume, so every change is applied by Windows to the current system output.
    private const float VolumeStepScalar = 0.02f;
    private static readonly Guid EventContext = new("FBCA4A21-0933-47DD-A1EA-E8C8647348CC");

    public (float Percent, bool Muted) GetState() => WithEndpoint(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out var scalar));
        Marshal.ThrowExceptionForHR(endpoint.GetMute(out var muted));
        return (Math.Clamp(scalar * 100f, 0, 100), muted);
    });

    public void StepUp() => Step(VolumeStepScalar, unmute: true);

    public void StepDown() => Step(-VolumeStepScalar, unmute: false);

    public void SetPercent(float percent)
    {
        if (!float.IsFinite(percent) || percent is < 0f or > 100f)
            throw new ArgumentOutOfRangeException(nameof(percent), "Głośność musi mieścić się w zakresie 0–100%.");
        SetScalar(percent / 100f);
    }

    private static void Step(float delta, bool unmute) => WithEndpoint<object?>(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out var current));
        var target = Math.Clamp(current + delta, 0f, 1f);
        var context = EventContext;
        Marshal.ThrowExceptionForHR(endpoint.SetMasterVolumeLevelScalar(target, ref context));
        if (unmute)
        {
            Marshal.ThrowExceptionForHR(endpoint.GetMute(out var muted));
            if (muted) Marshal.ThrowExceptionForHR(endpoint.SetMute(false, ref context));
        }
        return null;
    });

    private static void SetScalar(float scalar) => WithEndpoint<object?>(endpoint =>
    {
        var context = EventContext;
        Marshal.ThrowExceptionForHR(endpoint.SetMasterVolumeLevelScalar(scalar, ref context));
        return null;
    });

    public void SetMute(bool muted) => WithEndpoint<object?>(endpoint =>
    {
        var context = EventContext;
        Marshal.ThrowExceptionForHR(endpoint.SetMute(muted, ref context));
        return null;
    });

    private static T WithEndpoint<T>(Func<IAudioEndpointVolume, T> operation)
    {
        IMMDeviceEnumerator? enumerator = null;
        IMMDevice? device = null;
        object? endpointObject = null;
        try
        {
            enumerator = (IMMDeviceEnumerator)(object)new MMDeviceEnumeratorComObject();
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Console, out device));
            var iid = typeof(IAudioEndpointVolume).GUID;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, ClsCtx.All, IntPtr.Zero, out endpointObject));
            return operation((IAudioEndpointVolume)endpointObject);
        }
        finally
        {
            if (endpointObject is not null && Marshal.IsComObject(endpointObject)) Marshal.FinalReleaseComObject(endpointObject);
            if (device is not null && Marshal.IsComObject(device)) Marshal.FinalReleaseComObject(device);
            if (enumerator is not null && Marshal.IsComObject(enumerator)) Marshal.FinalReleaseComObject(enumerator);
        }
    }

    private enum EDataFlow { Render, Capture, All }
    private enum ERole { Console, Multimedia, Communications }

    [Flags]
    private enum ClsCtx : uint
    {
        InprocServer = 0x1,
        InprocHandler = 0x2,
        LocalServer = 0x4,
        RemoteServer = 0x10,
        All = InprocServer | InprocHandler | LocalServer | RemoteServer,
    }

    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private sealed class MMDeviceEnumeratorComObject;

    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig]
        int Activate(ref Guid iid, ClsCtx classContext, IntPtr activationParameters, [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
        [PreserveSig] int OpenPropertyStore(uint access, out IntPtr properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out uint state);
    }

    [ComImport]
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(IntPtr notify);
        [PreserveSig] int UnregisterControlChangeNotify(IntPtr notify);
        [PreserveSig] int GetChannelCount(out uint channelCount);
        [PreserveSig] int SetMasterVolumeLevel(float levelDb, ref Guid eventContext);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid eventContext);
        [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid eventContext);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid eventContext);
        [PreserveSig] int GetChannelVolumeLevel(uint channel, out float levelDb);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool muted, ref Guid eventContext);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool muted);
        [PreserveSig] int GetVolumeStepInfo(out uint currentStep, out uint stepCount);
        [PreserveSig] int VolumeStepUp(ref Guid eventContext);
        [PreserveSig] int VolumeStepDown(ref Guid eventContext);
        [PreserveSig] int QueryHardwareSupport(out uint hardwareSupportMask);
        [PreserveSig] int GetVolumeRange(out float minimumDb, out float maximumDb, out float incrementDb);
    }
}
