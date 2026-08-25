# Triki Music Controller

Triki Music Controller zmienia kontroler **Żabka Triki** w pilot do muzyki na Androidzie. Aplikacja odbiera strumień IMU przez Bluetooth Low Energy, reguluje głośność obrotem kapsla wokół osi Z i obsługuje akcje multimedialne przez fizyczny przycisk.

To nie jest emulator Żappki i nie omija zabezpieczeń żadnej usługi. Całość działa przez publiczne API Androida: GATT, `MediaSessionManager`, `MediaController.TransportControls`, `AudioManager`, `NotificationListenerService` oraz foreground service typu `connectedDevice`.

## Funkcje

- pełny cykl BLE: skan pierwszego urządzenia na żądanie, zapamiętanie po udanym połączeniu, pasywne GATT `autoConnect`, discovery, NUS notifications, timeout, RSSI i bateria;
- potwierdzony dekoder ramek IMU z resynchronizacją po rozciętych i sklejonych notyfikacjach;
- ciągły regulator głośności wykorzystujący dokładnie przefiltrowaną wartość żyroskopu Z: dodatnie Z podgłaśnia, ujemne Z ścisza;
- wielowarstwowa bramka bezpieczeństwa: kapsel musi leżeć górą do góry w przechyle do 25°, akcelerometr pozostawać w zakresie 0,8–1,2 g, a cały żyroskop wskazywać bezruch nieprzerwanie przez 900 ms;
- wielostopniowa filtracja IMU: mediana odrzucająca pojedyncze skoki, adaptacyjna martwa strefa gyro, low-pass i filtr komplementarny;
- histereza, martwa strefa i całkowanie prędkości kątowej ograniczające przypadkowe skoki oraz zachowujące proporcję między szybkością obrotu i zmianą głośności;
- opcjonalna kalibracja biasu akcelerometru/żyroskopu, neutralnej pozycji i szumu;
- bezpieczna autodetekcja przycisku: jeden klik steruje Play/Pause, dwa przechodzą do następnego utworu, trzy do poprzedniego; każde mapowanie można zmienić w profilu;
- konfigurowalne mapowania przycisku zapisywane w Preferences DataStore;
- sterowanie Play, Pause, Play/Pause, Next, Previous, Stop, Volume +/−, Mute i Unmute;
- dashboard z orientacją Triki, baterią, RSSI, częstotliwością ramek i informacją o odtwarzanym utworze;
- onboarding, czytelna lista warunków bezpiecznej regulacji, monitor czujników i inspektor BLE;
- rotujący bufor pakietów RAW z eksportem HEX/DEC oraz ograniczony bufor logów;
- foreground service czekający na wybudzenie zapamiętanego Triki, akcja wyłączająca autołączenie oraz przywrócenie czuwania po restarcie telefonu;
- `FakeTrikiDataSource` dostępny wyłącznie w buildzie debug po włączeniu trybu deweloperskiego;
- automatyczne sprawdzanie najnowszego wydania GitHub przy uruchomieniu wersji release, ręczne sprawdzanie na ekranie **O aplikacji** oraz weryfikowany instalator APK;
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
7. Naciśnij przycisk Triki, wybierz **Urządzenie → Skanuj urządzenia**, a następnie **Połącz i zapamiętaj**. Adres jest zapisywany dopiero po pełnym uruchomieniu strumienia IMU.
8. Po stanie **Gotowe** otwórz **Sterowanie**, sprawdź status regulatora Z i ustaw akcje dla jednego, dwóch oraz trzech kliknięć.
9. Uruchom muzykę. Połóż kapsel górą do góry na równej powierzchni i nie dotykaj go przez około sekundę. Gdy UI pokaże „Regulator gotowy”, obracaj go płasko: dodatnia wartość Z podgłaśnia, ujemna ścisza.
10. Gdy Triki uśnie i rozłączy BLE, naciśnij jego fizyczny przycisk. Przy włączonym **Sterowaniu w tle** Android automatycznie dokończy oczekujące połączenie z zapamiętanym kapslem — bez ponownego wybierania urządzenia.

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
| `RECEIVE_BOOT_COMPLETED` | Wszystkie | Przywrócenie włączonego przez użytkownika oczekiwania na zapamiętane Triki po restarcie telefonu |
| `MODIFY_AUDIO_SETTINGS` | Wszystkie | Volume +/− oraz Mute/Unmute przez `AudioManager` |
| `INTERNET` | Wszystkie | Sprawdzenie metadanych najnowszego wydania i pobranie wybranego APK z GitHub |
| `REQUEST_INSTALL_PACKAGES` | Wszystkie | Przekazanie zweryfikowanego APK do systemowego instalatora po zgodzie użytkownika |

