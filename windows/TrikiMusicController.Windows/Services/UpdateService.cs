using System.Buffers;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class UpdateService : IDisposable
{
    private const long MaximumMetadataBytes = 1 * 1024 * 1024;
    private const long MaximumInstallerBytes = 300 * 1024 * 1024;
    private const string InstallerPrefix = "triki-music-controller-windows-";
    private const string InstallerSuffix = "-setup.exe";
    private readonly HttpClient _client;
    private readonly string _updatesDirectory;
    private bool _disposed;

    public UpdateService()
        : this(CreateHttpHandler(), GetDefaultUpdatesDirectory())
    {
    }

    internal UpdateService(HttpMessageHandler handler, string updatesDirectory)
    {
        ArgumentNullException.ThrowIfNull(handler);
        ArgumentException.ThrowIfNullOrWhiteSpace(updatesDirectory);
        _client = new HttpClient(handler, disposeHandler: true)
        {
            Timeout = TimeSpan.FromMinutes(10),
        };
        _client.DefaultRequestHeaders.UserAgent.ParseAdd("TrikiMusicController-Windows/" + AppInfo.Version);
        _client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        _client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        _updatesDirectory = Path.GetFullPath(updatesDirectory);
    }

    private static SocketsHttpHandler CreateHttpHandler() => new()
    {
        AutomaticDecompression = DecompressionMethods.All,
        AllowAutoRedirect = true,
        MaxAutomaticRedirections = 5,
        ConnectTimeout = TimeSpan.FromSeconds(15),
    };

    private static string GetDefaultUpdatesDirectory() => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "TrikiMusicController",
            "Updates");

    public async Task<AppUpdateInfo?> CheckAsync(CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        var endpoint = new Uri($"https://api.github.com/repos/{AppInfo.GitHubOwner}/{AppInfo.GitHubRepository}/releases/latest");
        using var response = await _client.GetAsync(endpoint, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (response.StatusCode == HttpStatusCode.NotFound)
            return null;
        if (!response.IsSuccessStatusCode)
            throw new UpdateException($"GitHub zwrócił kod HTTP {(int)response.StatusCode} podczas sprawdzania aktualizacji.");
        if (response.Content.Headers.ContentLength is > MaximumMetadataBytes)
            throw new UpdateException("Metadane wydania przekraczają bezpieczny limit rozmiaru.");

        await using var network = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        await using var bounded = new MemoryStream();
        await CopyWithLimitAsync(network, bounded, MaximumMetadataBytes, null, cancellationToken).ConfigureAwait(false);
        bounded.Position = 0;
        GitHubRelease release;
        try
        {
            release = await JsonSerializer.DeserializeAsync<GitHubRelease>(bounded, cancellationToken: cancellationToken)
                .ConfigureAwait(false)
                ?? throw new JsonException("Brak obiektu wydania.");
        }
        catch (JsonException error)
        {
            throw new UpdateException("GitHub zwrócił nieprawidłowe metadane wydania.", error);
        }

        if (release.Draft || release.Prerelease)
            return null;
        var releaseVersion = ParseVersion(release.TagName);
        var currentVersion = ParseVersion(AppInfo.Version);
        if (releaseVersion <= currentVersion)
            return null;

        var candidates = release.Assets
            .Where(asset => asset.Name.StartsWith(InstallerPrefix, StringComparison.OrdinalIgnoreCase)
                && asset.Name.EndsWith(InstallerSuffix, StringComparison.OrdinalIgnoreCase))
            .ToArray();
        if (candidates.Length != 1)
            throw new UpdateException($"Wydanie {release.TagName} nie zawiera dokładnie jednego instalatora Windows.");

        var asset = candidates[0];
        if (asset.Size <= 0 || asset.Size > MaximumInstallerBytes)
            throw new UpdateException("Instalator ma nieprawidłowy lub zbyt duży rozmiar.");
        var downloadUri = ValidateDownloadUri(asset.BrowserDownloadUrl, release.TagName, asset.Name);
        var releasePage = ValidateReleasePage(release.HtmlUrl, release.TagName);
        var sha256 = ParseSha256(asset.Digest);
        return new AppUpdateInfo(
            releaseVersion,
            release.TagName,
            release.Body ?? string.Empty,
            releasePage,
            downloadUri,
            asset.Name,
            asset.Size,
            sha256);
    }

    public async Task<string> DownloadAsync(
        AppUpdateInfo update,
        IProgress<UpdateDownloadProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ValidateDownloadRequest(update);
        Directory.CreateDirectory(_updatesDirectory);
        var destination = Path.Combine(_updatesDirectory, update.AssetName);
        var temporary = destination + ".download";
        try
        {
            long downloadedLength;
            using (var response = await _client.GetAsync(update.DownloadUri, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                       .ConfigureAwait(false))
            {
                if (!response.IsSuccessStatusCode)
                    throw new UpdateException($"Pobieranie instalatora zakończyło się kodem HTTP {(int)response.StatusCode}.");
                var declaredLength = response.Content.Headers.ContentLength;
                if (declaredLength is <= 0 or > MaximumInstallerBytes || declaredLength != update.SizeBytes)
                    throw new UpdateException("Rozmiar instalatora nie zgadza się z metadanymi wydania.");

                await using (var source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false))
                await using (var target = new FileStream(
                                 temporary,
                                 FileMode.Create,
                                 FileAccess.Write,
                                 FileShare.None,
                                 64 * 1024,
                                 FileOptions.Asynchronous | FileOptions.SequentialScan))
                {
                    await CopyWithLimitAsync(source, target, update.SizeBytes, progress, cancellationToken).ConfigureAwait(false);
                    await target.FlushAsync(cancellationToken).ConfigureAwait(false);
                    downloadedLength = target.Length;
                }
            }

            if (downloadedLength != update.SizeBytes)
                throw new UpdateException("Pobrany instalator ma inny rozmiar niż plik opublikowany na GitHub.");

            var actualHash = await CalculateSha256Async(temporary, cancellationToken).ConfigureAwait(false);
            if (!actualHash.Equals(update.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new UpdateException("Suma SHA-256 instalatora nie zgadza się z metadanymi GitHub.");
            await ValidatePortableExecutableAsync(temporary, cancellationToken).ConfigureAwait(false);
            await MoveCompletedDownloadAsync(temporary, destination, cancellationToken).ConfigureAwait(false);
            return destination;
        }
        catch (UpdateException)
        {
            TryDeleteFile(temporary);
            throw;
        }
        catch (IOException error)
        {
            TryDeleteFile(temporary);
            throw new UpdateException("Nie udało się zapisać instalatora aktualizacji. Zamknij inne uruchomione instalatory i spróbuj ponownie.", error);
        }
        catch (UnauthorizedAccessException error)
        {
            TryDeleteFile(temporary);
            throw new UpdateException("Windows odmówił dostępu do katalogu aktualizacji.", error);
        }
        catch
        {
            TryDeleteFile(temporary);
            throw;
        }
    }

    private static void ValidateDownloadRequest(AppUpdateInfo update)
    {
        ArgumentNullException.ThrowIfNull(update);
        if (string.IsNullOrWhiteSpace(update.AssetName)
            || !Path.GetFileName(update.AssetName).Equals(update.AssetName, StringComparison.Ordinal)
            || !update.AssetName.StartsWith(InstallerPrefix, StringComparison.OrdinalIgnoreCase)
            || !update.AssetName.EndsWith(InstallerSuffix, StringComparison.OrdinalIgnoreCase))
            throw new UpdateException("Nazwa instalatora aktualizacji jest nieprawidłowa.");
        if (update.SizeBytes is <= 0 or > MaximumInstallerBytes)
            throw new UpdateException("Instalator ma nieprawidłowy lub zbyt duży rozmiar.");
        if (string.IsNullOrWhiteSpace(update.Sha256)
            || update.Sha256.Length != 64
            || update.Sha256.Any(character => !Uri.IsHexDigit(character)))
            throw new UpdateException("Suma SHA-256 instalatora ma nieprawidłowy format.");
    }

    private static async Task MoveCompletedDownloadAsync(
        string temporary,
        string destination,
        CancellationToken cancellationToken)
    {
        const int maximumAttempts = 5;
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                File.Move(temporary, destination, overwrite: true);
                return;
            }
            catch (IOException) when (attempt < maximumAttempts)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(attempt * 150), cancellationToken).ConfigureAwait(false);
            }
        }
    }

    public void LaunchInstaller(string installerPath)
    {
        ThrowIfDisposed();
        var fullPath = Path.GetFullPath(installerPath);
        var expectedDirectory = Path.GetFullPath(_updatesDirectory) + Path.DirectorySeparatorChar;
        if (!fullPath.StartsWith(expectedDirectory, StringComparison.OrdinalIgnoreCase) || !File.Exists(fullPath))
            throw new UpdateException("Nie można uruchomić instalatora spoza chronionego katalogu aktualizacji.");
        var process = Process.Start(new ProcessStartInfo
        {
            FileName = fullPath,
            Arguments = "/SP- /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS",
            UseShellExecute = true,
        });
        if (process is null)
            throw new UpdateException("Windows nie uruchomił kreatora aktualizacji.");
    }

    private static async Task CopyWithLimitAsync(
        Stream source,
        Stream destination,
        long maximumBytes,
        IProgress<UpdateDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        long total = 0;
        try
        {
            while (true)
            {
                var read = await source.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken).ConfigureAwait(false);
                if (read == 0) break;
                total += read;
                if (total > maximumBytes)
                    throw new UpdateException("Pobrane dane przekraczają bezpieczny limit rozmiaru.");
                await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                progress?.Report(new UpdateDownloadProgress(total, maximumBytes));
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static Version ParseVersion(string value)
    {
        var normalized = value.Trim().TrimStart('v', 'V');
        if (!Version.TryParse(normalized, out var version) || version.Revision >= 0)
            throw new UpdateException($"Wersja „{value}” ma nieobsługiwany format.");
        return version.Build < 0 ? new Version(version.Major, version.Minor, 0) : version;
    }

    private static Uri ValidateDownloadUri(string value, string tag, string assetName)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps
            || !uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase))
            throw new UpdateException("Adres instalatora nie prowadzi do GitHub przez HTTPS.");
        var expectedPath = $"/{AppInfo.GitHubOwner}/{AppInfo.GitHubRepository}/releases/download/{tag}/{assetName}";
        if (!Uri.UnescapeDataString(uri.AbsolutePath).Equals(expectedPath, StringComparison.Ordinal))
            throw new UpdateException("Adres instalatora nie zgadza się z repozytorium i tagiem wydania.");
        return uri;
    }

    private static Uri ValidateReleasePage(string value, string tag)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps
            || !uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase)
            || !Uri.UnescapeDataString(uri.AbsolutePath).Equals(
                $"/{AppInfo.GitHubOwner}/{AppInfo.GitHubRepository}/releases/tag/{tag}",
                StringComparison.Ordinal))
            throw new UpdateException("Adres strony wydania jest nieprawidłowy.");
        return uri;
    }

    private static string ParseSha256(string? digest)
    {
        const string prefix = "sha256:";
        if (digest is null || !digest.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            throw new UpdateException("GitHub nie udostępnił sumy SHA-256 instalatora.");
        var hash = digest[prefix.Length..];
        if (hash.Length != 64 || hash.Any(character => !Uri.IsHexDigit(character)))
            throw new UpdateException("Suma SHA-256 instalatora ma nieprawidłowy format.");
        return hash;
    }

    private static async Task<string> CalculateSha256Async(string path, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        var hash = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        return Convert.ToHexString(hash);
    }

    private static async Task ValidatePortableExecutableAsync(string path, CancellationToken cancellationToken)
    {
        var header = new byte[2];
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 2, FileOptions.Asynchronous);
        var read = await stream.ReadAsync(header, cancellationToken).ConfigureAwait(false);
        if (read != 2 || header[0] != (byte)'M' || header[1] != (byte)'Z')
            throw new UpdateException("Pobrany plik nie jest prawidłowym instalatorem Windows.");
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Debug.WriteLine($"Nie udało się usunąć niepełnej aktualizacji: {error}");
        }
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(_disposed, this);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _client.Dispose();
    }

    private sealed class GitHubRelease
    {
        [JsonPropertyName("tag_name")]
        public string TagName { get; init; } = string.Empty;

        [JsonPropertyName("html_url")]
        public string HtmlUrl { get; init; } = string.Empty;

        [JsonPropertyName("body")]
        public string? Body { get; init; }

        [JsonPropertyName("draft")]
        public bool Draft { get; init; }

        [JsonPropertyName("prerelease")]
        public bool Prerelease { get; init; }

        [JsonPropertyName("assets")]
        public GitHubAsset[] Assets { get; init; } = [];
    }

    private sealed class GitHubAsset
    {
        [JsonPropertyName("name")]
        public string Name { get; init; } = string.Empty;

        [JsonPropertyName("browser_download_url")]
        public string BrowserDownloadUrl { get; init; } = string.Empty;

        [JsonPropertyName("size")]
        public long Size { get; init; }

        [JsonPropertyName("digest")]
        public string? Digest { get; init; }
    }
}
