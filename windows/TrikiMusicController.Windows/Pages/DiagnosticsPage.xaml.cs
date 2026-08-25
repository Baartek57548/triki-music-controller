using Microsoft.UI.Xaml.Controls;

namespace TrikiMusicController_Windows.Pages;

public sealed partial class DiagnosticsPage : Page
{
    public DiagnosticsPage()
    {
        InitializeComponent();
        DataContext = App.Services.ViewModel;
    }
}
