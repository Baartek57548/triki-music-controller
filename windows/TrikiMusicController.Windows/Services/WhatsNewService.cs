using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed record ReleaseHighlight(string Version, string Title, IReadOnlyList<string> Highlights);

public static class WhatsNewService
{
    private static readonly IReadOnlyList<ReleaseHighlight> Releases =
    [
        new ReleaseHighlight(
            "2.9.6",
            "Cicha aktualizacja i okno Co nowego",
            [
                "Pełna automatyzacja aktualizacji Windows: instalator pobiera się, weryfikuje i instaluje bezgłośnie w tle bez wyświetlania zbędnych okien kreatora.",
                "Automatyczne ponowne uruchomienie aplikacji po zakończeniu instalacji z natychmiastowym wyświetleniem listy nowości.",
                "Dedykowany przycisk „Co nowego w tej wersji” w zakładce Informacje umożliwiający sprawdzenie listy zmian w dowolnym momencie.",
                "Zoptymalizowany proces publikacji i weryfikacji pakietów instalacyjnych dla systemów Windows oraz Android."
            ]),
        new ReleaseHighlight(
            "2.9.5",
            "Nowy interfejs Material Design 3 w Androidzie",
            [
                "Kompleksowe przeprojektowanie wszystkich zakładek i ekranów aplikacji mobilnej zgodnie z wytycznymi Material Design 3.",
                "Nowy panel Teraz Odtwarzane z powiększoną okładką albumu, etykietą źródła oraz zbalansowanymi przyciskami sterowania.",
                "Studio bramki głośności ze wskaźnikiem stabilizacji pozycji i podglądem telemetrii w czasie rzeczywistym.",
                "Studio nawigacji obrotem ze wsparciem konfigurowalnego kąta docelowego i dynamicznym paskiem postępu.",
                "Ujednolicenie stylistyki kart, kafelków telemetrycznych i całkowita eliminacja emotikonów."
            ])
    ];

    public static ReleaseHighlight? GetForCurrentVersion() =>
        Releases.FirstOrDefault(r => r.Version.Equals(AppInfo.Version, StringComparison.OrdinalIgnoreCase))
        ?? Releases.FirstOrDefault();

    public static IReadOnlyList<ReleaseHighlight> GetAllReleases() => Releases;
}
