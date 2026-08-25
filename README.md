# Triki Music Controller

Triki Music Controller zmienia kontroler **Żabka Triki** w pilot do muzyki na Androidzie. Aplikacja odbiera strumień IMU przez Bluetooth Low Energy, reguluje głośność obrotem kapsla wokół osi Z i obsługuje akcje multimedialne przez fizyczny przycisk.

To nie jest emulator Żappki i nie omija zabezpieczeń żadnej usługi. Całość działa przez publiczne API Androida: GATT, `MediaSessionManager`, `MediaController.TransportControls`, `AudioManager`, `NotificationListenerService` oraz foreground service typu `connectedDevice`.

## Funkcje

- pełny cykl BLE: energooszczędny skan na żądanie, GATT discovery, NUS notifications, timeout, RSSI, bateria, reconnect z exponential backoff;
- potwierdzony dekoder ramek IMU z resynchronizacją po rozciętych i sklejonych notyfikacjach;
- ciągły regulator głośności wykorzystujący dokładnie przefiltrowaną wartość żyroskopu Z: dodatnie Z podgłaśnia, ujemne Z ścisza;
- obowiązkowa bramka bezruchu akcelerometru: długość wektora musi pozostawać w zakresie 0,8–1,2 g przez 120 ms;
- wielostopniowa filtracja IMU: mediana odrzucająca pojedyncze skoki, adaptacyjna martwa strefa gyro, low-pass i filtr komplementarny;
- histereza, martwa strefa i całkowanie prędkości kątowej ograniczające przypadkowe skoki oraz zachowujące proporcję między szybkością obrotu i zmianą głośności;
- opcjonalna kalibracja biasu akcelerometru/żyroskopu, neutralnej pozycji i szumu;
- bezpieczna autodetekcja przycisku: jeden klik steruje Play/Pause, dwa przechodzą do następnego utworu, trzy do poprzedniego; każde mapowanie można zmienić w profilu;
- konfigurowalne mapowania przycisku zapisywane w Preferences DataStore;
- sterowanie Play, Pause, Play/Pause, Next, Previous, Stop, Volume +/−, Mute i Unmute;
- dashboard z orientacją Triki, baterią, RSSI, częstotliwością ramek i Now Playing;
- onboarding, ekran stanu regulatora, Sensor Monitor i BLE Inspector;
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
8. Po stanie **Gotowe** otwórz **Sterowanie**, sprawdź status regulatora Z i ustaw akcje dla jednego, dwóch oraz trzech kliknięć.
9. Uruchom muzykę. Trzymaj kapsel bez przyspieszeń liniowych i obracaj go w miejscu: dodatnia wartość Z podgłaśnia, ujemna ścisza.

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
GyroscopeVolumeController + bramka 0,8–1,2 g
    ↓
ActionMapper + mapowanie przycisku
    ↓
