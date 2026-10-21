using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController_Windows.Runtime;

public sealed class TrikiRuntimeEngine : IDisposable
{
    private readonly object _sync = new();
    private readonly BluetoothService _bluetooth;
    private readonly MediaControlService _media;
    private readonly SystemVolumeService _systemVolume;
    private readonly SystemBrightnessService _systemBrightness;
    private readonly SettingsService _settings;
    private readonly FeedbackToneService _ratingFeedback;
    private readonly SensorFilter _sensorFilter = new();
    private readonly GyroscopeVolumeController _volumeController = new();
    private readonly EdgePoseBrightnessController _brightnessController = new();
    private FullRotationGestureDetector _rotationGestureDetector;
    private readonly TrikiButtonInterpreter _buttonInterpreter = new();
    private readonly ConnectionActivityLease _connectionActivityLease = new();
    private bool _connectionWasReady;
    private ConnectionPowerMode? _lastPowerMode;
    private bool _disposed;

    public TrikiRuntimeEngine(
        BluetoothService bluetooth,
        MediaControlService media,
        SystemVolumeService systemVolume,
        SystemBrightnessService systemBrightness,
        SettingsService settings,
        FeedbackToneService ratingFeedback)
    {
        _bluetooth = bluetooth;
        _media = media;
        _systemVolume = systemVolume;
        _systemBrightness = systemBrightness;
        _settings = settings;
        _ratingFeedback = ratingFeedback;
        _brightnessController.CurrentBrightnessPercent = _systemBrightness.GetBrightness();
        _rotationGestureDetector = CreateGestureDetector(_settings.Current.RotationAngleDegrees);
        _bluetooth.SampleReceived += BluetoothOnSampleReceived;
        _bluetooth.StateChanged += BluetoothOnStateChanged;
    }

    /// <summary>Tworzy detektor obrotu z przeliczonym progiem filtrowanym dla podanego kąta fizycznego.</summary>
    private static FullRotationGestureDetector CreateGestureDetector(int physicalAngleDegrees)
    {
        // Filtr low-pass pochłania ~7% ruchu; przeliczamy próg proporcjonalnie
        const float filterFactor = FullRotationGestureDetector.FilteredRotationTriggerDegrees
            / FullRotationGestureDetector.PhysicalRotationTargetDegrees;
        var required = Math.Clamp(physicalAngleDegrees * filterFactor, 80f, 360f);
        var maximum = Math.Max(required + 150f, required * 1.4f);
        return new FullRotationGestureDetector(new FullRotationGestureConfiguration(
            RequiredRotationDegrees: required,
            MaximumRotationDegrees: maximum));
    }

    /// <summary>Aktualizuje kąt obrotu wymagany do zmiany utworu bez przerywania połączenia.</summary>
    public void UpdateRotationAngle(int physicalAngleDegrees)
    {
        lock (_sync)
        {
            _rotationGestureDetector = CreateGestureDetector(physicalAngleDegrees);
        }
    }

    public RuntimeSnapshot State { get; private set; } = RuntimeSnapshot.Initial;
    public event EventHandler<RuntimeSnapshot>? StateChanged;

    public void ResetConnectionActivity()
    {
        lock (_sync) _connectionActivityLease.Reset();
    }

    public void Reset()
    {
        lock (_sync)
        {
            _sensorFilter.Reset();
            _volumeController.Reset();
            _brightnessController.Reset();
            _rotationGestureDetector.Reset();
            _buttonInterpreter.Reset();
            _connectionActivityLease.Reset();
            State = RuntimeSnapshot.Initial;
        }
        StateChanged?.Invoke(this, State);
    }

    private void BluetoothOnStateChanged(object? sender, BluetoothSnapshot state)
    {
        var isReady = state.ConnectionState == TrikiConnectionState.Ready;
        if (isReady != _connectionWasReady)
        {
            lock (_sync) _connectionActivityLease.Reset();
            _connectionWasReady = isReady;
        }
        if (!isReady && State.LatestSample is not null) Reset();
    }