`BLUETOOTH_SCAN` ma flagę `neverForLocation`. Aplikacja nie żąda background location ani dostępu do pamięci masowej. Zezwolenie „Instaluj nieznane aplikacje” jest wymagane dopiero po wybraniu pobrania aktualizacji; ostateczne zatwierdzenie instalacji zawsze odbywa się w interfejsie Androida.

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
GyroscopeVolumeController + bramka poziomu, bezruchu i 0,8–1,2 g
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
- `data/update` — klient GitHub Releases, pobieranie, kontrola rozmiaru i SHA-256 oraz weryfikacja pakietu, versionCode i certyfikatu podpisującego;
- `domain` — modele, kontrakty repozytoriów i use cases niezależne od UI;
- `runtime` — jednokierunkowe spięcie strumienia IMU z mapowaniem akcji;
- `ui` — Compose, nawigacja i pojedynczy ViewModel orkiestrujący interakcje;
- `service` — foreground service autołączenia, odbiornik restartu telefonu i wymagany komponent Notification Listener.

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

`GyroscopeVolumeController` przyjmuje przefiltrowaną wartość żyroskopu Z w °/s i całkuje ją do kąta obrotu. Każde 15° daje jeden krok głośności; znak żyroskopu Z określa kierunek. Sprzętowo potwierdzona pozycja kapsla górą do góry daje około −1 g na osi akcelerometru Z. Uzbrojenie wymaga 900 ms ciągłego spoczynku całego żyroskopu poniżej 5°/s, długości wektora akcelerometru 0,8–1,2 g oraz położenia górą do góry z przechyłem maksymalnie 25°. Po uzbrojeniu histereza dopuszcza przechył do 32°, ale przekroczenie tego progu albo obrót poza osią Z powyżej 22°/s natychmiast rozbraja regulator. Położenie pionowe 90° i odwrócone 180° nie może zmienić głośności.

Martwa strefa osi Z 18°/s, próg zwolnienia 10°/s, zerowanie po zmianie kierunku i ponowne uzbrajanie po przerwie strumienia ograniczają niezamierzone skoki. Interfejs pokazuje osobno położenie, zakres przyspieszenia, postęp stabilizacji i bieżącą wartość żyroskopu Z, dzięki czemu użytkownik wie, co blokuje sterowanie.

Fizyczny przycisk nie korzysta z IMU ani kalibracji. Po potwierdzeniu wariantu `0/1` aplikacja stosuje debounce, liczy pełne cykle wciśnięcie–puszczenie i czeka 450 ms na następny klik. Firmware z licznikiem `0..15` oraz wariant naprzemienny `0/1` są ignorowane, aby identyfikatory ramek nie uruchamiały muzyki. Domyślnie: `×1 → Play/Pause`, `×2 → Next`, `×3 → Previous`.

## Diagnostyka

Po włączeniu **Ustawienia → Tryb deweloperski** dostępne są:

