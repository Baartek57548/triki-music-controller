using System.Text.Json;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController.Windows.Tests;

public sealed class ConnectionHistoryTests
{
    [Fact]
    public async Task GracefulClosePersistsThePendingEventWithoutAnExplicitFlush()
    {
        var directory = Path.Combine(Path.GetTempPath(), "TrikiHistory-" + Guid.NewGuid().ToString("N"));
        try
        {
            await using (var history = new ConnectionHistoryService(directory))
            {
                await history.Ready;
                history.Record(new(DateTimeOffset.UtcNow, TrikiConnectionState.Disconnected, "Zakończenie aplikacji.", null));
            }
            using var content = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(directory, "connection-history.json")));
            Assert.Equal(1, content.RootElement.GetArrayLength());
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Fact]
    public async Task WriteFailureRemainsVisibleWithoutLosingTheInMemoryEvent()
    {
        var directory = Path.Combine(Path.GetTempPath(), "TrikiHistory-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var blocked = Path.Combine(directory, "blocked");
        await File.WriteAllTextAsync(blocked, "not a directory");
        try
        {
            await using var history = new ConnectionHistoryService(blocked);
            await history.Ready;
            var failure = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            history.Changed += (_, _) => { if (history.PersistenceError is not null) failure.TrySetResult(true); };
            history.Record(new(DateTimeOffset.UtcNow, TrikiConnectionState.Ready, "Połączono.", "Triki"));
            await failure.Task.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.NotNull(history.PersistenceError);
            Assert.Single(history.Entries);
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public async Task HistoryPersistsBoundsExportsAndClearsWithoutRecordingTelemetry()
    {
        var directory = Path.Combine(Path.GetTempPath(), "TrikiHistory-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            await using (var history = new ConnectionHistoryService(directory))
            {
                await history.Ready;
                var snapshot = BluetoothSnapshot.Initial with { ConnectionState = TrikiConnectionState.Ready };
                history.RecordTransition(BluetoothSnapshot.Initial, snapshot);
                for (var index = 0; index < 500; index++) history.RecordTransition(snapshot, snapshot with { DecodedFrames = index });
                Assert.Single(history.Entries);
                for (var index = 0; index < 250; index++) history.Record(new(DateTimeOffset.UtcNow, TrikiConnectionState.Connecting, "Próba " + index, "Triki"));
                Assert.Equal(200, history.Entries.Count);
                Assert.Equal("Próba 249", history.Entries[0].Message);
                await history.FlushAsync();
                await history.ExportAsync(Path.Combine(directory, "export.json"));
                using var exported = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(directory, "export.json")));
                Assert.Equal(200, exported.RootElement.GetArrayLength());
            }
            await using (var reloaded = new ConnectionHistoryService(directory))
            {
                await reloaded.Ready;
                Assert.Equal(200, reloaded.Entries.Count);
                await reloaded.ClearAsync();
            }
            await using var cleared = new ConnectionHistoryService(directory);
            await cleared.Ready;
            Assert.Empty(cleared.Entries);
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public async Task CorruptHistoryIsBackedUpAndRecoversWithoutBlockingStartup()
    {
        var directory = Path.Combine(Path.GetTempPath(), "TrikiHistory-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            await File.WriteAllTextAsync(Path.Combine(directory, "connection-history.json"), "{invalid");
            await using var history = new ConnectionHistoryService(directory);
            await history.Ready;
            Assert.NotNull(history.PersistenceError);
            Assert.Empty(history.Entries);
            Assert.Single(Directory.GetFiles(directory, "*.corrupt"));
            history.RecordTransition(BluetoothSnapshot.Initial, BluetoothSnapshot.Initial, "Ręczne rozłączenie.");
            await history.FlushAsync();
            using var result = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(directory, "connection-history.json")));
            Assert.Equal(1, result.RootElement.GetArrayLength());
        }
        finally { Directory.Delete(directory, true); }
    }
}
