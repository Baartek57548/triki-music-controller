# Triki Music Controller

Triki Music Controller zmienia kontroler **Żabka Triki** w pilot do muzyki na Androidzie i Windows 11. Obie aplikacje odbierają strumień IMU przez Bluetooth Low Energy, regulują głośność obrotem kapsla wokół osi Z i obsługują akcje multimedialne przez fizyczny przycisk.

To nie jest emulator Żappki i nie omija zabezpieczeń żadnej usługi. Implementacje używają publicznych API Androida oraz Windows: GATT/Bluetooth LE, systemowych sesji multimedialnych i systemowego sterowania głośnością.

## Pobieranie

Najnowsze stabilne wydanie znajduje się na stronie [GitHub Releases](https://github.com/Baartek57548/triki-music-controller/releases/latest):

- Android: podpisany plik `triki-music-controller-android-v…-release.apk`;
- Windows 11 x64: `triki-music-controller-windows-v…-setup.exe`, czyli kreator instalacji per-user bez wymagania osobnej instalacji .NET lub Windows App SDK.

Obie aplikacje sprawdzają nowe stabilne wydanie przy uruchomieniu. Pobieranie i instalacja wymagają decyzji użytkownika; plik jest przyjmowany tylko z tego repozytorium i weryfikowany przez rozmiar oraz SHA-256 z metadanych GitHub.

## Funkcje

- pełny cykl BLE: skan pierwszego urządzenia na żądanie, zapamiętanie po udanym połączeniu, pasywne GATT `autoConnect`, discovery, NUS notifications, timeout, RSSI i bateria;
- potwierdzony dekoder ramek IMU z resynchronizacją po rozciętych i sklejonych notyfikacjach;
- ciągły regulator głośności wykorzystujący dokładnie przefiltrowaną wartość żyroskopu Z: dodatnie Z podgłaśnia, ujemne Z ścisza;
- 2-sekundowa stabilizacja kąta 0–25° bez wymogu bezruchu, po której regulator działa także podczas trzymania i poruszania kapslem w powietrzu;
- wielostopniowa filtracja IMU: mediana odrzucająca pojedyncze skoki, adaptacyjna martwa strefa gyro, low-pass i filtr komplementarny;
- dodatkowe wygładzenie żyroskopu Z, histereza, limit częstotliwości kroków i całkowanie prędkości kątowej ograniczające gwałtowne skoki głośności;
- opcjonalna kalibracja biasu akcelerometru/żyroskopu, neutralnej pozycji i szumu;
- bezpieczna autodetekcja przycisku: jeden klik steruje Play/Pause, dwa przechodzą do następnego utworu, trzy do poprzedniego; każde mapowanie można zmienić w profilu;
- konfigurowalne mapowania przycisku zapisywane w Preferences DataStore;
- przytrzymanie przycisku i pionowy ruch o około 20 cm: podniesienie wysyła Like, opuszczenie Dislike oraz odtwarza krótki sygnał powodzenia lub błędu;
- sterowanie Play, Pause, Play/Pause, Next, Previous, Like, Dislike, Stop, Volume +/−, Mute i Unmute;
- dashboard z orientacją Triki, baterią, RSSI, częstotliwością ramek i informacją o odtwarzanym utworze;
- uproszczone ekrany robocze i wspólny ekran **Informacje**, otwierany ikoną w prawym górnym rogu aplikacji Android i Windows;
- onboarding, czytelna lista warunków bezpiecznej regulacji, monitor czujników i inspektor BLE;
- rotujący bufor pakietów RAW z eksportem HEX/DEC oraz ograniczony bufor logów;
- foreground service czekający na wybudzenie zapamiętanego Triki, akcja wyłączająca autołączenie oraz przywrócenie czuwania po restarcie telefonu;
- `FakeTrikiDataSource` dostępny wyłącznie w buildzie debug po włączeniu trybu deweloperskiego;
- automatyczne sprawdzanie najnowszego wydania GitHub przy uruchomieniu wersji release, ręczne sprawdzanie na ekranie **Informacje** oraz weryfikowany instalator APK;
- jasny, ciemny i systemowy motyw Material 3, edge-to-edge oraz responsywny dashboard.
- natywna wersja Windows 11 w WinUI 3: ten sam dekoder, filtr, gesty i mapowania, globalna sesja multimedialna, głośność domyślnego urządzenia audio, zapamiętywanie adresu Triki oraz automatyczne łączenie po naciśnięciu przycisku;
- instalator Windows z opcjonalnym skrótem, autostartem w tle, deinstalatorem i wbudowanym mechanizmem aktualizacji.

## Wymagania

- Android 8.0 (API 26) lub nowszy;
- telefon z Bluetooth Low Energy;
- kontroler Żabka Triki; przed skanowaniem należy nacisnąć jego przycisk, aby go wybudzić;
- JDK 17 lub nowszy oraz Android SDK 36 do budowania projektu.
- Windows 11 w wersji 22H2 lub nowszej i architektura x64 dla aplikacji desktopowej;
- .NET SDK 10, Visual Studio z workloadem Windows App SDK oraz Inno Setup 6 tylko do samodzielnego budowania wersji Windows.

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
9. Uruchom muzykę i utrzymuj kapsel górą do góry w przechyle 0–25° przez 2 sekundy. Nie musi być nieruchomy. Gdy UI pokaże „Regulator gotowy”, obracaj go: dodatnia wartość Z podgłaśnia, ujemna ścisza.
10. Aby ocenić utwór, przytrzymaj przycisk około pół sekundy, a następnie podnieś kapsel o 20–30 cm dla Like albo opuść go o 20–30 cm dla Dislike. Sygnał potwierdzi wysłanie akcji; sygnał błędu oznacza brak obsługi przez aktywny odtwarzacz.
11. Gdy Triki uśnie i rozłączy BLE, naciśnij jego fizyczny przycisk. Przy włączonym **Sterowaniu w tle** Android automatycznie dokończy oczekujące połączenie z zapamiętanym kapslem — bez ponownego wybierania urządzenia.

Build z linii poleceń na Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

Na macOS/Linux odpowiednikami są `./gradlew assembleDebug` i `./gradlew test`.

Build i testy Windows z linii poleceń:

```powershell
dotnet test .\windows\TrikiMusicController.Windows.Tests\TrikiMusicController.Windows.Tests.csproj -c Release -p:Platform=x64
dotnet publish .\windows\TrikiMusicController.Windows\TrikiMusicController.Windows.csproj -c Release -r win-x64 --self-contained true -p:Platform=x64 -o .\windows\artifacts\publish
& "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" .\windows\installer\TrikiMusicController.iss
```

Instalator domyślnie umieszcza aplikację w `%LOCALAPPDATA%\Programs\Triki Music Controller`. Włączenie autostartu uruchamia ją z argumentem `--background`, dzięki czemu czeka zminimalizowana na reklamę BLE zapamiętanego kapsla.

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
GyroscopeVolumeController + 2 s stabilizacji przechyłu 0–25°
    ↓
HoldVerticalGestureDetector + przycisk/ruch góra–dół
    ↓
ActionMapper + mapowanie przycisku i rating MediaSession
    ↓
AndroidMediaControllerGateway
```

Wersja Windows zachowuje ten sam deterministyczny rdzeń i ma osobne adaptery platformowe:

```text
BluetoothLEAdvertisementWatcher → BluetoothService → TrikiProtocolDecoder
    → SensorFilter → GyroscopeVolumeController / HoldVerticalGestureDetector
    → TrikiRuntimeEngine → GSMTC / Core Audio EndpointVolume
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

`GyroscopeVolumeController` przyjmuje przefiltrowaną wartość żyroskopu Z w °/s i całkuje ją do kąta obrotu. Zakres 0–25° musi być zachowany nieprzerwanie przez 2 sekundy, ale nie ma wymogu bezruchu, odkładania kapsla ani długości wektora 0,8–1,2 g. Dodatkowy filtr Z oraz limit jednego kroku na 100 ms wygładzają regulację. Każde 15° daje jeden krok głośności; znak Z określa kierunek. Przekroczenie 25° zeruje stabilizację i nagromadzony obrót. Położenie pionowe 90° i odwrócone 180° nie może zmienić głośności.

Martwa strefa osi Z 18°/s, próg zwolnienia 10°/s, zerowanie po zmianie kierunku, wyjściu poza 25° i przerwie strumienia ograniczają niezamierzone skoki. Interfejs pokazuje bieżący przechył, dozwolony zakres i wartość żyroskopu Z, dzięki czemu użytkownik wie, co blokuje sterowanie.

Fizyczny przycisk nie korzysta z IMU ani kalibracji. Po potwierdzeniu wariantu `0/1` aplikacja stosuje debounce, liczy pełne cykle wciśnięcie–puszczenie i czeka 450 ms na następny klik. Firmware z licznikiem `0..15` oraz wariant naprzemienny `0/1` są ignorowane, aby identyfikatory ramek nie uruchamiały muzyki. Domyślnie: `×1 → Play/Pause`, `×2 → Next`, `×3 → Previous`.

`HoldVerticalGestureDetector` uruchamia się dopiero po około 500 ms potwierdzonego przytrzymania. Zapamiętuje lokalny wektor grawitacji, odejmuje go od kolejnych próbek i przez krótkie okno całkuje progowane przyspieszenie pionowe. Dla sprzętowej konwencji osi Triki podniesienie daje ujemne przemieszczenie i wysyła Like, a opuszczenie daje dodatnie przemieszczenie i wysyła Dislike; po akcji przytrzymanie jest konsumowane, więc puszczenie nie generuje dodatkowego pojedynczego kliknięcia. `AndroidMediaControllerGateway` korzysta ze standardowego `ACTION_SET_RATING` lub akcji niestandardowej jawnie wystawionej przez aktywną MediaSession.

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
- dodatni i ujemny kierunek regulatora Z, pełne 2 sekundy w przechyle 0–25°, brak bramki bezruchu i wartości g, wygładzenie Z, limit kroków, blokadę powyżej 25°, położenie 90°/180° oraz reset po przerwie strumienia;
- przytrzymanie konsumujące zwykły klik, estymację ruchu +/−20 cm, pojedyncze Like/Dislike na jedno przytrzymanie oraz bezpieczne dopasowanie niestandardowych akcji ratingu;
- mapowanie przycisk → akcja i brak wywołania dla `NONE`;
- round-trip serializacji ustawień, mapowań przycisku i kalibracji, migrację starszej konwencji osi oraz bezpieczne ignorowanie pól poprzedniego systemu sterowania;
- parsowanie i numeryczne porównywanie wersji semantycznych używanych przez aktualizator.

## Znane ograniczenia

- Fizyczna walidacja wymaga konkretnego egzemplarza Triki. Projekt kompiluje się i ma testy dekodera na potwierdzonych ramkach, ale progi regulatora należy ostatecznie potwierdzić na rzeczywistym kapslu.
- Częstotliwość IMU nie jest zakodowana jako gwarantowana stała protokołu. UI pokazuje wartość mierzoną na żywo; parser interpoluje znaczniki wewnątrz burstu wyłącznie na potrzeby stabilnego filtru.
- Yaw bez magnetometru dryfuje. Pitch i roll są korygowane grawitacją, natomiast yaw opiera się na całkowaniu żyroskopu.
- Kąt przechyłu jest wyznaczany z kierunku wektora akcelerometru. Silne przyspieszenie liniowe podczas trzymania kapsla w powietrzu może chwilowo zniekształcić ten kąt i zablokować sterowanie do powrotu w zakres 0–25°.
- Autołączenie wymaga włączonego Bluetooth, przyznanego dostępu do urządzeń w pobliżu i aktywnego ustawienia **Sterowanie w tle**. Wymuszone zatrzymanie aplikacji w ustawieniach Androida blokuje jej usługi i odbiorniki do następnego ręcznego uruchomienia; część nakładek producentów może również wymagać zezwolenia na autostart.
- Metadane i okładka wymagają dostępu do aktywnej MediaSession. Play/Pause, Next, Previous i Stop mają fallback przez standardowe klawisze multimedialne, ale ostateczna obsługa komendy zależy od aktywnego odtwarzacza.
- Aktualizator obsługuje publiczne, stabilne wydania GitHub zawierające jednoznaczny APK release. Android wymaga zgody na instalowanie z tego źródła oraz osobnego potwierdzenia każdej instalacji.
- Like/Dislike działa tylko wtedy, gdy aktywna aplikacja udostępnia standardowe `ACTION_SET_RATING` albo jednoznaczną akcję niestandardową MediaSession. Brak takiej możliwości zwraca błąd i osobny sygnał dźwiękowy; aplikacja nie deklaruje wtedy zmiany oceny.
- Windows Global System Media Transport Controls nie definiuje standardowej komendy Like/Dislike. Wersja Windows rozpoznaje poprawny kierunek gestu i sygnalizuje brak wykonania, ale bez osobnej integracji konkretnego odtwarzacza nie deklaruje zmiany oceny; Play/Pause, Next, Previous, Stop i głośność działają systemowo.
- Publiczny instalator Windows nie jest podpisany komercyjnym certyfikatem Authenticode, dlatego SmartScreen może pokazać ostrzeżenie nieznanego wydawcy. Integralność pliku można porównać z digestem SHA-256 publikowanym przez GitHub.

## Licencja i znaki towarowe

„Żabka”, „Żappka”, „Spotify”, „YouTube Music”, „TIDAL” i „Apple Music” są znakami ich właścicieli. Projekt jest niezależnym narzędziem interoperacyjności i nie jest przez nich sponsorowany ani zatwierdzony.
