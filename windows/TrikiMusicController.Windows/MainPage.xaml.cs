using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Pages;
using TrikiMusicController_Windows.ViewModels;

namespace TrikiMusicController_Windows;

public sealed partial class MainPage : Page
{
    private MainViewModel ViewModel => App.Services.ViewModel;

    public MainPage()
    {
        InitializeComponent();
        DataContext = ViewModel;
        Loaded += MainPage_Loaded;
    }

    private async void MainPage_Loaded(object sender, RoutedEventArgs e)
    {
        try { await ViewModel.InitializeAsync(); }
        catch (Exception error) { await ShowErrorAsync(error.Message); }
    }

    private void Device_Click(object sender, RoutedEventArgs e)
    {
        var mainWindow = (Application.Current as App)?.MainWindow;
        if (mainWindow is not null)
        {
            mainWindow.NavigateToTag("device");
        }
        else
        {
            Frame.Navigate(typeof(DevicePage));
        }
    }

    private async void Previous_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(MediaAction.Previous);
    private async void PlayPause_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(MediaAction.PlayPause);
    private async void Next_Click(object sender, RoutedEventArgs e) => await ExecuteAsync(MediaAction.Next);

    private async Task ExecuteAsync(MediaAction action)
    {
        try { await ViewModel.ExecuteMediaActionAsync(action); }
        catch (Exception error) { await ShowErrorAsync(error.Message); }
    }

    private async Task ShowErrorAsync(string message)
    {
        var dialog = new ContentDialog { Title = "Triki Music Controller", Content = message, CloseButtonText = "OK", XamlRoot = XamlRoot };
        await dialog.ShowAsync();
    }
}
