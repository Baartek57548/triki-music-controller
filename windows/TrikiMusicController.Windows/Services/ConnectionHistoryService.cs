using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Channels;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed record ConnectionHistoryEntry(DateTimeOffset Timestamp, TrikiConnectionState State, string Message, string? DeviceName)
{
    [JsonIgnore] public string TimeDisplay => Timestamp.ToLocalTime().ToString("dd.MM.yyyy HH:mm:ss");
    [JsonIgnore] public string Details => string.IsNullOrWhiteSpace(DeviceName) ? Message : $"{DeviceName} • {Message}";
}

public sealed class ConnectionHistoryService : IDisposable, IAsyncDisposable
{
    public const int MaximumEntries = 200;
    private readonly object _sync = new();
    private readonly SemaphoreSlim _fileGate = new(1, 1);
    private readonly Channel<bool> _saves = Channel.CreateBounded<bool>(new BoundedChannelOptions(1)
        { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
    private readonly string _path;
    private readonly JsonSerializerOptions _json = new() { WriteIndented = true, Converters = { new JsonStringEnumConverter() } };
    private readonly List<ConnectionHistoryEntry> _entries = [];
    private readonly Task _worker;
    private bool _disposed;
    public event EventHandler? Changed;
    public string? PersistenceError { get; private set; }
    public Task Ready { get; }
    public IReadOnlyList<ConnectionHistoryEntry> Entries { get { lock (_sync) return _entries.ToArray(); } }

    public ConnectionHistoryService(string? directory = null)
    {
        _path = Path.Combine(directory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TrikiMusicController"), "connection-history.json");
        Ready = LoadAsync();
        _worker = RunWriterAsync();
    }

    public void RecordTransition(BluetoothSnapshot previous, BluetoothSnapshot current, string? reason = null)
    {
        if (reason is null && previous.ConnectionState == current.ConnectionState &&
            previous.ErrorMessage == current.ErrorMessage && previous.ConnectedDevice?.BluetoothAddress == current.ConnectedDevice?.BluetoothAddress) return;
        Record(new ConnectionHistoryEntry(DateTimeOffset.UtcNow, current.ConnectionState,
            reason ?? current.ErrorMessage ?? Describe(current.ConnectionState), current.ConnectedDevice?.Name ?? previous.ConnectedDevice?.Name));
    }

    public void Record(ConnectionHistoryEntry entry)
    {
        if (_disposed) return;
        var safe = Sanitize(entry);
        if (safe is null) return;
        lock (_sync)
        {
            _entries.Insert(0, safe);
            if (_entries.Count > MaximumEntries) _entries.RemoveRange(MaximumEntries, _entries.Count - MaximumEntries);
        }
        Changed?.Invoke(this, EventArgs.Empty);
        _saves.Writer.TryWrite(true);
    }

    public async Task ClearAsync()
    {
        await Ready.ConfigureAwait(false);
        lock (_sync) _entries.Clear();
        Changed?.Invoke(this, EventArgs.Empty);
        await FlushAsync().ConfigureAwait(false);
    }

    public async Task ExportAsync(string destination)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destination);
        await Ready.ConfigureAwait(false);
        await WriteAtomicallyAsync(Path.GetFullPath(destination), Entries).ConfigureAwait(false);
    }

    public async Task FlushAsync()
    {
        await Ready.ConfigureAwait(false);
        await _fileGate.WaitAsync().ConfigureAwait(false);
        try { await WriteAtomicallyAsync(_path, Entries).ConfigureAwait(false); }
        finally { _fileGate.Release(); }
    }

    private async Task LoadAsync()
    {
        try
        {
            if (!File.Exists(_path)) return;
            if (new FileInfo(_path).Length > 1_000_000) throw new InvalidDataException("Historia połączeń przekracza dozwolony rozmiar.");
            await using var stream = File.OpenRead(_path);
            var loaded = await JsonSerializer.DeserializeAsync<List<ConnectionHistoryEntry?>>(stream, _json).ConfigureAwait(false) ?? [];
            lock (_sync)
            {
                var merged = _entries.Concat(loaded.Select(Sanitize).OfType<ConnectionHistoryEntry>()).OrderByDescending(e => e.Timestamp).Take(MaximumEntries).ToArray();
                _entries.Clear();
                _entries.AddRange(merged);
            }
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            PersistenceError = $"Nie można odczytać historii: {error.Message}";
            try { if (File.Exists(_path) && new FileInfo(_path).Length <= 1_000_000) File.Copy(_path, _path + ".corrupt", overwrite: true); }
            catch (Exception backupError) when (backupError is IOException or UnauthorizedAccessException)
            { System.Diagnostics.Trace.WriteLine(backupError); }
        }
        finally { Changed?.Invoke(this, EventArgs.Empty); }
    }

    private async Task RunWriterAsync()
    {
        await Ready.ConfigureAwait(false);
        await foreach (var request in _saves.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            try
            {
                await FlushAsync().ConfigureAwait(false);
                if (PersistenceError is not null) { PersistenceError = null; Changed?.Invoke(this, EventArgs.Empty); }
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                PersistenceError = $"Nie można zapisać historii: {error.Message}";
                Changed?.Invoke(this, EventArgs.Empty);
            }
        }
    }

    private async Task WriteAtomicallyAsync(string path, IReadOnlyList<ConnectionHistoryEntry> entries)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, useAsync: true))
            {
                await JsonSerializer.SerializeAsync(stream, entries, _json).ConfigureAwait(false);
                await stream.FlushAsync().ConfigureAwait(false);
            }
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            try { if (File.Exists(temporary)) File.Delete(temporary); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { System.Diagnostics.Trace.WriteLine(error); }
        }
    }

    private static ConnectionHistoryEntry? Sanitize(ConnectionHistoryEntry? entry) =>
        entry is null || !Enum.IsDefined(entry.State) || string.IsNullOrWhiteSpace(entry.Message) ? null : entry with
        { Message = entry.Message[..Math.Min(entry.Message.Length, 1_000)], DeviceName = entry.DeviceName?[..Math.Min(entry.DeviceName.Length, 160)] };

    private static string Describe(TrikiConnectionState state) => state switch
    {
        TrikiConnectionState.Scanning => "Skanowanie Bluetooth LE.",
        TrikiConnectionState.Connecting => "Rozpoczęto łączenie.",
        TrikiConnectionState.Ready => "Połączenie gotowe; odebrano poprawne dane IMU.",
        TrikiConnectionState.WaitingForWake or TrikiConnectionState.WaitingForDevice => "Oczekiwanie na dostępność kontrolera; automatyczne łączenie.",
        TrikiConnectionState.Error => "Błąd Bluetooth; szczegółowa przyczyna nie jest dostępna.",
        _ => "Połączenie zakończone.",
    };

    public void Dispose() { _disposed = true; _saves.Writer.TryComplete(); }
    public async ValueTask DisposeAsync() { Dispose(); await _worker.ConfigureAwait(false); }
}
