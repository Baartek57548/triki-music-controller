using Windows.Media.Control;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class MediaControlService : IDisposable
{
    private readonly SystemVolumeService _volumeService;
    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private GlobalSystemMediaTransportControlsSession? _session;
    private bool _disposed;

    public MediaControlService(SystemVolumeService volumeService)
    {
        _volumeService = volumeService;
    }

    public event EventHandler<MediaSnapshot>? StateChanged;
    public MediaSnapshot State { get; private set; } = MediaSnapshot.Initial;

    public async Task InitializeAsync()
    {
        ThrowIfDisposed();
        try
        {
            _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            _manager.CurrentSessionChanged += ManagerOnCurrentSessionChanged;
            _manager.SessionsChanged += ManagerOnSessionsChanged;
            AttachSession(_manager.GetCurrentSession());
            await RefreshAsync().ConfigureAwait(false);
        }
        catch (Exception error)
        {
            Publish(State with { ErrorMessage = $"Windows nie udostępnił sterowania multimediami: {error.Message}" });
        }
    }

    public async Task<(bool Succeeded, string Message)> ExecuteAsync(MediaAction action)
    {
        ThrowIfDisposed();
        try
        {
            if (action is MediaAction.VolumeUp or MediaAction.VolumeDown or MediaAction.Mute or MediaAction.Unmute)
            {
                switch (action)
                {
                    case MediaAction.VolumeUp: _volumeService.StepUp(); break;
                    case MediaAction.VolumeDown: _volumeService.StepDown(); break;
                    case MediaAction.Mute: _volumeService.SetMute(true); break;
                    case MediaAction.Unmute: _volumeService.SetMute(false); break;
                }
                await RefreshAsync().ConfigureAwait(false);
                return (true, action.DisplayName());
            }

            if (action is MediaAction.Like or MediaAction.Dislike)
            {
                var ratingResult = await WindowsRatingDispatcher.DispatchRatingAsync(action, _session?.SourceAppUserModelId);
                return ratingResult;
            }

            if (action == MediaAction.None) return (true, action.DisplayName());
            var session = _session ?? throw new InvalidOperationException("Brak aktywnej sesji multimedialnej.");
            var controls = session.GetPlaybackInfo()?.Controls;
            bool succeeded = action switch
            {
                MediaAction.Play when controls?.IsPlayEnabled == true => await session.TryPlayAsync(),
                MediaAction.Pause when controls?.IsPauseEnabled == true => await session.TryPauseAsync(),
                MediaAction.PlayPause when controls?.IsPlayPauseToggleEnabled == true => await session.TryTogglePlayPauseAsync(),
                MediaAction.Next when controls?.IsNextEnabled == true => await session.TrySkipNextAsync(),
                MediaAction.Previous when controls?.IsPreviousEnabled == true => await session.TrySkipPreviousAsync(),
                MediaAction.Stop when controls?.IsStopEnabled == true => await session.TryStopAsync(),
                _ => false,
            };
            if (!succeeded) return (false, $"Aktywny odtwarzacz nie obsługuje akcji: {action.DisplayName()}.");
            await RefreshAsync().ConfigureAwait(false);
            return (true, action.DisplayName());
        }
        catch (Exception error)
        {
            return (false, error.Message);
        }
    }

    public async Task RefreshAsync()
    {
        ThrowIfDisposed();
        try
        {
            var volume = _volumeService.GetState();
            var session = _session;
            if (session is null)
            {
                Publish(MediaSnapshot.Initial with { VolumePercent = volume.Percent, IsMuted = volume.Muted });
                return;
            }

            var playbackInfo = session.GetPlaybackInfo();
            var controls = playbackInfo?.Controls;
            var properties = await session.TryGetMediaPropertiesAsync();
            byte[]? thumbnailBytes = null;
            if (properties?.Thumbnail is { } thumbRef)
            {
                try
                {
                    using var streamRef = await thumbRef.OpenReadAsync();
                    if (streamRef is not null && streamRef.Size > 0 && streamRef.Size < 15_000_000)
                    {
                        var buffer = new Windows.Storage.Streams.Buffer((uint)streamRef.Size);
                        await streamRef.ReadAsync(buffer, (uint)streamRef.Size, Windows.Storage.Streams.InputStreamOptions.None);
                        using var reader = Windows.Storage.Streams.DataReader.FromBuffer(buffer);
                        thumbnailBytes = new byte[buffer.Length];
                        reader.ReadBytes(thumbnailBytes);
                    }
                }
                catch (Exception error)
                {
                    System.Diagnostics.Debug.WriteLine($"Nie udało się pobrać miniatury utworu: {error.Message}");
                }
            }

            Publish(new MediaSnapshot(
                true,
                playbackInfo?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing,
                string.IsNullOrWhiteSpace(properties?.Title) ? "Nieznany utwór" : properties.Title,
                string.IsNullOrWhiteSpace(properties?.Artist) ? "Nieznany wykonawca" : properties.Artist,
                session.SourceAppUserModelId ?? "Nieznany odtwarzacz",
                controls?.IsPlayEnabled == true,
                controls?.IsPauseEnabled == true,
                controls?.IsNextEnabled == true,
                controls?.IsPreviousEnabled == true,
                volume.Percent,
                volume.Muted,
                thumbnailBytes,
                null));
        }
        catch (Exception error)
        {
            Publish(State with { ErrorMessage = error.Message });
        }
    }

    private void ManagerOnCurrentSessionChanged(GlobalSystemMediaTransportControlsSessionManager sender, CurrentSessionChangedEventArgs args)
    {
        AttachSession(sender.GetCurrentSession());
        QueueRefresh();
    }

    private void ManagerOnSessionsChanged(GlobalSystemMediaTransportControlsSessionManager sender, SessionsChangedEventArgs args)
    {
        AttachSession(sender.GetCurrentSession());
        QueueRefresh();
    }

    private void AttachSession(GlobalSystemMediaTransportControlsSession? session)
    {
        if (ReferenceEquals(_session, session)) return;
        if (_session is not null)
        {
            _session.MediaPropertiesChanged -= SessionOnMediaPropertiesChanged;
            _session.PlaybackInfoChanged -= SessionOnPlaybackInfoChanged;
        }
        _session = session;
        if (_session is not null)
        {
            _session.MediaPropertiesChanged += SessionOnMediaPropertiesChanged;
            _session.PlaybackInfoChanged += SessionOnPlaybackInfoChanged;
        }
    }

    private void SessionOnMediaPropertiesChanged(GlobalSystemMediaTransportControlsSession sender, MediaPropertiesChangedEventArgs args) =>
        QueueRefresh();

    private void SessionOnPlaybackInfoChanged(GlobalSystemMediaTransportControlsSession sender, PlaybackInfoChangedEventArgs args) =>
        QueueRefresh();

    private void QueueRefresh() => _ = RefreshFromEventAsync();

    private async Task RefreshFromEventAsync()
    {
        try
        {
            await RefreshAsync().ConfigureAwait(false);
        }
        catch (ObjectDisposedException)
        {
            // A queued platform event may finish after the application has begun closing.
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Odświeżenie sesji multimedialnej nie powiodło się: {error}");
        }
    }

    private void Publish(MediaSnapshot state)
    {
        State = state;
        StateChanged?.Invoke(this, state);
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(_disposed, this);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        AttachSession(null);
        if (_manager is not null)
        {
            _manager.CurrentSessionChanged -= ManagerOnCurrentSessionChanged;
            _manager.SessionsChanged -= ManagerOnSessionsChanged;
        }
        _manager = null;
    }
}