    private void BluetoothOnSampleReceived(object? sender, TrikiSensorData sample)
    {
        MediaAction? actionToExecute = null;
        var shouldParkConnection = false;
        lock (_sync)
        {
            if (_lastPowerMode != _settings.Current.ConnectionPowerMode)
            {
                _lastPowerMode = _settings.Current.ConnectionPowerMode;
                _connectionActivityLease.Reset();
            }
            _connectionActivityLease.ConfigureTimeout(_settings.Current.ConnectionPowerMode.IdleTimeoutNanos());
            _volumeController.Sensitivity = _settings.Current.VolumeSensitivityPercent / 100f;
            _brightnessController.Sensitivity = _settings.Current.BrightnessSensitivityPercent / 100f;
            var filtered = _sensorFilter.Process(sample, _settings.Current.Calibration);
            var buttonEvent = _buttonInterpreter.Process(sample);
            var gesture = _rotationGestureDetector.Process(filtered);

            var isButtonPressed = _buttonInterpreter.IsPressed;

            var brightness = _settings.Current.BrightnessGestureEnabled ? _brightnessController.Process(filtered, isButtonPressed) : null;
            if (!_settings.Current.BrightnessGestureEnabled) _brightnessController.Reset();
            if (brightness?.Active == true && isButtonPressed)
            {
                _buttonInterpreter.ConsumeCurrentHold();
            }
            shouldParkConnection = _settings.Current.ConnectOnlyWhenNeeded &&
                _connectionActivityLease.Observe(filtered,
                    buttonEvent is not null || _buttonInterpreter.ShouldSuppressMotionControl,
                    brightness?.Active == true && isButtonPressed, gesture.Phase);

            if (brightness?.Active == true)
            {
                _volumeController.Reset();
                _rotationGestureDetector.Reset();
                if (brightness.DeltaPercent != 0f)
                {
                    _systemBrightness.StepBrightness(brightness.DeltaPercent);
                }
                State = State with
                {
                    LatestSample = filtered,
                    Brightness = brightness,
                    Volume = null,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                };
            }
            else if (buttonEvent is null && gesture.Triggered)
            {
                _volumeController.Reset();
                _brightnessController.Reset();
                actionToExecute = gesture.Direction?.ToInvertedCapsuleNavigationAction() ?? MediaAction.None;
                State = State with
                {
                    LatestSample = filtered,
                    Brightness = null,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                    LastActionStatus = $"Rozpoznano obrót: {actionToExecute.Value.DisplayName()}",
                };
            }
            else if (buttonEvent is not null)
            {
                _volumeController.Reset();
                _brightnessController.Reset();
                actionToExecute = _settings.Current.ActionFor(buttonEvent.Type);
                State = State with
                {
                    LatestSample = filtered,
                    Brightness = null,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                    LastActionStatus = $"Przycisk: {buttonEvent.Type}",
                };
            }
            else if (_buttonInterpreter.ShouldSuppressMotionControl)
            {
                _volumeController.Reset();
                _brightnessController.Reset();
                State = State with
                {
                    LatestSample = filtered,
                    Brightness = null,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                    Volume = null,
                };
            }
            else if (_settings.Current.VolumeGestureEnabled)
            {
                _brightnessController.Reset();
                var volume = _volumeController.Process(filtered);
                actionToExecute = volume.Action;
                State = State with
                {
                    LatestSample = filtered,
                    Brightness = null,
                    Volume = volume,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                };
            }
            else
            {
                _volumeController.Reset();
                State = State with { LatestSample = filtered, Brightness = null, Volume = null, Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode };
            }
        }
        StateChanged?.Invoke(this, State);

        if (actionToExecute is MediaAction action && action != MediaAction.None)
            _ = ExecuteAsync(action);
        if (shouldParkConnection) _ = ParkConnectionAfterIdleAsync();
    }

    private async Task ParkConnectionAfterIdleAsync()
    {
        var requestedMode = _lastPowerMode;
        try
        {
            await _bluetooth.ParkUntilWakeAsync(() =>
            {
                lock (_sync) return requestedMode == _settings.Current.ConnectionPowerMode && _connectionActivityLease.ParkingRequested;
            }).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            lock (_sync) _connectionActivityLease.Reset();
            System.Diagnostics.Debug.WriteLine($"Nie udało się uśpić połączenia GATT: {error}");
        }
    }

    private async Task ExecuteAsync(MediaAction action)
    {
        (bool Succeeded, string Message) result;
        try
        {
            if (action is MediaAction.VolumeUp or MediaAction.VolumeDown or MediaAction.Mute or MediaAction.Unmute)
            {
                // Keep the sensor path independent from MediaSession: IAudioEndpointVolume changes the
                // Windows default render endpoint (the system master), never an individual app session.
                switch (action)
                {
                    case MediaAction.VolumeUp: _systemVolume.StepUp(); break;
                    case MediaAction.VolumeDown: _systemVolume.StepDown(); break;
                    case MediaAction.Mute: _systemVolume.SetMute(true); break;
                    case MediaAction.Unmute: _systemVolume.SetMute(false); break;
                }
                await _media.RefreshAsync().ConfigureAwait(false);
                result = (true, action.DisplayName());
            }
            else
            {
                result = await _media.ExecuteAsync(action).ConfigureAwait(false);
            }
        }
        catch (Exception error)
        {
            result = (false, error.Message);
            System.Diagnostics.Debug.WriteLine($"Wykonanie akcji {action} nie powiodło się: {error}");
        }
        if (_settings.Current.EnableSoundFeedback && action is MediaAction.Like or MediaAction.Dislike)
            _ratingFeedback.PlayRatingAction(action);
        lock (_sync)
        {
            State = State with
            {
                LastAction = action,
                LastActionAt = DateTimeOffset.Now,
                LastActionStatus = result.Succeeded ? $"Wykonano: {action.DisplayName()}" : $"Nie wykonano: {result.Message}",
            };
        }
        StateChanged?.Invoke(this, State);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _bluetooth.SampleReceived -= BluetoothOnSampleReceived;
        _bluetooth.StateChanged -= BluetoothOnStateChanged;
    }
}
