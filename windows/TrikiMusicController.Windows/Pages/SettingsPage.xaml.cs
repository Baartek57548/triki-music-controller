using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace TrikiMusicController_Windows.Pages;

public sealed partial class SettingsPage : Page
{
    public SettingsPage()
    {
        InitializeComponent();
        DataContext = App.Services.ViewModel;
    }

    private void Diagnostics_Click(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(DiagnosticsPage));

    private void About_Click(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(AboutPage));

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        if (((App)Microsoft.UI.Xaml.Application.Current).MainWindow is { } window)
            await window.CheckForUpdatesAsync(showUpToDateMessage: true);
    }
}
