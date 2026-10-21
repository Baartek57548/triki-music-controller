# Triki Music Controller v3.2.0

Data: 2026-10-21. Windows 3.2.0.0, Android versionCode 50.

- Dodano wybór trybu połączenia: Eco 2, 5 lub 10 minut, Normal 30 minut i stałe połączenie.
- Domyślny tryb to **Normal — 30 minut bezczynności**. Dotyczy także migracji starszych ustawień bez zapisanego nowego trybu. Wyłączenie automatycznego łączenia Windows jest respektowane.
- Ruch lub przycisk odnawia czas bezczynności. Zmiana trybu rozpoczyna nowy okres oczekiwania.
- Historia przechowuje lokalnie ostatnich 200 zdarzeń BLE, także po restarcie aplikacji. Rejestruje łączenie, gotowość, rozłączenia, bezczynność, błędy i ponowne próby. Nie zapisuje zdarzenia przy każdej próbce IMU.
- Historię można wyeksportować jako JSON lub wyczyścić w Ustawieniach. Błędy odczytu i zapisu są pokazywane, a uszkodzony plik ma jedną ograniczoną kopię diagnostyczną.
- Dodano osobną czułość gestów głośności i jasności (50–200%, domyślnie 100%) oraz niezależne włączniki tych gestów. Czułość zmienia siłę reakcji bez wzmacniania szumu wejściowego.
- Zachowano mapowania przycisku, kalibrację, kąt zmiany utworu, arbitraż i działanie Windows w zasobniku.
- Automatyczne wznawianie BLE pozostaje aktywne; czas hosta nie może zagwarantować, że firmware kontrolera nie uśpi urządzenia wcześniej.

Testy symulowanych próbek i plików nie zastępują fizycznego sprawdzenia kontrolera oraz zużycia baterii.

Powiązania: [[Releases-Overview]], [[Settings-Architecture]], [[BLE-Protocol]], [[Next-Improvements]].
