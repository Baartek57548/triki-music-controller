using Microsoft.UI.Xaml.Controls;

namespace TrikiMusicController_Windows.Pages;

public sealed partial class ControlsPage : Page
{
    public ControlsPage()
    {
        InitializeComponent();
        DataContext = App.Services.ViewModel;
    }
}
