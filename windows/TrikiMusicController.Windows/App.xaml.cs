using Microsoft.UI.Xaml;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.Windows.AppLifecycle;
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
            _window.Closed += Window_Closed;
            _trayIcon = new TrayIconService(
                _dispatcherQueue,
                Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"),
                ShowMainWindow,
                Shutdown);

            if (IsBackgroundLaunch(args.Arguments))
            {
                _window.AppWindow.IsShownInSwitchers = false;
                await Services.ViewModel.InitializeAsync();
            }
            else
            {
                ShowMainWindow();
            }
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
            args.Data is not ILaunchActivatedEventArgs launch ||
            IsBackgroundLaunch(launch.Arguments))
            return;
        _dispatcherQueue?.TryEnqueue(ShowMainWindow);
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

    private void Window_Closed(object sender, WindowEventArgs args)
    {
        _trayIcon?.Dispose();
        _trayIcon = null;
        _window = null;
        DisposeApplicationResources();
    }

    private void DisposeApplicationResources()
    {
        if (_applicationResourcesDisposed) return;
        _applicationResourcesDisposed = true;
        Services?.Dispose();
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
