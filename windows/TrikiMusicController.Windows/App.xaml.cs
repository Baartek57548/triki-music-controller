using Microsoft.UI.Xaml;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.Windows.AppLifecycle;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;
using ILaunchActivatedEventArgs = Windows.ApplicationModel.Activation.ILaunchActivatedEventArgs;

namespace TrikiMusicController_Windows;

public partial class App : Microsoft.UI.Xaml.Application
{
    private const string SingleInstanceMutexName = @"Local\TrikiMusicController.BAEDA449-C844-43F1-8888-AE0EFE5FBB13";
    private Window? _window;
    private readonly Mutex _singleInstanceMutex;
    private readonly bool _ownsSingleInstanceMutex;
    private bool _singleInstanceMutexDisposed;
    private AppInstance? _mainInstance;
    private DispatcherQueue? _dispatcherQueue;
    private TrayIconService? _trayIcon;
    private bool _applicationResourcesDisposed;
    private bool _shutdownStarted;
    public static AppServices Services { get; private set; } = null!;
    public MainWindow? MainWindow => _window as MainWindow;

    public App()
    {
        _singleInstanceMutex = new Mutex(initiallyOwned: true, SingleInstanceMutexName, out _ownsSingleInstanceMutex);
        InitializeComponent();
        UnhandledException += (_, args) =>
        {
            System.Diagnostics.Debug.WriteLine(args.Exception);
            args.Handled = false;
        };
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            _mainInstance = AppInstance.FindOrRegisterForKey("TrikiMusicController.Main");
            if (!_mainInstance.IsCurrent || !_ownsSingleInstanceMutex)
            {
                if (!_mainInstance.IsCurrent)
                    await _mainInstance.RedirectActivationToAsync(AppInstance.GetCurrent().GetActivatedEventArgs());
                ReleaseSingleInstanceMutex();
                Exit();
                return;
            }
            var settings = new SettingsService();
            await settings.LoadAsync();
            _dispatcherQueue = DispatcherQueue.GetForCurrentThread();
            _mainInstance.Activated += MainInstance_Activated;
            Services = new AppServices(settings, _dispatcherQueue);
            _window = new MainWindow();
            _window.AppWindow.Closing += Window_Closing;
            _window.AppWindow.Changed += Window_Changed;
            _window.Closed += Window_Closed;
            _trayIcon = new TrayIconService(
                _dispatcherQueue,
                Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"),
                ShowMainWindow,
                Shutdown,
                action => _ = Services.Media.ExecuteAsync(action),
                () => _ = Services.ViewModel.ConnectOrDisconnectAsync());

            Services.Bluetooth.StateChanged += OnBluetoothStateChanged;
            Services.Media.StateChanged += OnMediaStateChanged;

            var commandLineArgs = Environment.GetCommandLineArgs();
            var isWhatsNewRequested = commandLineArgs.Any(a => a.Equals("--whats-new", StringComparison.OrdinalIgnoreCase) || a.Equals("--updated", StringComparison.OrdinalIgnoreCase));
            var isBackground = commandLineArgs.Any(a => a.Equals("--background", StringComparison.OrdinalIgnoreCase));

            var previousSeenVersion = settings.Current.LastSeenVersion;
            var isVersionUpdated = !string.IsNullOrWhiteSpace(previousSeenVersion) &&
                                   !previousSeenVersion.Equals(AppInfo.Version, StringComparison.OrdinalIgnoreCase);

            if (isWhatsNewRequested || isVersionUpdated)
            {
                settings.Current.LastSeenVersion = AppInfo.Version;
                await settings.SaveAsync();
                ShowMainWindow();
                _dispatcherQueue.TryEnqueue(async () =>
                {
                    if (MainWindow is { } win)
                    {
                        await win.ShowWhatsNewDialogAsync();
                    }
                });
            }
            else
            {
                if (string.IsNullOrWhiteSpace(previousSeenVersion))
                {
                    settings.Current.LastSeenVersion = AppInfo.Version;
                    await settings.SaveAsync();
                }
                HideMainWindow();
            }

            await Services.ViewModel.InitializeAsync();
            UpdateTrayStatus();
        }
        catch (Exception error)
        {
            System.Diagnostics.Trace.WriteLine($"Nie udało się uruchomić aplikacji: {error}");
            WriteBootstrapError(error);
            ReleaseSingleInstanceMutex();
            Exit();
        }
    }

    private void MainInstance_Activated(object? sender, AppActivationArguments args)
    {
        if (args.Kind != ExtendedActivationKind.Launch ||
            args.Data is not ILaunchActivatedEventArgs launch)
            return;

        var isBackground = IsBackgroundLaunch(launch.Arguments);
        var isWhatsNew = launch.Arguments.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Any(a => a.Equals("--whats-new", StringComparison.OrdinalIgnoreCase) || a.Equals("--updated", StringComparison.OrdinalIgnoreCase));

        if (isBackground && !isWhatsNew) return;

        _dispatcherQueue?.TryEnqueue(async () =>
        {
            ShowMainWindow();
            if (isWhatsNew && MainWindow is { } win)
            {
                await win.ShowWhatsNewDialogAsync();
            }
        });
    }

    public void Shutdown()
    {
        if (_shutdownStarted) return;
        _shutdownStarted = true;
        _trayIcon?.Dispose();
        _trayIcon = null;
        if (_window is not null)
            _window.Close();
        else
            DisposeApplicationResources();
        Exit();
    }

    private void ShowMainWindow()
    {
        if (_shutdownStarted || _window is null) return;
        _window.AppWindow.IsShownInSwitchers = true;
        _window.AppWindow.Show();
        if (_window.AppWindow.Presenter is OverlappedPresenter presenter)
            presenter.Restore();
        _window.Activate();
    }

    private void HideMainWindow()
    {
        if (_shutdownStarted || _window is null) return;
        _window.AppWindow.IsShownInSwitchers = false;
        _window.AppWindow.Hide();
    }

    private void Window_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (_shutdownStarted) return;
        args.Cancel = true;
        HideMainWindow();
    }

    private void Window_Changed(AppWindow sender, AppWindowChangedEventArgs args)
    {
        if (_shutdownStarted || !args.DidPresenterChange) return;
        if (sender.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized })
            HideMainWindow();
    }

    private bool _notifiedConnected;

    private void UpdateTrayStatus()
    {
        if (_trayIcon is null || _shutdownStarted || Services is null) return;
        try
        {
            var vm = Services.ViewModel;
            var isConnected = vm.IsConnected;
            var statusHeader = isConnected
                ? $"Triki: Połączono ({vm.BatteryText})"
                : $"Triki: {vm.ConnectionTitle}";
            var tooltip = isConnected
                ? $"Triki: {vm.BatteryText} • {vm.MediaTitle}"
                : $"Triki Music Controller • {vm.ConnectionTitle}";

            _trayIcon.UpdateStatus(statusHeader, tooltip, isConnected, Services.Media.State.IsPlaying);

            if (Services.Settings.Current.EnableToastNotifications)
            {
                if (isConnected && !_notifiedConnected)
                {
                    _notifiedConnected = true;
                    _trayIcon.ShowToastNotification("Triki Music Controller", $"Połączono z Triki (Bateria: {vm.BatteryText})");
                }
                else if (!isConnected)
                {
                    _notifiedConnected = false;
                }
            }
        }
        catch (Exception error)
        {
            System.Diagnostics.Trace.WriteLine($"Błąd podczas aktualizacji ikony zasobnika: {error}");
        }
    }

    private void OnBluetoothStateChanged(object? sender, BluetoothSnapshot state)
    {
        _dispatcherQueue?.TryEnqueue(UpdateTrayStatus);
    }

    private void OnMediaStateChanged(object? sender, MediaSnapshot state)
    {
        _dispatcherQueue?.TryEnqueue(UpdateTrayStatus);
    }

    private void Window_Closed(object sender, WindowEventArgs args)
    {
        if (sender is Window closedWindow)
        {
            closedWindow.AppWindow.Closing -= Window_Closing;
            closedWindow.AppWindow.Changed -= Window_Changed;
            closedWindow.Closed -= Window_Closed;
        }
        _trayIcon?.Dispose();
        _trayIcon = null;
        _window = null;
        DisposeApplicationResources();
    }

    private void DisposeApplicationResources()
    {
        if (_applicationResourcesDisposed) return;
        _applicationResourcesDisposed = true;
        if (Services is not null)
        {
            Services.Bluetooth.StateChanged -= OnBluetoothStateChanged;
            Services.Media.StateChanged -= OnMediaStateChanged;
            Services.Dispose();
        }
        if (_mainInstance is not null)
            _mainInstance.Activated -= MainInstance_Activated;
        ReleaseSingleInstanceMutex();
    }

    private static bool IsBackgroundLaunch(string arguments) =>
        arguments.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Any(argument => argument.Equals("--background", StringComparison.OrdinalIgnoreCase));

    private static void WriteBootstrapError(Exception error)
    {
        try
        {
            var directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "TrikiMusicController",
                "Logs");
            Directory.CreateDirectory(directory);
            File.AppendAllText(
                Path.Combine(directory, "startup-errors.log"),
                $"[{DateTimeOffset.Now:O}] {error}\n\n");
        }
        catch (Exception loggingError) when (loggingError is IOException or UnauthorizedAccessException)
        {
            System.Diagnostics.Trace.WriteLine($"Nie udało się zapisać dziennika startu: {loggingError}");
        }
    }

    private void ReleaseSingleInstanceMutex()
    {
        if (_singleInstanceMutexDisposed) return;
        _singleInstanceMutexDisposed = true;
        if (_ownsSingleInstanceMutex) _singleInstanceMutex.ReleaseMutex();
        _singleInstanceMutex.Dispose();
    }
}