AndroidMediaControllerGateway
```

- `core/bluetooth` — protokół, parser ramek i stanowa warstwa BLE;
- `core/sensor` — filtracja, orientacja i kalibracja czujników;
- `core/volume` — bezpieczna regulacja głośności z żyroskopu Z;
- `data/media` — adapter publicznych API multimedialnych Androida;
- `data/repository` — atomowy zapis ustawień w DataStore;
- `domain` — modele, kontrakty repozytoriów i use cases niezależne od UI;
- `runtime` — jednokierunkowe spięcie strumienia IMU z mapowaniem akcji;
- `ui` — Compose, nawigacja i pojedynczy ViewModel orkiestrujący interakcje;
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
- drugi bajt ramki jest zależny od firmware: licznik pakietu `0..15` albo stan przycisku `0/1`; aplikacja rozpoznaje wariant przed wygenerowaniem zdarzenia;
- `6e400004-… bit 0`: sterowanie LED.

Pełna tabela offsetów, źródeł potwierdzenia i ograniczeń znajduje się w [TRIKI_PROTOCOL.md](docs/TRIKI_PROTOCOL.md).

## Regulator głośności

`GyroscopeVolumeController` przyjmuje przefiltrowaną wartość osi Z w °/s i całkuje ją do kąta obrotu. Każde 15° daje jeden krok głośności; znak Z określa kierunek. Uruchomienie wymaga 120 ms stabilnego odczytu akcelerometru w zakresie 0,8–1,2 g. Zakres dotyczy długości wektora, a nie osobnych osi, dlatego działa także wtedy, gdy kapsel jest trzymany pod kątem w powietrzu. Martwa strefa 18°/s, próg zwolnienia 10°/s, zerowanie po zmianie kierunku i ponowne uzbrajanie po przerwie strumienia ograniczają niezamierzone zmiany.

Fizyczny przycisk nie korzysta z IMU ani kalibracji. Po potwierdzeniu wariantu `0/1` aplikacja stosuje debounce, liczy pełne cykle wciśnięcie–puszczenie i czeka 450 ms na następny klik. Firmware z licznikiem `0..15` oraz wariant naprzemienny `0/1` są ignorowane, aby identyfikatory ramek nie uruchamiały muzyki. Domyślnie: `×1 → Play/Pause`, `×2 → Next`, `×3 → Previous`.

## Diagnostyka

Po włączeniu **Settings → Developer Mode** dostępne są:

- Sensor Monitor z wykresami X/Y/Z, orientation, magnitude, RSSI i baterią;
- BLE Inspector z usługami, charakterystykami, properties, descriptorami i odczytanymi wartościami;
- nagrywanie krótkiej sesji RAW i eksport do pliku tekstowego w HEX/DEC;
- bieżący stan bramki akcelerometru oraz wartość osi Z używaną przez regulator;
- kategorie logów `BLE`, `PROTOCOL`, `IMU`, `CONTROL`, `MEDIA`, `SERVICE`, `PERMISSION`;
- Fake Triki generujący sekwencje jednego, dwóch i trzech kliknięć.

## Testy

Testy JVM obejmują:

- dekodowanie little-endian, skalowanie, startup discard i resynchronizację parsera;
- autodetekcję przycisku/licznika, debounce, wieloklik, odbicia styku, długie przytrzymanie i zerwanie strumienia;
- medianowe odrzucanie skoków, smoothing, adaptacyjną martwą strefę gyro, korekcję biasu i walidację kalibracji;
- dodatni i ujemny kierunek regulatora Z, tolerancję 0,8–1,2 g, niezależność od orientacji kapsla, histerezę oraz ponowne uzbrajanie po przerwie strumienia;
- mapowanie przycisk → akcja i brak wywołania dla `NONE`;
- round-trip serializacji ustawień, mapowań przycisku i kalibracji, wraz z bezpiecznym ignorowaniem pól starszego systemu sterowania.

## Znane ograniczenia

- Fizyczna walidacja wymaga konkretnego egzemplarza Triki. Projekt kompiluje się i ma testy dekodera na potwierdzonych ramkach, ale progi regulatora należy ostatecznie potwierdzić na rzeczywistym kapslu.
- Częstotliwość IMU nie jest zakodowana jako gwarantowana stała protokołu. UI pokazuje wartość mierzoną na żywo; parser interpoluje znaczniki wewnątrz burstu wyłącznie na potrzeby stabilnego filtru.
- Yaw bez magnetometru dryfuje. Pitch i roll są korygowane grawitacją, natomiast yaw opiera się na całkowaniu żyroskopu.
- Akcelerometr nie odróżnia bezruchu od ruchu ze stałą prędkością. Bramka 0,8–1,2 g odrzuca przyspieszenia liniowe, lecz nie może wykryć jednostajnego przesuwania bez dodatkowego źródła pozycji.
- Metadane i okładka wymagają dostępu do aktywnej MediaSession. Play/Pause, Next, Previous i Stop mają fallback przez standardowe klawisze multimedialne, ale ostateczna obsługa komendy zależy od aktywnego odtwarzacza.
- Akcje specyficzne dla Spotify/YouTube Music, takie jak like, shuffle i repeat, nie mają wspólnego publicznego API Android MediaSession. Architektura pozwala dodać jawne integracje w przyszłości, ale obecna wersja nie udaje ich działania.

## Licencja i znaki towarowe

„Żabka”, „Żappka”, „Spotify”, „YouTube Music”, „TIDAL” i „Apple Music” są znakami ich właścicieli. Projekt jest niezależnym narzędziem interoperacyjności i nie jest przez nich sponsorowany ani zatwierdzony.
