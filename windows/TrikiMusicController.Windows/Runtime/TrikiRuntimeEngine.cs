using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController_Windows.Runtime;

public sealed class TrikiRuntimeEngine : IDisposable
{
    private readonly object _sync = new();
    private readonly BluetoothService _bluetooth;
    private readonly MediaControlService _media;
    private readonly SettingsService _settings;
    private readonly FeedbackToneService _feedback;
    private readonly SensorFilter _sensorFilter = new();
    private readonly GyroscopeVolumeController _volumeController = new();
    private readonly HoldArcGestureDetector _ratingGestureDetector = new();
    private readonly TrikiButtonInterpreter _buttonInterpreter = new();
    private readonly ConnectionActivityLease _connectionActivityLease = new();
    private bool _connectionWasReady;
    private bool _disposed;

    public TrikiRuntimeEngine(
        BluetoothService bluetooth,
        MediaControlService media,
        SettingsService settings,
        FeedbackToneService feedback)
    {
        _bluetooth = bluetooth;
        _media = media;
        _settings = settings;
        _feedback = feedback;
        _bluetooth.SampleReceived += BluetoothOnSampleReceived;
        _bluetooth.StateChanged += BluetoothOnStateChanged;
    }

    public RuntimeSnapshot State { get; private set; } = RuntimeSnapshot.Initial;
    public event EventHandler<RuntimeSnapshot>? StateChanged;

    public void Reset()
    {
        lock (_sync)
        {
            _sensorFilter.Reset();
            _volumeController.Reset();
            _ratingGestureDetector.Reset();
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
        RatingGestureAction? ratingFeedback = null;
        var shouldParkConnection = false;
        lock (_sync)
        {
            var filtered = _sensorFilter.Process(sample, _settings.Current.Calibration);
            var buttonEvent = _buttonInterpreter.Process(sample);
            var gesture = _ratingGestureDetector.Process(filtered);
            var explicitConnectionActivity = buttonEvent is not null ||
                _buttonInterpreter.IsPressed ||
                gesture.Phase is HoldGesturePhase.Holding or
                    HoldGesturePhase.Ready or
                    HoldGesturePhase.Tracking or
                    HoldGesturePhase.Completing or
                    HoldGesturePhase.Triggered;
            shouldParkConnection = _settings.Current.ConnectOnlyWhenNeeded &&
                _connectionActivityLease.Observe(filtered, explicitConnectionActivity);

            if (gesture.Action is RatingGestureAction ratingAction)
            {
                _volumeController.Reset();
                ratingFeedback = ratingAction;
                actionToExecute = ratingAction == RatingGestureAction.Like ? MediaAction.Like : MediaAction.Dislike;
                State = State with
                {
                    LatestSample = filtered,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                    LastActionStatus = $"Rozpoznano gest: {actionToExecute.Value.DisplayName()}",
                };
            }
            else if (buttonEvent is not null)
            {
                _volumeController.Reset();
                actionToExecute = _settings.Current.ActionFor(buttonEvent.Type);
                State = State with
                {
                    LatestSample = filtered,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                    LastActionStatus = $"Przycisk: {buttonEvent.Type}",
                };
            }
            else if (_buttonInterpreter.ShouldSuppressMotionControl)
            {
                _volumeController.Reset();
                State = State with
                {
                    LatestSample = filtered,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                    Volume = null,
                };
            }
            else
            {
                var volume = _volumeController.Process(filtered);
                actionToExecute = volume.Action;
                State = State with
                {
                    LatestSample = filtered,
                    Volume = volume,
                    Gesture = gesture,
                    ButtonProtocol = _buttonInterpreter.ProtocolMode,
                };
            }
        }
        StateChanged?.Invoke(this, State);

        if (actionToExecute is MediaAction action && action != MediaAction.None)
            _ = ExecuteAsync(action, ratingFeedback);
        if (shouldParkConnection) _ = ParkConnectionAfterIdleAsync();
    }

    private async Task ParkConnectionAfterIdleAsync()
    {
        try
        {
            await _bluetooth.ParkUntilWakeAsync().ConfigureAwait(false);
        }
        catch (Exception error)
        {
            lock (_sync) _connectionActivityLease.Reset();
            System.Diagnostics.Debug.WriteLine($"Nie udało się uśpić połączenia GATT: {error}");
        }
    }

    private async Task ExecuteAsync(MediaAction action, RatingGestureAction? ratingFeedback)
    {
        (bool Succeeded, string Message) result;
        try
        {
            result = await _media.ExecuteAsync(action).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            result = (false, error.Message);
            System.Diagnostics.Debug.WriteLine($"Wykonanie akcji {action} nie powiodło się: {error}");
        }
        if (ratingFeedback is RatingGestureAction rating) _feedback.PlayRatingResult(rating, result.Succeeded);
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
