using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using TrikiMusicController_Windows.Pages;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows;

public sealed partial class MainWindow : Window
{
    private readonly SemaphoreSlim _updateGate = new(1, 1);
    private bool _automaticUpdateCheckStarted;

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

    private void Info_Click(object sender, RoutedEventArgs e)
    {
        Navigation.SelectedItem = null;
        if (RootFrame.CurrentSourcePageType != typeof(AboutPage))
            RootFrame.Navigate(typeof(AboutPage));
    }

    public async Task CheckForUpdatesAsync(bool showUpToDateMessage)
    {
        if (!await _updateGate.WaitAsync(0)) return;
        try
        {
            AppUpdateInfo? update;
            try
            {
                update = await App.Services.Updates.CheckAsync();
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
            if (await prompt.ShowAsync() != ContentDialogResult.Primary) return;

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
            };
            var progressOperation = progressDialog.ShowAsync();
            try
            {
                var progress = new Progress<UpdateDownloadProgress>(value =>
                {
                    progressBar.Value = value.Percentage;
                    progressText.Text = $"Pobrano {value.Percentage:0}% — po pobraniu zostanie sprawdzony SHA-256.";
                });
                var installer = await App.Services.Updates.DownloadAsync(update, progress);
                progressDialog.Hide();
                await progressOperation;
                App.Services.Updates.LaunchInstaller(installer);
                ((App)Microsoft.UI.Xaml.Application.Current).Shutdown();
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
        if (_automaticUpdateCheckStarted) return;
        _automaticUpdateCheckStarted = true;
        await CheckForUpdatesAsync(showUpToDateMessage: false);
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
        await dialog.ShowAsync();
    }
}
