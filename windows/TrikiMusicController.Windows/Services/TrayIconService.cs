using Microsoft.UI.Dispatching;

namespace TrikiMusicController_Windows.Services;

public sealed class TrayIconService : IDisposable
{
    private readonly DispatcherQueue _dispatcherQueue;
    private readonly Action _showWindow;
    private readonly Action _exitApplication;
    private readonly System.Drawing.Icon _icon;
    private readonly System.Windows.Forms.ContextMenuStrip _contextMenu;
    private readonly System.Windows.Forms.NotifyIcon _notifyIcon;
    private bool _disposed;

    public TrayIconService(
        DispatcherQueue dispatcherQueue,
        string iconPath,
        Action showWindow,
        Action exitApplication)
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
        _icon = new System.Drawing.Icon(iconPath);
        _contextMenu = new System.Windows.Forms.ContextMenuStrip();

        var openItem = _contextMenu.Items.Add("Otwórz Triki Music Controller");
        openItem.Click += OpenItem_Click;
        _contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        var exitItem = _contextMenu.Items.Add("Zakończ");
        exitItem.Click += ExitItem_Click;

        _notifyIcon = new System.Windows.Forms.NotifyIcon
        {
            Icon = _icon,
            Text = "Zappki — Triki Music Controller",
            ContextMenuStrip = _contextMenu,
            Visible = true,
        };
        _notifyIcon.DoubleClick += NotifyIcon_DoubleClick;
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

    private void ExitItem_Click(object? sender, EventArgs e) => Dispatch(_exitApplication);

    private void Dispatch(Action callback)
    {
        if (_disposed) return;
        if (!_dispatcherQueue.TryEnqueue(() => callback()))
            System.Diagnostics.Trace.WriteLine("Nie udało się przekazać polecenia ikony Triki do wątku interfejsu.");
    }
}
