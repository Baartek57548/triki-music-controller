using Microsoft.UI.Xaml;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.Windows.AppLifecycle;
using TrikiMusicController_Windows.Services;
using ILaunchActivatedEventArgs = Windows.ApplicationModel.Activation.ILaunchActivatedEventArgs;

namespace TrikiMusicController_Windows;

public partial class App : Application
{
    private const string SingleInstanceMutexName = @"Local\TrikiMusicController.BAEDA449-C844-43F1-8888-AE0EFE5FBB13";
    private Window? _window;
    private readonly Mutex _singleInstanceMutex;
    private readonly bool _ownsSingleInstanceMutex;
    private bool _singleInstanceMutexDisposed;
    private AppInstance? _mainInstance;
    private DispatcherQueue? _dispatcherQueue;
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
            _window.Closed += (_, _) =>
            {
                Services.Dispose();
                if (_mainInstance is not null) _mainInstance.Activated -= MainInstance_Activated;
                ReleaseSingleInstanceMutex();
            };
            _window.Activate();
            if (args.Arguments.Split(' ', StringSplitOptions.RemoveEmptyEntries)
                .Any(argument => argument.Equals("--background", StringComparison.OrdinalIgnoreCase))
                && _window.AppWindow.Presenter is OverlappedPresenter presenter)
            {
                presenter.Minimize();
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
            launch.Arguments.Split(' ', StringSplitOptions.RemoveEmptyEntries)
                .Any(argument => argument.Equals("--background", StringComparison.OrdinalIgnoreCase)))
            return;
        _dispatcherQueue?.TryEnqueue(() =>
        {
            if (_window is null) return;
            _window.Activate();
            if (_window.AppWindow.Presenter is OverlappedPresenter presenter)
                presenter.Restore();
        });
    }

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
