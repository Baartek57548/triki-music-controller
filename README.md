# Triki Music Controller

Triki Music Controller zmienia kontroler **Żabka Triki** w konfigurowalny pilot do muzyki na Androidzie. Aplikacja odbiera strumień IMU przez Bluetooth Low Energy, filtruje i klasyfikuje ruchy, mapuje gesty na akcje multimedialne oraz steruje aktualnie aktywną sesją Android MediaSession.

To nie jest emulator Żappki i nie omija zabezpieczeń żadnej usługi. Całość działa przez publiczne API Androida: GATT, `MediaSessionManager`, `MediaController.TransportControls`, `AudioManager`, `NotificationListenerService` oraz foreground service typu `connectedDevice`.

## Funkcje

- pełny cykl BLE: energooszczędny skan na żądanie, GATT discovery, NUS notifications, timeout, RSSI, bateria, reconnect z exponential backoff;
- potwierdzony dekoder ramek IMU z resynchronizacją po rozciętych i sklejonych notyfikacjach;
- `GestureEngine` niezależny od UI: lean, slide, rotate, tap, shake, double shake i flip klasyfikowane z akcelerometru oraz żyroskopu po pełnym oknie ruchu;
- dynamiczna baza neutralna, wymagany cykl spoczynek–ruch–spoczynek oraz cooldown per gest;
- wielostopniowa filtracja IMU: mediana odrzucająca pojedyncze skoki, adaptacyjna martwa strefa gyro, low-pass i filtr komplementarny;
- opcjonalna kalibracja biasu akcelerometru/żyroskopu, neutralnej pozycji i szumu; lokalny stabilny spoczynek automatycznie uzbraja sterowanie;
- lokalny model few-shot k-NN uczony z 2–5 przykładów gestu; 40 cech łączy akcelerometr i żyroskop oraz ogranicza zależność od pozycji kapsla;
- konfigurowalne mapowania oraz profile zapisywane w Preferences DataStore;
- sterowanie Play, Pause, Play/Pause, Next, Previous, Stop, Volume +/−, Mute i Unmute;
- dashboard z orientacją Triki, baterią, RSSI, częstotliwością ramek i Now Playing;
- onboarding, kreator wszystkich ośmiu gestów, Gesture Trainer z nagrywaniem Start/Stop, Sensor Monitor i BLE Inspector;
- instrukcje wykonania każdego ruchu, bezpieczna próba rozpoznawania oraz wybór własnej akcji lub wyłączenie gestu;
- rotujący bufor pakietów RAW z eksportem HEX/DEC oraz ograniczony bufor logów;
- foreground service z akcją `Rozłącz` i automatycznym wygaszaniem, gdy nie ma aktywnego połączenia;
- `FakeTrikiDataSource` dostępny wyłącznie w buildzie debug po włączeniu Developer Mode;
- jasny, ciemny i systemowy motyw Material 3, edge-to-edge oraz responsywny dashboard.

## Wymagania

- Android 8.0 (API 26) lub nowszy;
- telefon z Bluetooth Low Energy;
- kontroler Żabka Triki; przed skanowaniem należy nacisnąć jego przycisk, aby go wybudzić;
- JDK 17 lub nowszy oraz Android SDK 36 do budowania projektu.

Projekt używa Android Gradle Plugin 8.13.2 i wrappera Gradle 8.13. Wersje zostały dobrane pod stabilny toolchain obsługujący compile SDK 36.

## Uruchomienie

1. Otwórz repozytorium w Android Studio i pozwól Gradle pobrać zależności.
2. Upewnij się, że `local.properties` wskazuje zainstalowane Android SDK.
3. Zbuduj i uruchom wariant `debug` na fizycznym telefonie; emulator Androida nie zapewni połączenia z kapslem BLE.
4. Przejdź onboarding i otwórz **Uprawnienia**.
5. Nadaj dostęp do urządzeń w pobliżu. Na Androidzie 8–11 system wymaga podczas skanowania BLE uprawnienia lokalizacji, mimo że aplikacja nie odczytuje GPS.
6. Dostęp listenera powiadomień jest opcjonalny i służy do okładki, tytułu oraz dokładnego stanu sesji. Na Xiaomi podstawowe sterowanie działa również bez niego przez publiczne media keys.
7. Naciśnij przycisk Triki, wybierz **Device → Skanuj urządzenia**, a następnie **Połącz**.
8. Po stanie **Gotowe** przejdź kreator, w którym wybierzesz akcje i opcjonalnie nagrasz po dwie krótkie próby gestu z różnych typowych pozycji kapsla. Kalibracja jest zalecana, ale nie blokuje sterowania.
9. Uruchom muzykę. Kreator można później powtórzyć z ekranu **Gestures → Kreator gestów**.