- monitor czujników z wykresami X/Y/Z, orientacją, długością wektora, RSSI i baterią;
- inspektor BLE z usługami, charakterystykami, właściwościami, deskryptorami i odczytanymi wartościami;
- nagrywanie krótkiej sesji RAW i eksport do pliku tekstowego w HEX/DEC;
- bieżący stan bramki akcelerometru oraz wartość osi Z używaną przez regulator;
- kategorie logów `BLE`, `PROTOCOL`, `IMU`, `CONTROL`, `MEDIA`, `SERVICE`, `PERMISSION`, `UPDATE`;
- Fake Triki generujący sekwencje jednego, dwóch i trzech kliknięć.

## Testy

Testy JVM obejmują:

- dekodowanie little-endian, skalowanie, startup discard i resynchronizację parsera;
- autodetekcję przycisku/licznika, debounce, wieloklik, odbicia styku, długie przytrzymanie i zerwanie strumienia;
- medianowe odrzucanie skoków, smoothing, adaptacyjną martwą strefę gyro, korekcję biasu i walidację kalibracji;
- dodatni i ujemny kierunek regulatora Z, pełne 900 ms bezruchu, tolerancję 0,8–1,2 g, położenie poziome, blokadę przy 90°/180°, ruch poza osią, histerezę oraz ponowne uzbrajanie po przerwie strumienia;
- mapowanie przycisk → akcja i brak wywołania dla `NONE`;
- round-trip serializacji ustawień, mapowań przycisku i kalibracji, migrację starszej konwencji osi oraz bezpieczne ignorowanie pól poprzedniego systemu sterowania;
- parsowanie i numeryczne porównywanie wersji semantycznych używanych przez aktualizator.

## Znane ograniczenia

- Fizyczna walidacja wymaga konkretnego egzemplarza Triki. Projekt kompiluje się i ma testy dekodera na potwierdzonych ramkach, ale progi regulatora należy ostatecznie potwierdzić na rzeczywistym kapslu.
- Częstotliwość IMU nie jest zakodowana jako gwarantowana stała protokołu. UI pokazuje wartość mierzoną na żywo; parser interpoluje znaczniki wewnątrz burstu wyłącznie na potrzeby stabilnego filtru.
- Yaw bez magnetometru dryfuje. Pitch i roll są korygowane grawitacją, natomiast yaw opiera się na całkowaniu żyroskopu.
- Czujniki IMU nie potrafią potwierdzić fizycznego kontaktu ze stołem. Połączenie kierunku grawitacji, 900 ms spoczynku akcelerometru i żyroskopu oraz blokady ruchu poza osią bardzo ogranicza przypadkowe sterowanie, ale urządzenie trzymane idealnie płasko i nieruchomo w powietrzu może spełnić te same warunki.
- Autołączenie wymaga włączonego Bluetooth, przyznanego dostępu do urządzeń w pobliżu i aktywnego ustawienia **Sterowanie w tle**. Wymuszone zatrzymanie aplikacji w ustawieniach Androida blokuje jej usługi i odbiorniki do następnego ręcznego uruchomienia; część nakładek producentów może również wymagać zezwolenia na autostart.
- Metadane i okładka wymagają dostępu do aktywnej MediaSession. Play/Pause, Next, Previous i Stop mają fallback przez standardowe klawisze multimedialne, ale ostateczna obsługa komendy zależy od aktywnego odtwarzacza.
- Aktualizator obsługuje publiczne, stabilne wydania GitHub zawierające jednoznaczny APK release. Android wymaga zgody na instalowanie z tego źródła oraz osobnego potwierdzenia każdej instalacji.
- Akcje specyficzne dla Spotify/YouTube Music, takie jak like, shuffle i repeat, nie mają wspólnego publicznego API Android MediaSession. Architektura pozwala dodać jawne integracje w przyszłości, ale obecna wersja nie udaje ich działania.

## Licencja i znaki towarowe

„Żabka”, „Żappka”, „Spotify”, „YouTube Music”, „TIDAL” i „Apple Music” są znakami ich właścicieli. Projekt jest niezależnym narzędziem interoperacyjności i nie jest przez nich sponsorowany ani zatwierdzony.
