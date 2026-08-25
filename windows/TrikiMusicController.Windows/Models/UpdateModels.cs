namespace TrikiMusicController_Windows.Models;

public sealed record AppUpdateInfo(
    Version Version,
    string TagName,
    string ReleaseNotes,
    Uri ReleasePage,
    Uri DownloadUri,
    string AssetName,
    long SizeBytes,
    string Sha256);

public sealed record UpdateDownloadProgress(long BytesReceived, long TotalBytes)
{
    public double Percentage => TotalBytes <= 0
        ? 0
        : Math.Clamp(BytesReceived * 100d / TotalBytes, 0d, 100d);
}

public sealed class UpdateException(string message, Exception? innerException = null)
    : Exception(message, innerException);
