---
title: Architektura i Układ Ekranu Ustawień
tags:
  - ui
---

# Architektura i Układ Ekranu Ustawień

Zgodnie z wytycznymi ergonomii interfejsu (wersja 3.0.1), zakładka **Ustawienia** na platformach Windows i Android została zorganizowana w spójną architekturę **4 czystych sekcji**:

Powiązane węzły:
- [[Multi-Device-Arbitration]] — konfiguracja trybu łączenia.
- [[Spotify-Connect-Integration]] — sekcja integracji domowej.
- [[ADR-004-Dynamic-Version-Binding]] — automatyczne wersjonowanie w sekcji O aplikacji.
- [[Android-Architecture]] & [[Windows-Architecture]] — implementacja widoków.

---

## 4 Główne Kategorie Ustawień

1. **Połączenie i Zasilanie**:
   - *Automatyczne łączenie*: Łączenie z zapamiętanym kontrolerem przy uruchomieniu.
   - *Oszczędzanie energii (Tryb Eco)*: Zwalnianie połączenia po 12s bezczynności (`WakeAdvertisementGate`).
   - *Priorytet wielu urządzeń*: Wybór trybu arbitrażu między komputerem a telefonem.

2. **Działanie i Wygląd**:
   - *Dźwięki potwierdzenia akcji*: Odtwarzanie subtelnych sygnałów dźwiękowych po wykonaniu gestu lub kliknięcia.
   - *Powiadomienia systemowe*: Włączanie powiadomień OSD / toast o stanie połączenia.
   - *Motyw aplikacji*: Wybór motywu (Zgodny z systemem, Ciemny, Jasny).

3. **Integracje**:
   - *Spotify Connect*: Zdalne sterowanie urządzeniami audio w sieci lokalnej (Sonos, TV, konsole).

4. **O Aplikacji i Narzędzia**:
   - *Informacje o wersji*: Wyświetlanie aktualnej wersji programu powiązanej dynamicznie z `AppInfo.Version`.
   - *Sprawdź aktualizacje*: Bezpośrednie wywołanie sprawdzania wydań na GitHubie.
   - *Uprawnienia systemowe*: Przejrzysty panel uprawnień Bluetooth, multimediów, jasności WMI oraz skrót do Ustawień systemowych.
   - *Informacje i repozytorium*: Przejście do oficjalnego repozytorium GitHub i listy nowości.
   - *Diagnostyka IMU*: Narzędzia zaawansowanego podglądu surowych danych i procedury kalibracji.