Build z linii poleceń na Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

Na macOS/Linux odpowiednikami są `./gradlew assembleDebug` i `./gradlew test`.

## Uprawnienia

| Uprawnienie / dostęp | Wersje | Powód |
|---|---:|---|
| `BLUETOOTH_SCAN` | Android 12+ | Wyszukiwanie reklamującego się Triki |
| `BLUETOOTH_CONNECT` | Android 12+ | Połączenie i operacje GATT |
| `ACCESS_FINE_LOCATION` | Android 8–11 | Wymóg platformy dla skanowania BLE; `maxSdkVersion=30` |
| Notification Listener Access | Wszystkie | Opcjonalne metadane i dokładny stan aktywnej MediaSession |
| `POST_NOTIFICATIONS` | Android 13+ | Widoczne powiadomienie połączenia w tle |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ | Utrzymanie aktywnego GATT po zminimalizowaniu aplikacji |
| `MODIFY_AUDIO_SETTINGS` | Wszystkie | Volume +/− oraz Mute/Unmute przez `AudioManager` |

`BLUETOOTH_SCAN` ma flagę `neverForLocation`. Aplikacja nie żąda background location, pamięci masowej ani dostępu do Internetu.

## Architektura

Kod jest podzielony zgodnie z Clean Architecture + MVVM:

```text
Android GATT
    ↓
TrikiBleManager → RAW ring buffer / GATT Inspector
    ↓
TrikiProtocolDecoder
    ↓
SensorFilter + calibration + complementary orientation
    ↓
GestureFeatureExtractor + lokalny model k-NN
    ↓
GestureEngine + reguły bezpieczeństwa
    ↓
ActionMapper + aktywny ControlProfile
    ↓
AndroidMediaControllerGateway
```

- `core/bluetooth` — protokół, parser ramek i stanowa warstwa BLE;
- `core/gesture` — filtracja, orientacja, kalibracja i klasyfikacja ruchu;
- `data/media` — adapter publicznych API multimedialnych Androida;
- `data/repository` — atomowy zapis ustawień w DataStore;
- `domain` — modele, kontrakty repozytoriów i use cases niezależne od UI;
- `runtime` — jednokierunkowe spięcie strumienia IMU z mapowaniem akcji;
- `ui` — Compose, nawigacja, kreator gestów i pojedynczy ViewModel orkiestrujący interakcje;
- `service` — foreground service i wymagany komponent Notification Listener.

