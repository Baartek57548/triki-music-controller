using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace TrikiMusicController_Windows.Views;

public sealed class CompactHudWindow : Window
{
    private readonly DispatcherTimer _hideTimer = new();
    private readonly TextBlock _iconText;
    private readonly TextBlock _titleText;
    private readonly TextBlock _subtitleText;
    private readonly TextBlock _valueText;
    private readonly ProgressBar _progressBar;

    public CompactHudWindow()
    {
        Title = "Triki HUD";

        _hideTimer.Interval = TimeSpan.FromMilliseconds(1500);
        _hideTimer.Tick += (s, e) =>
        {
            _hideTimer.Stop();
            AppWindow.Hide();
        };

        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsAlwaysOnTop = true;
            presenter.IsResizable = false;
            presenter.IsMinimizable = false;
            presenter.IsMaximizable = false;
            presenter.SetBorderAndTitleBar(false, false);
        }

        AppWindow.Resize(new SizeInt32(320, 88));

        _iconText = new TextBlock
        {
            Text = "VOL",
            FontSize = 11,
            FontWeight = Microsoft.UI.Text.FontWeights.Bold,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };

        var iconBorder = new Border
        {
            Width = 36,
            Height = 36,
            CornerRadius = new CornerRadius(10),
            VerticalAlignment = VerticalAlignment.Center,
            Background = Application.Current.Resources["LayerFillColorDefaultBrush"] as Brush,
            Child = _iconText,
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
            Children = { _titleText, _subtitleText },
        };

        _valueText = new TextBlock
        {
            Text = "50%",
            FontSize = 14,
            FontWeight = Microsoft.UI.Text.FontWeights.Bold,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(4, 0, 2, 0),
        };

        _progressBar = new ProgressBar
        {
            Minimum = 0,
            Maximum = 100,
            Value = 50,
            Height = 5,
            CornerRadius = new CornerRadius(2.5),
            Margin = new Thickness(0, 4, 0, 0),
        };

        var contentGrid = new Grid
        {
            ColumnSpacing = 12,
            RowSpacing = 6,
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
            Margin = new Thickness(6),
            Background = Application.Current.Resources["SystemControlBackgroundChromeMediumBrush"] as Brush,
            BorderBrush = Application.Current.Resources["CardStrokeColorDefaultBrush"] as Brush,
            BorderThickness = new Thickness(1),
            Child = contentGrid,
        };

        Content = card;
        PositionWindow();
    }

    private void PositionWindow()
    {
        try
        {
            var displayArea = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary);
            if (displayArea is not null)
            {
                var workArea = displayArea.WorkArea;
                var x = workArea.X + (workArea.Width - 320) / 2;
                var y = workArea.Y + workArea.Height - 110;
                AppWindow.Move(new PointInt32(x, y));
            }
        }
        catch
        {
            // Fallback default position
        }
    }

    public void ShowVolume(int volumePercent, string trackTitle, string artist)
    {
        _iconText.Text = "VOL";
        _titleText.Text = string.IsNullOrWhiteSpace(trackTitle) || trackTitle == "—" ? "Głośność" : trackTitle;
        _subtitleText.Text = string.IsNullOrWhiteSpace(artist) || artist == "—" ? "System audio" : artist;
        _valueText.Text = $"{volumePercent}%";
        _progressBar.Value = volumePercent;

        PositionWindow();
        AppWindow.Show();
        _hideTimer.Stop();
        _hideTimer.Start();
    }

    public void ShowBrightness(int brightnessPercent)
    {
        _iconText.Text = "JAS";
        _titleText.Text = "Jasność ekranu";
        _subtitleText.Text = "Pozycja 90°";
        _valueText.Text = $"{brightnessPercent}%";
        _progressBar.Value = brightnessPercent;

        PositionWindow();
        AppWindow.Show();
        _hideTimer.Stop();
        _hideTimer.Start();
    }
}
