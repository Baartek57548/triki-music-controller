using Microsoft.UI.Dispatching;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class TrayIconService : IDisposable
{
    private readonly DispatcherQueue _dispatcherQueue;
    private readonly Action _showWindow;
    private readonly Action _exitApplication;
    private readonly Action<MediaAction>? _executeMediaAction;
    private readonly Action? _toggleConnection;
    private readonly System.Drawing.Icon _icon;
    private readonly System.Windows.Forms.ContextMenuStrip _contextMenu;
    private readonly System.Windows.Forms.NotifyIcon _notifyIcon;
    private readonly System.Windows.Forms.ToolStripMenuItem _statusHeaderItem;
    private readonly System.Windows.Forms.ToolStripMenuItem _connectItem;
    private readonly System.Windows.Forms.ToolStripMenuItem _playPauseItem;
    private bool _disposed;

    public TrayIconService(
        DispatcherQueue dispatcherQueue,
        string iconPath,
        Action showWindow,
        Action exitApplication,
        Action<MediaAction>? executeMediaAction = null,
        Action? toggleConnection = null)
    {
        ArgumentNullException.ThrowIfNull(dispatcherQueue);
        ArgumentException.ThrowIfNullOrWhiteSpace(iconPath);
        ArgumentNullException.ThrowIfNull(showWindow);
        ArgumentNullException.ThrowIfNull(exitApplication);
        if (!File.Exists(iconPath))
            throw new FileNotFoundException("Nie znaleziono ikony zasobnika Triki.", iconPath);

        _dispatcherQueue = dispatcherQueue;
        _showWindow = showWindow;
        _exitApplication = exitApplication;
        _executeMediaAction = executeMediaAction;
        _toggleConnection = toggleConnection;
        _icon = new System.Drawing.Icon(iconPath);
        _contextMenu = new System.Windows.Forms.ContextMenuStrip();

        // 1. Otwórz okno
        var openItem = new System.Windows.Forms.ToolStripMenuItem("Otwórz Triki Music Controller");
        openItem.Font = new System.Drawing.Font(openItem.Font, System.Drawing.FontStyle.Bold);
        openItem.Click += OpenItem_Click;
        _contextMenu.Items.Add(openItem);

        _contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());

        // 2. Nagłówek statusu połączenia
        _statusHeaderItem = new System.Windows.Forms.ToolStripMenuItem("Triki: Brak połączenia") { Enabled = false };
        _contextMenu.Items.Add(_statusHeaderItem);

        // 3. Połącz / Rozłącz
        _connectItem = new System.Windows.Forms.ToolStripMenuItem("Połącz z Triki");
        _connectItem.Click += ConnectItem_Click;
        _contextMenu.Items.Add(_connectItem);

        _contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());

        // 4. Sterowanie mediami
        _playPauseItem = new System.Windows.Forms.ToolStripMenuItem("Odtwórz / Wstrzymaj");
        _playPauseItem.Click += (_, _) => DispatchMedia(MediaAction.PlayPause);
        _contextMenu.Items.Add(_playPauseItem);

        var nextItem = new System.Windows.Forms.ToolStripMenuItem("Następny utwór");
        nextItem.Click += (_, _) => DispatchMedia(MediaAction.Next);
        _contextMenu.Items.Add(nextItem);

        var prevItem = new System.Windows.Forms.ToolStripMenuItem("Poprzedni utwór");
        prevItem.Click += (_, _) => DispatchMedia(MediaAction.Previous);
        _contextMenu.Items.Add(prevItem);

        _contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());

        // 5. Zakończ
        var exitItem = new System.Windows.Forms.ToolStripMenuItem("Zakończ");
        exitItem.Click += ExitItem_Click;
        _contextMenu.Items.Add(exitItem);

        _notifyIcon = new System.Windows.Forms.NotifyIcon
        {
            Icon = _icon,
            Text = "Triki Music Controller",
            ContextMenuStrip = _contextMenu,
            Visible = true,
        };
        _notifyIcon.DoubleClick += NotifyIcon_DoubleClick;
    }

    public void UpdateStatus(string statusHeader, string tooltipText, bool isConnected, bool isPlaying)
    {
        if (_disposed) return;
        try
        {
            _statusHeaderItem.Text = statusHeader;
            _connectItem.Text = isConnected ? "Rozłącz Triki" : "Połącz z Triki";
            _playPauseItem.Text = isPlaying ? "Wstrzymaj" : "Odtwórz";

            var cleanTooltip = tooltipText.Trim();
            if (cleanTooltip.Length > 63)
                cleanTooltip = cleanTooltip[..60] + "…";

            _notifyIcon.Text = string.IsNullOrEmpty(cleanTooltip) ? "Triki Music Controller" : cleanTooltip;
        }
        catch (Exception error)
        {
            System.Diagnostics.Trace.WriteLine($"Nie udało się zaktualizować ikony w zasobniku: {error}");
        }
    }

    public void ShowToastNotification(string title, string message)
    {
        if (_disposed) return;
        try
        {
            _notifyIcon.ShowBalloonTip(3000, title, message, System.Windows.Forms.ToolTipIcon.Info);
        }
        catch (Exception error)
        {
            System.Diagnostics.Trace.WriteLine($"Nie udało się wyświetlić powiadomienia dymkowego: {error}");
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _notifyIcon.Visible = false;
        _notifyIcon.DoubleClick -= NotifyIcon_DoubleClick;
        _notifyIcon.Dispose();
        _contextMenu.Dispose();
        _icon.Dispose();
    }

    private void NotifyIcon_DoubleClick(object? sender, EventArgs e) => Dispatch(_showWindow);

    private void OpenItem_Click(object? sender, EventArgs e) => Dispatch(_showWindow);

    private void ConnectItem_Click(object? sender, EventArgs e)
    {
        if (_toggleConnection is not null)
            Dispatch(_toggleConnection);
    }

    private void DispatchMedia(MediaAction action)
    {
        if (_executeMediaAction is not null)
            Dispatch(() => _executeMediaAction(action));
    }

    private void ExitItem_Click(object? sender, EventArgs e) => Dispatch(_exitApplication);

    private void Dispatch(Action callback)
    {
        if (_disposed) return;
        if (!_dispatcherQueue.TryEnqueue(() => callback()))
            System.Diagnostics.Trace.WriteLine("Nie udało się przekazać polecenia ikony Triki do wątku interfejsu.");
    }
}
