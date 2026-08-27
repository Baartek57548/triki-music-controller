using System.Net;
using System.Security.Cryptography;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController_Windows.Tests;

public sealed class UpdateServiceTests
{
    [Fact]
    public async Task DownloadAsync_ClosesTemporaryFileBeforeValidationAndMove()
    {
        var payload = CreateInstallerPayload();
        var directory = CreateTemporaryDirectory();
        try
        {
            using var service = new UpdateService(new StaticResponseHandler(payload), directory);
            var update = CreateUpdate(payload);

            var result = await service.DownloadAsync(update);

            Assert.Equal(Path.Combine(directory, update.AssetName), result);
            Assert.Equal(payload, await File.ReadAllBytesAsync(result));
            Assert.False(File.Exists(result + ".download"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task DownloadAsync_ReplacesPreviouslyDownloadedInstaller()
    {
        var payload = CreateInstallerPayload();
        var directory = CreateTemporaryDirectory();
        try
        {
            var update = CreateUpdate(payload);
            var destination = Path.Combine(directory, update.AssetName);
            await File.WriteAllBytesAsync(destination, [0x4D, 0x5A, 0x00]);
            using var service = new UpdateService(new StaticResponseHandler(payload), directory);

            var result = await service.DownloadAsync(update);

            Assert.Equal(payload, await File.ReadAllBytesAsync(result));
            Assert.False(File.Exists(result + ".download"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static byte[] CreateInstallerPayload()
    {
        var payload = new byte[128 * 1024];
        RandomNumberGenerator.Fill(payload);
        payload[0] = (byte)'M';
        payload[1] = (byte)'Z';
        return payload;
    }

    private static AppUpdateInfo CreateUpdate(byte[] payload) => new(
        new Version(9, 9, 9),
        "v9.9.9",
        string.Empty,
        new Uri("https://github.com/Baartek57548/triki-music-controller/releases/tag/v9.9.9"),
        new Uri("https://github.com/Baartek57548/triki-music-controller/releases/download/v9.9.9/triki-music-controller-windows-v9.9.9-setup.exe"),
        "triki-music-controller-windows-v9.9.9-setup.exe",
        payload.LongLength,
        Convert.ToHexString(SHA256.HashData(payload)));

    private static string CreateTemporaryDirectory()
    {
        var path = Path.Combine(Path.GetTempPath(), "TrikiMusicController.Tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }

    private sealed class StaticResponseHandler(byte[] payload) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var response = new HttpResponseMessage(HttpStatusCode.OK)
            {
                RequestMessage = request,
                Content = new ByteArrayContent(payload),
            };
            response.Content.Headers.ContentLength = payload.LongLength;
            return Task.FromResult(response);
        }
    }
}
