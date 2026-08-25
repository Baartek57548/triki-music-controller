using System.Runtime.InteropServices;

namespace TrikiMusicController_Windows.Services;

public sealed class SystemVolumeService
{
    public (float Percent, bool Muted) GetState() => WithEndpoint(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out var scalar));
        Marshal.ThrowExceptionForHR(endpoint.GetMute(out var muted));
        return (Math.Clamp(scalar * 100f, 0, 100), muted);
    });

    public void StepUp() => WithEndpoint<object?>(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.VolumeStepUp(Guid.Empty));
        return null;
    });

    public void StepDown() => WithEndpoint<object?>(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.VolumeStepDown(Guid.Empty));
        return null;
    });

    public void SetMute(bool muted) => WithEndpoint<object?>(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.SetMute(muted, Guid.Empty));
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
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Multimedia, out device));
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
        [PreserveSig] int SetMasterVolumeLevel(float levelDb, Guid eventContext);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, Guid eventContext);
        [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(uint channel, float levelDb, Guid eventContext);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, Guid eventContext);
        [PreserveSig] int GetChannelVolumeLevel(uint channel, out float levelDb);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool muted, Guid eventContext);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool muted);
        [PreserveSig] int GetVolumeStepInfo(out uint currentStep, out uint stepCount);
        [PreserveSig] int VolumeStepUp(Guid eventContext);
        [PreserveSig] int VolumeStepDown(Guid eventContext);
        [PreserveSig] int QueryHardwareSupport(out uint hardwareSupportMask);
        [PreserveSig] int GetVolumeRange(out float minimumDb, out float maximumDb, out float incrementDb);
    }
}
