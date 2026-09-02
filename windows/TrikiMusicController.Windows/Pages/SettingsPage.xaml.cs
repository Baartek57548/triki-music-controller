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

    private void About_Click(object sender, RoutedEventArgs e)
    {
        if (((App)Microsoft.UI.Xaml.Application.Current).MainWindow is { } window)
            window.NavigateToTag("about");
        else
            Frame.Navigate(typeof(AboutPage));
    }

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        if (((App)Microsoft.UI.Xaml.Application.Current).MainWindow is { } window)
            await window.CheckForUpdatesAsync(showUpToDateMessage: true);
    }

    private async void Permissions_Click(object sender, RoutedEventArgs e)
    {
        var panel = new StackPanel { Spacing = 14, MaxWidth = 480 };
        panel.Children.Add(new TextBlock
        {
            Text = "Uprawnienia i dostęp systemowy Windows",
            Style = (Style)Application.Current.Resources["SectionTitleStyle"],
            TextWrapping = TextWrapping.Wrap,
        });

        var items = new (string Icon, string Title, string Desc)[]
        {
            ("\uE702", "Bluetooth i urządzenia w pobliżu", "Wymagane do skanowania i odbierania strumienia danych telemetrycznych z kontrolera Triki przez BLE."),
            ("\uE767", "Kontrola sesji multimediów (GSMTC)", "Umożliwia odczyt tytułu, wykonawcy, okładki oraz sterowanie odtwarzaniem w Spotify, YouTube itp."),
            ("\uE706", "Regulacja jasności ekranu (WMI)", "Pozwala na płynną zmianę jasności monitora w pozycji 90° kapsla."),
            ("\uE7BA", "Dźwięki powiadomień", "Odtwarzanie krótkich sygnałów audio potwierdzających wykonanie gestu lub zmianę utworu."),
        };

        foreach (var (glyph, title, desc) in items)
        {
            var row = new Grid { ColumnSpacing = 12, Margin = new Thickness(0, 4, 0, 4) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var iconBorder = new Border
            {
                Width = 32,
                Height = 32,
                CornerRadius = new CornerRadius(16),
                Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SubtleFillColorSecondaryBrush"],
                VerticalAlignment = VerticalAlignment.Top,
                Child = new FontIcon
                {
                    Glyph = glyph,
                    FontSize = 14,
                    Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"],
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            };
            Grid.SetColumn(iconBorder, 0);

            var textStack = new StackPanel { Spacing = 2 };
            textStack.Children.Add(new TextBlock { Text = title, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
            textStack.Children.Add(new TextBlock { Text = desc, Style = (Style)Application.Current.Resources["MetadataTextStyle"], TextWrapping = TextWrapping.Wrap });
            Grid.SetColumn(textStack, 1);

            row.Children.Add(iconBorder);
            row.Children.Add(textStack);
            panel.Children.Add(row);
        }

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Uprawnienia systemowe",
            Content = new ScrollViewer { MaxHeight = 380, Content = panel },
            PrimaryButtonText = "Ustawienia Windows",
            CloseButtonText = "Zamknij",
            DefaultButton = ContentDialogButton.Close,
        };

        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
        {
            await Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:privacy-radios"));
        }
    }
}
