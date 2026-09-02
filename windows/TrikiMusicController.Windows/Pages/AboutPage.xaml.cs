using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace TrikiMusicController_Windows.Pages;

public sealed partial class AboutPage : Page
{
    public AboutPage()
    {
        InitializeComponent();
        DataContext = App.Services.ViewModel;
    }

    private async void GitHub_Click(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchUriAsync(new Uri("https://github.com/Baartek57548/triki-music-controller"));

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        if (((App)Microsoft.UI.Xaml.Application.Current).MainWindow is { } window)
            await window.CheckForUpdatesAsync(showUpToDateMessage: true);
    }

    private async void WhatsNew_Click(object sender, RoutedEventArgs e)
    {
        if (((App)Microsoft.UI.Xaml.Application.Current).MainWindow is { } window)
            await window.ShowWhatsNewDialogAsync();
    }
}
