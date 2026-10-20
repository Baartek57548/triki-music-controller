using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using TrikiMusicController_Windows.Pages;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController_Windows;

public sealed partial class MainWindow : Window
{
    private readonly SemaphoreSlim _updateGate = new(1, 1);
    private readonly SemaphoreSlim _dialogGate = new(1, 1);
    private readonly CancellationTokenSource _windowLifetime = new();
    private bool _automaticUpdateCheckStarted;
    private AppUpdateInfo? _pendingStartupUpdate;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon("Assets/AppIcon.ico");
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 780));
        ApplyTheme(App.Services.Settings.Current.Theme);
        RootFrame.Navigate(typeof(MainPage));
        Navigation.SelectedItem = HomeItem;
        Activated += MainWindow_Activated;
        Closed += MainWindow_Closed;
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        Activated -= MainWindow_Activated;
        Closed -= MainWindow_Closed;
        _windowLifetime.Cancel();
    }

    public void ApplyTheme(string theme)
    {
        RootGrid.RequestedTheme = theme switch
        {
            "Light" => ElementTheme.Light,
            "Dark" => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
    }

    private void Navigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (!args.IsSettingsSelected && args.SelectedItemContainer is null) return;

        var pageType = args.IsSettingsSelected
            ? typeof(SettingsPage)
            : args.SelectedItemContainer?.Tag?.ToString() switch
            {
                "device" => typeof(DevicePage),
                "controls" => typeof(ControlsPage),
                _ => typeof(MainPage),
            };
        if (RootFrame.CurrentSourcePageType != pageType) RootFrame.Navigate(pageType);
    }

    public void NavigateToTag(string tag)
    {
        switch (tag)
        {
            case "device":
                Navigation.SelectedItem = DeviceItem;
                break;
            case "controls":
                Navigation.SelectedItem = ControlsItem;
                break;
            case "settings":
                Navigation.SelectedItem = Navigation.SettingsItem;
                break;
            case "about":
                Navigation.SelectedItem = null;
                if (RootFrame.CurrentSourcePageType != typeof(AboutPage))
                    RootFrame.Navigate(typeof(AboutPage));
                break;
            default:
                Navigation.SelectedItem = HomeItem;
                break;
        }
    }

    private void Info_Click(object sender, RoutedEventArgs e)
    {
        NavigateToTag("about");
    }

    public async Task CheckForUpdatesAsync(bool showUpToDateMessage)
    {
        if (!await _updateGate.WaitAsync(0)) return;
        try
        {
            AppUpdateInfo? update;
            try
            {
                update = _pendingStartupUpdate ?? await App.Services.Updates.CheckAsync(_windowLifetime.Token);
                _pendingStartupUpdate = null;
            }
            catch (Exception error)
            {
                if (showUpToDateMessage)
                    await ShowMessageAsync("Nie udało się sprawdzić aktualizacji", error.Message);
                else
                    System.Diagnostics.Debug.WriteLine($"Automatyczne sprawdzanie aktualizacji nie powiodło się: {error}");
                return;
            }

            if (update is null)
            {
                if (showUpToDateMessage)
                    await ShowMessageAsync("Aktualizacje", $"Masz najnowszą wersję aplikacji ({AppInfo.Version}).");
                return;
            }

            var releaseNotes = string.IsNullOrWhiteSpace(update.ReleaseNotes)
                ? "Na GitHub opublikowano nowszą wersję aplikacji."
                : update.ReleaseNotes.Trim();
            var prompt = new ContentDialog
            {
                XamlRoot = RootGrid.XamlRoot,
                Title = $"Dostępna wersja {update.Version}",
                Content = new ScrollViewer
                {
                    MaxHeight = 320,
                    Content = new TextBlock
                    {
                        Text = releaseNotes,
                        TextWrapping = TextWrapping.Wrap,
                    },
                },
                PrimaryButtonText = "Pobierz i zainstaluj",
                CloseButtonText = "Później",
                DefaultButton = ContentDialogButton.Primary,
            };
            if (await ShowDialogAsync(prompt) != ContentDialogResult.Primary) return;

            var progressBar = new ProgressBar { Minimum = 0, Maximum = 100, Value = 0, Width = 360 };
            var progressText = new TextBlock { Text = "Przygotowywanie pobierania…", TextWrapping = TextWrapping.Wrap };
            var progressDialog = new ContentDialog
            {
                XamlRoot = RootGrid.XamlRoot,
                Title = "Pobieranie bezpiecznej aktualizacji",
                Content = new StackPanel
                {
                    Spacing = 12,
                    Children = { progressText, progressBar },
                },
                CloseButtonText = "Anuluj",
            };
            using var downloadCancellation = CancellationTokenSource.CreateLinkedTokenSource(_windowLifetime.Token);
            progressDialog.CloseButtonClick += (_, _) => downloadCancellation.Cancel();
            var progressOperation = ShowDialogAsync(progressDialog);
            try
            {
                var progress = new Progress<UpdateDownloadProgress>(value =>
                {
                    progressBar.Value = value.Percentage;
                    progressText.Text = $"Pobrano {value.Percentage:0}% — po pobraniu zostanie sprawdzony SHA-256.";
                });
                var installer = await App.Services.Updates.DownloadAsync(update, progress, downloadCancellation.Token);
                progressDialog.Hide();
                await progressOperation;
                App.Services.Updates.LaunchInstaller(installer, silent: true);
                ((App)Application.Current).Shutdown();
            }
            catch (OperationCanceledException)
            {
                progressDialog.Hide();
                await progressOperation;
            }
            catch (Exception error)
            {
                progressDialog.Hide();
                await progressOperation;
                await ShowMessageAsync("Aktualizacja nie została zainstalowana", error.Message);
            }
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Obsługa aktualizacji nie powiodła się: {error}");
            if (showUpToDateMessage)
            {
                try
                {
                    await ShowMessageAsync("Błąd aktualizacji", error.Message);
                }
                catch (Exception dialogError)
                {
                    System.Diagnostics.Debug.WriteLine($"Nie udało się pokazać komunikatu aktualizacji: {dialogError}");
                }
            }
        }
        finally
        {
            _updateGate.Release();
        }
    }

    private async void MainWindow_Activated(object sender, WindowActivatedEventArgs args)
    {
        if (args.WindowActivationState == WindowActivationState.Deactivated) return;
        if (_pendingStartupUpdate is not null)
        {
            await CheckForUpdatesAsync(showUpToDateMessage: false);
            return;
        }
        await CheckForUpdatesOnStartupAsync();
    }

    public async Task CheckForUpdatesOnStartupAsync()
    {
        if (_automaticUpdateCheckStarted || _windowLifetime.IsCancellationRequested) return;
        _automaticUpdateCheckStarted = true;
        try
        {
            _pendingStartupUpdate = await App.Services.Updates.CheckAsync(_windowLifetime.Token);
            if (_pendingStartupUpdate is { } update)
            {
                ((App)Application.Current).NotifyUpdateAvailable(update.Version);
                if (AppWindow.IsVisible) await CheckForUpdatesAsync(showUpToDateMessage: false);
            }
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Sprawdzanie aktualizacji przy starcie: {error}");
        }
    }

    public async Task ShowWhatsNewDialogAsync(ReleaseHighlight? specificRelease = null)
    {
        var release = specificRelease ?? WhatsNewService.GetForCurrentVersion();
        if (release is null) return;

        var panel = new StackPanel { Spacing = 14, MaxWidth = 520 };
        panel.Children.Add(new TextBlock
        {
            Text = release.Title,
            Style = (Style)Application.Current.Resources["SectionTitleStyle"],
            TextWrapping = TextWrapping.Wrap,
        });

        var highlightsList = new StackPanel { Spacing = 10 };
        foreach (var item in release.Highlights)
        {
            var row = new Grid { ColumnSpacing = 10 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var bullet = new FontIcon
            {
                Glyph = "\uE73E",
                FontSize = 12,
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"],
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, 4, 0, 0),
            };
            Grid.SetColumn(bullet, 0);

            var text = new TextBlock
            {
                Text = item,
                TextWrapping = TextWrapping.Wrap,
            };
            Grid.SetColumn(text, 1);

            row.Children.Add(bullet);
            row.Children.Add(text);
            highlightsList.Children.Add(row);
        }

        panel.Children.Add(highlightsList);

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = $"Co nowego w wersji {release.Version}",
            Content = new ScrollViewer
            {
                MaxHeight = 420,
                Content = panel,
            },
            PrimaryButtonText = "Rozumiem",
            DefaultButton = ContentDialogButton.Primary,
        };

        try
        {
            await ShowDialogAsync(dialog);
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Nie udało się wyświetlić okna Co nowego: {error}");
        }
    }

    private async Task ShowMessageAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = title,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            CloseButtonText = "OK",
        };
        await ShowDialogAsync(dialog);
    }

    private async Task<ContentDialogResult> ShowDialogAsync(ContentDialog dialog)
    {
        await _dialogGate.WaitAsync(_windowLifetime.Token);
        try
        {
            return await dialog.ShowAsync();
        }
        finally
        {
            _dialogGate.Release();
        }
    }
}
