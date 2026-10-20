# Triki Music Controller v3.1.7

Data: 2026-10-20. Windows 3.1.7.0, Android versionCode 47.

- Naprawiono rozłączanie po 12 sekundach bezczynności w obu aplikacjach.
- Nasłuch wybudzenia Androida filtruje zapamiętany adres BLE, także przy wygaszonym ekranie.
- Zmiana trybu połączenia lub arbitrażu nie zeruje działającego połączenia Windows.
- Własne mapowania Play/Pause, Next i Previous nie są nadpisywane przez migrację ustawień.
- Licznik pakietów IMU nie jest traktowany jako wciśnięcie przycisku sterującego jasnością.
- Gotowość BLE wymaga poprawnych ramek IMU. Dodano timeouty konfiguracji i odzyskiwanie po zaniku strumienia.
- Błędy zapisu kalibracji i ręcznego sterowania multimediami są obsługiwane i pokazywane użytkownikowi.
- Powiadomienie Androida odświeża się po zmianie stanu połączenia, bez aktualizacji przy każdej próbce.
- Windows sprawdza aktualizację także podczas startu w zasobniku. Pobieranie można anulować.
- Publikacja wymaga podpisanego APK z dotychczasowym certyfikatem oraz zgodnych wersji obu aplikacji i instalatora.

Pozostają dostępne funkcje wersji 3.1.6: Air Mouse, regulacja jasności, HUD, arbitraż wielu urządzeń i integracja Spotify.

Powiązania: [[Releases-Overview]], [[System-Overview]], [[BLE-Protocol]].
