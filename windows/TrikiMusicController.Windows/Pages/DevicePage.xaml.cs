using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using TrikiMusicController_Windows.ViewModels;

namespace TrikiMusicController_Windows.Pages;

public sealed partial class DevicePage : Page
{
    private MainViewModel ViewModel => App.Services.ViewModel;

    public DevicePage()
    {
        InitializeComponent();
        DataContext = ViewModel;
    }

    private async void Scan_Click(object sender, RoutedEventArgs e) => await RunAsync(ViewModel.ScanAsync);
    private async void Connect_Click(object sender, RoutedEventArgs e) => await RunAsync(ViewModel.ConnectSelectedAsync);
    private async void Disconnect_Click(object sender, RoutedEventArgs e) => await RunAsync(ViewModel.DisconnectAsync);
    private async void Forget_Click(object sender, RoutedEventArgs e) => await RunAsync(ViewModel.ForgetAsync);
    private async void Led_Click(object sender, RoutedEventArgs e) => await RunAsync(() => ViewModel.SetLedAsync(((AppBarToggleButton)sender).IsChecked == true));

    private async Task RunAsync(Func<Task> action)
    {
        try { await action(); }
        catch (Exception error)
        {
            var dialog = new ContentDialog { Title = "Bluetooth", Content = error.Message, CloseButtonText = "OK", XamlRoot = XamlRoot };
            await dialog.ShowAsync();
        }
    }
}
