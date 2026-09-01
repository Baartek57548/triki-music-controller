using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace TrikiMusicController_Windows.Services;

public sealed record SpotifyDevice(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("is_active")] bool IsActive,
    [property: JsonPropertyName("volume_percent")] int? VolumePercent);

public sealed record SpotifyDevicesResponse(
    [property: JsonPropertyName("devices")] IReadOnlyList<SpotifyDevice> Devices);

public sealed class SpotifyConnectService : IDisposable
{
    private readonly HttpClient _http = new();
    private string? _accessToken;
    private string? _selectedDeviceId;

    public bool IsAuthenticated => !string.IsNullOrWhiteSpace(_accessToken);
    public string? SelectedDeviceId => _selectedDeviceId;
    public IReadOnlyList<SpotifyDevice> AvailableDevices { get; private set; } = [];

    public void SetAccessToken(string? token)
    {
        _accessToken = token?.Trim();
        if (string.IsNullOrWhiteSpace(_accessToken))
        {
            AvailableDevices = [];
            _selectedDeviceId = null;
        }
    }

    public void SelectDevice(string? deviceId)
    {
        _selectedDeviceId = deviceId;
    }

    public async Task<IReadOnlyList<SpotifyDevice>> RefreshDevicesAsync()
    {
        if (string.IsNullOrWhiteSpace(_accessToken)) return [];

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, "https://api.spotify.com/v1/me/player/devices");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _accessToken);

            using var response = await _http.SendAsync(request).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return [];

            var json = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
            var result = JsonSerializer.Deserialize<SpotifyDevicesResponse>(json);
            AvailableDevices = result?.Devices ?? [];

            if (_selectedDeviceId is null || !AvailableDevices.Any(d => d.Id == _selectedDeviceId))
            {
                _selectedDeviceId = AvailableDevices.FirstOrDefault(d => d.IsActive)?.Id ?? AvailableDevices.FirstOrDefault()?.Id;
            }

            return AvailableDevices;
        }
        catch (Exception error)
        {
            Debug.WriteLine($"Błąd pobierania urządzeń Spotify Connect: {error.Message}");
            return [];
        }
    }

    public async Task<bool> SetVolumeAsync(int volumePercent)
    {
        if (string.IsNullOrWhiteSpace(_accessToken)) return false;
        var clamped = Math.Clamp(volumePercent, 0, 100);

        try
        {
            var url = $"https://api.spotify.com/v1/me/player/volume?volume_percent={clamped}";
            if (!string.IsNullOrWhiteSpace(_selectedDeviceId))
            {
                url += $"&device_id={_selectedDeviceId}";
            }

            using var request = new HttpRequestMessage(HttpMethod.Put, url);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _accessToken);
            using var response = await _http.SendAsync(request).ConfigureAwait(false);
            return response.IsSuccessStatusCode;
        }
        catch (Exception error)
        {
            Debug.WriteLine($"Błąd zmiany głośności Spotify Connect: {error.Message}");
            return false;
        }
    }

    public async Task<bool> NextTrackAsync()
    {
        if (string.IsNullOrWhiteSpace(_accessToken)) return false;
        try
        {
            var url = "https://api.spotify.com/v1/me/player/next";
            if (!string.IsNullOrWhiteSpace(_selectedDeviceId)) url += $"?device_id={_selectedDeviceId}";
            using var request = new HttpRequestMessage(HttpMethod.Post, url);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _accessToken);
            using var response = await _http.SendAsync(request).ConfigureAwait(false);
            return response.IsSuccessStatusCode;
        }
        catch (Exception error)
        {
            Debug.WriteLine($"Błąd przełączania utworu Spotify Connect: {error.Message}");
            return false;
        }
    }

    public async Task<bool> PreviousTrackAsync()
    {
        if (string.IsNullOrWhiteSpace(_accessToken)) return false;
        try
        {
            var url = "https://api.spotify.com/v1/me/player/previous";
            if (!string.IsNullOrWhiteSpace(_selectedDeviceId)) url += $"?device_id={_selectedDeviceId}";
            using var request = new HttpRequestMessage(HttpMethod.Post, url);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _accessToken);
            using var response = await _http.SendAsync(request).ConfigureAwait(false);
            return response.IsSuccessStatusCode;
        }
        catch (Exception error)
        {
            Debug.WriteLine($"Błąd poprzedniego utworu Spotify Connect: {error.Message}");
            return false;
        }
    }

    public async Task<bool> TogglePlayPauseAsync(bool isCurrentlyPlaying)
    {
        if (string.IsNullOrWhiteSpace(_accessToken)) return false;
        try
        {
            var endpoint = isCurrentlyPlaying ? "pause" : "play";
            var url = $"https://api.spotify.com/v1/me/player/{endpoint}";
            if (!string.IsNullOrWhiteSpace(_selectedDeviceId)) url += $"?device_id={_selectedDeviceId}";
            using var request = new HttpRequestMessage(HttpMethod.Put, url);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _accessToken);
            using var response = await _http.SendAsync(request).ConfigureAwait(false);
            return response.IsSuccessStatusCode;
        }
        catch (Exception error)
        {
            Debug.WriteLine($"Błąd Play/Pause Spotify Connect: {error.Message}");
            return false;
        }
    }

    public void Dispose()
    {
        _http.Dispose();
    }
}
