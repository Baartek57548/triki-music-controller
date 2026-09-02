using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed record ReleaseHighlight(string Version, string Title, IReadOnlyList<string> Highlights);

public static class WhatsNewService
{
    private static readonly IReadOnlyList<ReleaseHighlight> Releases =
    [
        new ReleaseHighlight(
            "3.0.1",
            "Uporządkowanie Ustawień, okładki albumów w HUD i regulacja jasności",
            [
                "Przebudowano Ustawienia w przejrzystą architekturę 4 kategorii (Połączenie i zasilanie, Działanie i wygląd, Integracje, O aplikacji).",
                "Dodano wyświetlanie rzeczywistej okładki albumu w mini-nakładce Windows (Compact HUD) przy zmianie utworu i głośności.",
                "Płynna regulacja głośności bez przeskoków wskaźnika.",
                "Wprowadzono regulację jasności ekranu w pozycji 90° z wymogiem przytrzymania przycisku.",
                "Przeniesiono konfigurację kąta obrotu do zakładki Sterowanie."
            ]),
        new ReleaseHighlight(
            "2.9.9",
            "Uporządkowanie zakładki Sterowanie w Windows",
            [
                "Usunięto emotikony i zbędne symbole z zakładki Sterowanie w aplikacji Windows.",
                "Uporządkowano opisy i etykiety nawigacji gestami w interfejsie WinUI 3.",
                "Zapewniono spójność wizualną pomiędzy kafelkami kierunków i panelami telemetrii."
            ]),
        new ReleaseHighlight(
            "2.9.8",
            "Usprawnienia skalowania i odnośnik do repozytorium",
            [
                "Poprawiono skalowanie sekcji wyboru kąta obrotu w ustawieniach aplikacji Android na wąskich ekranach smartfonów (np. Xiaomi 13).",
                "Zastosowano elastyczny układ FlowRow zapobiegający ucinaniu plakietek kątów obrotu.",
                "Dodano bezpośredni przycisk z linkiem do oficjalnego repozytorium GitHub w zakładce O aplikacji.",
                "Wprowadzono kontekstowe okno dialogowe z informacjami o działaniu parametru kąta obrotu."
            ]),
        new ReleaseHighlight(
            "2.9.7",
            "Przejrzysty interfejs i kontekstowe okna pomocy",
            [
                "Uproszczono ekrany aplikacji Android: usunięto zbędny tekst i instrukcje na stałe zaśmiecające karty główne.",
                "Wprowadzono kontekstowe ikony informacji (i) otwierające dedykowane okna dialogowe z objaśnieniem mechaniki gestów tylko na życzenie użytkownika.",
                "Zwiększono czytelność paneli sterowania, wskaźników postępu i kafelków telemetrycznych.",
                "Zoptymalizowano układ zakładek i nawigacji zgodnie z nowoczesnymi standardami systemów mobilnych."
            ]),
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
