using Microsoft.UI.Xaml.Controls;

namespace TrikiMusicController_Windows.Pages;

public sealed partial class SettingsPage : Page
{
    public SettingsPage()
    {
        InitializeComponent();
        DataContext = App.Services.ViewModel;
    }
}