Szczegóły: [ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Protokół BLE

Implementacja porównuje publiczne analizy [Maku-hub/TrikiScope](https://github.com/Maku-hub/TrikiScope), [koksny/TRIKI-Control](https://github.com/koksny/TRIKI-Control) oraz sprzętowo zweryfikowane pomiary [matiaspalmac/everything-imu](https://github.com/matiaspalmac/everything-imu/blob/main/DEVICES.md). Nieznane charakterystyki nie są interpretowane; BLE Inspector pokazuje je wraz z właściwościami i surowymi wartościami.

Potwierdzone minimum:

- Nordic UART Service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`;
- RX write `6e400002-…`, TX notify `6e400003-…`;
- komenda startowa `20 10 00 D0 07 34 00 03`, około 52–53 Hz;
- ramka `14 B`: `22 packetId/status + gyro XYZ + accel XYZ`;
- sześć wartości `int16`, little-endian;
- gyro `0,070°/s/LSB`, accel `2048 LSB/g`;
- identyfikator pakietu `0..15`; wariant `0/1` może raportować stan przycisku;
- `6e400004-… bit 0`: sterowanie LED.

Pełna tabela offsetów, źródeł potwierdzenia i ograniczeń znajduje się w [TRIKI_PROTOCOL.md](docs/TRIKI_PROTOCOL.md).

## Gesture Engine

Presety czułości zmieniają spójny zestaw progów, siłę wygładzania i cooldown. Tryb Advanced pozwala zmienić progi lean/rotate/shake/tap bez naruszania mechanizmów zapobiegających spamowi. Silnik najpierw ustala lokalny spoczynek, następnie zbiera ruch i klasyfikuje najwyżej jeden gest po ponownym uspokojeniu kontrolera. Model k-NN może skorygować wynik dla nauczonego sposobu użycia, ale nie omija fizycznych bramek bezpieczeństwa. Nieruchome Triki leżące pod kątem nie może więc samo wywołać `NEXT` lub `PREVIOUS`.

Opis stanów, kompromisów i testowania: [GESTURE_ENGINE.md](docs/GESTURE_ENGINE.md). Porównanie publicznych implementacji, uzasadnienie odczytu accel + gyro oraz kryteria walidacji sprzętowej: [GESTURE_RESEARCH.md](docs/GESTURE_RESEARCH.md).

## Diagnostyka

Po włączeniu **Settings → Developer Mode** dostępne są:

- Sensor Monitor z wykresami X/Y/Z, orientation, magnitude, RSSI i baterią;
- BLE Inspector z usługami, charakterystykami, properties, descriptorami i odczytanymi wartościami;
- nagrywanie krótkiej sesji RAW i eksport do pliku tekstowego w HEX/DEC;
- kategorie logów `BLE`, `PROTOCOL`, `IMU`, `GESTURE`, `MEDIA`, `SERVICE`, `PERMISSION`;
- Fake Triki generujący kontrolowane sekwencje wszystkich obsługiwanych gestów.

## Testy

Testy JVM obejmują:

- dekodowanie little-endian, skalowanie, status przycisku, startup discard i resynchronizację parsera;
- medianowe odrzucanie skoków, smoothing, adaptacyjną martwą strefę gyro, korekcję biasu i walidację kalibracji;
- ekstrakcję cech accel + gyro, odporność cech grawitacyjnych na obrót kapsla, uczenie k-NN, odrzucanie obcych próbek i fizyczne bramki gestów;
- pełne cykle lean, slide, rotate, flip, tap i single/double shake, nagranie Start/Stop oraz regresje dla długiego spoczynku, szumu, uszkodzonej próbki i stałego błędu gyro;
- profile referencyjne TRIKI-Control w skali prawdziwej ramki: spoczynek na `−Z`, krótkie skręty, lean 14°, płaski slide, stamp i flip, w tym uczenie wszystkich sześciu podstawowych klas;
- mapowanie gest → akcja i brak wywołania dla `NONE`;
- round-trip serializacji ustawień, profili, mapowań, kalibracji i stanu ukończenia kreatora, wraz z migracją danych ze starszej wersji.

## Znane ograniczenia

- Fizyczna walidacja wymaga konkretnego egzemplarza Triki. Projekt kompiluje się i ma testy dekodera na potwierdzonych ramkach, ale bieżące środowisko CI/deweloperskie nie miało dostępu do rzeczywistego kapsla. Inspector jest celowo częścią aplikacji, aby porównać firmware i zebrać brakujące pakiety bez fikcyjnego dekodowania.
- Częstotliwość IMU nie jest zakodowana jako gwarantowana stała protokołu. UI pokazuje wartość mierzoną na żywo; parser interpoluje znaczniki wewnątrz burstu wyłącznie na potrzeby stabilnego filtru.
- Yaw bez magnetometru dryfuje. Pitch i roll są korygowane grawitacją, natomiast yaw opiera się na całkowaniu żyroskopu.
- Bez magnetometru nie istnieje absolutny kierunek poziomy. Dlatego podstawowe mapowanie używa bezkierunkowego `Lean` i płaskiego `Slide`, natomiast kierunek lewo/prawo rozróżnia tylko obrót wokół grawitacji.
- Metadane i okładka wymagają dostępu do aktywnej MediaSession. Play/Pause, Next, Previous i Stop mają fallback przez standardowe klawisze multimedialne, ale ostateczna obsługa komendy zależy od aktywnego odtwarzacza.
- Akcje specyficzne dla Spotify/YouTube Music, takie jak like, shuffle i repeat, nie mają wspólnego publicznego API Android MediaSession. Architektura pozwala dodać jawne integracje w przyszłości, ale obecna wersja nie udaje ich działania.

## Licencja i znaki towarowe

„Żabka”, „Żappka”, „Spotify”, „YouTube Music”, „TIDAL” i „Apple Music” są znakami ich właścicieli. Projekt jest niezależnym narzędziem interoperacyjności i nie jest przez nich sponsorowany ani zatwierdzony.
