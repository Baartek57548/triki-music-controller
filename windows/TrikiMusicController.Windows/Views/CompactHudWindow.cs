using System.Runtime.InteropServices;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using WinRT.Interop;

namespace TrikiMusicController_Windows.Views;

public sealed class CompactHudWindow : Window
{
    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    private const int SwHide = 0;
    private const int SwShowNoActivate = 4;

    private readonly DispatcherTimer _hideTimer;
    private readonly FontIcon _fontIcon;
    private readonly TextBlock _titleText;
    private readonly TextBlock _subtitleText;
    private readonly TextBlock _valueText;
    private readonly ProgressBar _progressBar;
    private readonly IntPtr _hwnd;

    public CompactHudWindow()
    {
        Title = "Triki HUD";
        _hwnd = WindowNative.GetWindowHandle(this);

        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsAlwaysOnTop = true;
            presenter.IsResizable = false;
            presenter.IsMinimizable = false;
            presenter.IsMaximizable = false;
            presenter.SetBorderAndTitleBar(false, false);
        }

        AppWindow.Resize(new SizeInt32(330, 86));

        _hideTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(2000), // Dokładnie 2 sekundy bezczynności
        };
        _hideTimer.Tick += (s, e) =>
        {
            _hideTimer.Stop();
            HideHud();
        };

        _fontIcon = new FontIcon
        {
            Glyph = "\uE767",
            FontSize = 18,
            Foreground = Application.Current.Resources["AccentTextFillColorPrimaryBrush"] as Brush,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };

        var iconBorder = new Border
        {
            Width = 40,
            Height = 40,
            CornerRadius = new CornerRadius(12),
            VerticalAlignment = VerticalAlignment.Center,
            Background = Application.Current.Resources["AccentFillColorTertiaryBrush"] as Brush,
            Child = _fontIcon,
        };

        _titleText = new TextBlock
        {
            Text = "Głośność",
            FontSize = 13,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
        };

        _subtitleText = new TextBlock
        {
            Text = "System audio",
            FontSize = 11,
            Foreground = Application.Current.Resources["TextFillColorSecondaryBrush"] as Brush,
            TextTrimming = TextTrimming.CharacterEllipsis,
        };

        var textStack = new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            Spacing = 1,
            Children = { _titleText, _subtitleText },
        };

        _valueText = new TextBlock
        {
            Text = "50%",
            FontSize = 14,
            FontWeight = Microsoft.UI.Text.FontWeights.Bold,
            Foreground = Application.Current.Resources["AccentTextFillColorPrimaryBrush"] as Brush,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(4, 0, 2, 0),
        };

        _progressBar = new ProgressBar
        {
            Minimum = 0,
            Maximum = 100,
            Value = 50,
            Height = 4,
            CornerRadius = new CornerRadius(2),
            Margin = new Thickness(0, 4, 0, 0),
        };

        var contentGrid = new Grid
        {
            ColumnSpacing = 12,
            RowSpacing = 4,
        };
        contentGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        contentGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        contentGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        contentGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        contentGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        Grid.SetRowSpan(iconBorder, 2);
        Grid.SetColumn(iconBorder, 0);

        Grid.SetColumn(textStack, 1);
        Grid.SetRow(textStack, 0);

        Grid.SetColumn(_valueText, 2);
        Grid.SetRow(_valueText, 0);

        Grid.SetColumn(_progressBar, 1);
        Grid.SetColumnSpan(_progressBar, 2);
        Grid.SetRow(_progressBar, 1);

        contentGrid.Children.Add(iconBorder);
        contentGrid.Children.Add(textStack);
        contentGrid.Children.Add(_valueText);
        contentGrid.Children.Add(_progressBar);

        var card = new Border
        {
            CornerRadius = new CornerRadius(16),
            Padding = new Thickness(14, 10, 14, 10),
            Margin = new Thickness(4),
            Background = Application.Current.Resources["SystemControlBackgroundChromeMediumBrush"] as Brush,
            BorderBrush = Application.Current.Resources["CardStrokeColorDefaultBrush"] as Brush,
            BorderThickness = new Thickness(1),
            Child = contentGrid,
        };

        Content = card;
        HideHud();
    }

    private void PositionWindowOnRight()
    {
        try
        {
            var displayArea = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary);
            if (displayArea is not null)
            {
                var workArea = displayArea.WorkArea;
                // Prawa strona ekranu (32 px marginesu od prawej krawędzi, 80 px od dołu)
                var x = workArea.X + workArea.Width - 330 - 32;
                var y = workArea.Y + workArea.Height - 110;
                AppWindow.Move(new PointInt32(x, y));
            }
        }
        catch
        {
            // Fallback default position
        }
    }

    private void HideHud()
    {
        try
        {
            if (_hwnd != IntPtr.Zero)
            {
                ShowWindow(_hwnd, SwHide);
            }
            AppWindow.Hide();
        }
        catch
        {
            // Safety
        }
    }

    public void ShowVolume(int volumePercent, string trackTitle, string artist)
    {
        // Dynamiczna ikona głośnika Fluent w zależności od poziomu
        _fontIcon.Glyph = volumePercent switch
        {
            0 => "\uE74F",       // Wyciszenie (Mute)
            < 33 => "\uE993",    // Cicho (Volume 1)
            < 67 => "\uE994",    // Średnio (Volume 2)
            _ => "\uE995",       // Głośno (Volume 3)
        };

        _titleText.Text = string.IsNullOrWhiteSpace(trackTitle) || trackTitle == "—" ? "Głośność" : trackTitle;
        _subtitleText.Text = string.IsNullOrWhiteSpace(artist) || artist == "—" ? "System audio" : artist;
        _valueText.Text = $"{volumePercent}%";
        _progressBar.Value = volumePercent;

        PositionWindowOnRight();
        if (_hwnd != IntPtr.Zero)
        {
            ShowWindow(_hwnd, SwShowNoActivate);
        }
        else
        {
            AppWindow.Show();
        }

        _hideTimer.Stop();
        _hideTimer.Start();
    }

    public void ShowBrightness(int brightnessPercent)
    {
        _fontIcon.Glyph = "\uE706"; // Ikona słońca / jasności (Fluent Brightness)
        _titleText.Text = "Jasność ekranu";
        _subtitleText.Text = "Pozycja 90°";
        _valueText.Text = $"{brightnessPercent}%";
        _progressBar.Value = brightnessPercent;

        PositionWindowOnRight();
        if (_hwnd != IntPtr.Zero)
        {
            ShowWindow(_hwnd, SwShowNoActivate);
        }
        else
        {
            AppWindow.Show();
        }

        _hideTimer.Stop();
        _hideTimer.Start();
    }
}
