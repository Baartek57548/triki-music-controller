namespace TrikiMusicController_Windows.Models;

public enum ConnectionPowerMode { Normal, Eco2, Eco5, Eco10, AlwaysOn }

public static class ConnectionPowerModes
{
    public static int? IdleMinutes(this ConnectionPowerMode mode) => mode switch
    {
        ConnectionPowerMode.Eco2 => 2,
        ConnectionPowerMode.Eco5 => 5,
        ConnectionPowerMode.Eco10 => 10,
        ConnectionPowerMode.AlwaysOn => null,
        _ => 30,
    };

    public static long IdleTimeoutNanos(this ConnectionPowerMode mode) => (mode.IdleMinutes() ?? 30) * 60_000_000_000L;
    public static string DisplayName(this ConnectionPowerMode mode) => mode switch
    {
        ConnectionPowerMode.Eco2 => "Eco — 2 minuty",
        ConnectionPowerMode.Eco5 => "Eco — 5 minut",
        ConnectionPowerMode.Eco10 => "Eco — 10 minut",
        ConnectionPowerMode.AlwaysOn => "Stałe połączenie",
        _ => "Normal — 30 minut",
    };
}

public sealed record ConnectionPowerOption(ConnectionPowerMode Mode, string DisplayName);
