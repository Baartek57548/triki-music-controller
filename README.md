# Triki Music Controller

Triki Music Controller zmienia kontroler **Żabka Triki** w pilot do muzyki na Androidzie i Windows 11. Obie aplikacje odbierają strumień IMU przez Bluetooth Low Energy, regulują głośność obrotem kapsla wokół osi Z i obsługują akcje multimedialne przez fizyczny przycisk.

To nie jest emulator Żappki i nie omija zabezpieczeń żadnej usługi. Implementacje używają publicznych API Androida oraz Windows: GATT/Bluetooth LE, systemowych sesji multimedialnych i systemowego sterowania głośnością.

## Pobieranie

Najnowsze stabilne wydanie znajduje się na stronie [GitHub Releases](https://github.com/Baartek57548/triki-music-controller/releases/latest):

- Android: podpisany plik `triki-music-controller-android-v…-release.apk`;
- Windows 11 x64: `triki-music-controller-windows-v…-setup.exe`, czyli kreator instalacji per-user bez wymagania osobnej instalacji .NET lub Windows App SDK.

Obie aplikacje sprawdzają nowe stabilne wydanie przy uruchomieniu. Pobieranie i instalacja wymagają decyzji użytkownika; plik jest przyjmowany tylko z tego repozytorium i weryfikowany przez rozmiar oraz SHA-256 z metadanych GitHub.

## Funkcje

- pełny cykl BLE: skan pierwszego urządzenia na żądanie, zapamiętanie po udanym połączeniu, automatyczne wybudzanie, discovery, NUS notifications, timeout, RSSI i bateria;
- dodatkowy tryb **Łącz tylko podczas użycia**: po 12 sekundach bez ruchu lub przycisku zamyka aktywne GATT, czeka na rzeczywiste zaśnięcie kapsla i łączy go ponownie przy kolejnym wybudzeniu;
- potwierdzony dekoder ramek IMU z resynchronizacją po rozciętych i sklejonych notyfikacjach;
- ciągły regulator głośności wykorzystujący dokładnie przefiltrowaną wartość żyroskopu Z: dodatnie Z podgłaśnia, ujemne Z ścisza;
- 2-sekundowa stabilizacja kąta 0–25° bez wymogu odkładania kapsla; gwałtowne przyspieszenie poza 0,80–1,20 g natychmiast wstrzymuje regulację i rozpoczyna stabilizację od nowa;
- wielostopniowa filtracja IMU: mediana odrzucająca pojedyncze skoki, adaptacyjna martwa strefa gyro, low-pass i filtr komplementarny;
- łagodne wygładzenie żyroskopu Z, histereza, ograniczenie zaległych kroków, limit częstotliwości i całkowanie prędkości kątowej ograniczające gwałtowne skoki głośności;
- opcjonalna kalibracja biasu akcelerometru/żyroskopu, neutralnej pozycji i szumu;
- bezpieczna autodetekcja przycisku: jeden klik steruje Play/Pause, dwa wysyłają Like, trzy Dislike; każde mapowanie można zmienić w profilu;
- konfigurowalne mapowania przycisku zapisywane w Preferences DataStore;
- obrót odwróconym kapslem o konfigurowalny kąt (domyślnie 200°, zakres 90–360°) bez wciskania przycisku: po 0,5 s stabilizacji ruch dłoni w lewo przechodzi do następnego utworu, a w prawo do poprzedniego; kąt można zmienić w ustawieniach aplikacji;
- sterowanie Play, Pause, Play/Pause, Next, Previous, Like, Dislike, Stop, Volume +/−, Mute i Unmute;
- dashboard z orientacją Triki, baterią, RSSI, częstotliwością ramek i informacją o odtwarzanym utworze;
- uproszczone ekrany robocze i wspólny ekran **Informacje**, otwierany ikoną w prawym górnym rogu aplikacji Android i Windows;
- onboarding, czytelna lista warunków bezpiecznej regulacji, monitor czujników i inspektor BLE;
- rotujący bufor pakietów RAW z eksportem HEX/DEC oraz ograniczony bufor logów;
- foreground service czekający na wybudzenie zapamiętanego Triki, akcja wyłączająca autołączenie oraz przywrócenie czuwania po restarcie telefonu;
- `FakeTrikiDataSource` dostępny wyłącznie w buildzie debug po włączeniu trybu deweloperskiego;
- automatyczne sprawdzanie najnowszego wydania GitHub przy uruchomieniu wersji release, ręczne sprawdzanie na ekranie **Informacje** oraz weryfikowany instalator APK;
- jasny, ciemny i systemowy motyw Material 3, edge-to-edge oraz responsywny dashboard.
- natywna wersja Windows 11 w WinUI 3: ten sam dekoder, filtr, gesty i mapowania, globalna sesja multimedialna, precyzyjna regulacja systemowego master volume domyślnego urządzenia przez Core Audio (bez zmiany głośności pojedynczej aplikacji), zapamiętywanie adresu Triki oraz automatyczne łączenie po naciśnięciu przycisku;
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
9. Uruchom muzykę i utrzymuj kapsel górą do góry w przechyle 0–25° przez 2 sekundy. Nie musi leżeć nieruchomo, ale unikaj szarpnięć; gwałtowne przyspieszenie ponownie uruchamia 2-sekundową stabilizację. Gdy UI pokaże „Regulator gotowy”, obracaj go łagodnie: dodatnia wartość Z podgłaśnia, ujemna ścisza.
10. Aby ocenić utwór, użyj przycisku: dwa kliknięcia oznaczają Like, a trzy kliknięcia Dislike (mapowania można zmienić w profilu). Aby zmienić utwór bez przycisku, odwróć kapsel, odczekaj około pół sekundy stabilizacji i obróć go o 270° wokół osi Z. Kierunek jest liczony jako ruch dłoni: lewo przechodzi do następnego utworu, prawo do poprzedniego.
11. Gdy Triki uśnie i rozłączy BLE, naciśnij jego fizyczny przycisk. Przy włączonym **Sterowaniu w tle** Android automatycznie dokończy oczekujące połączenie z zapamiętanym kapslem — bez ponownego wybierania urządzenia.
12. Opcjonalnie włącz **Ustawienia → Łącz tylko podczas użycia**. Aplikacja zamknie aktywne połączenie po 12 sekundach bezczynności; po komunikacie o uzbrojonym nasłuchu kolejne naciśnięcie przycisku ponownie połączy zapamiętany kapsel.

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
GyroscopeVolumeController + bramka 0,80–1,20 g + 2 s stabilizacji przechyłu 0–25°
    ↓
FullRotationGestureDetector + obrót 270° na odwróconym kapslu
    ↓
ActionMapper + mapowanie przycisku i rating MediaSession
    ↓
AndroidMediaControllerGateway
```

Wersja Windows zachowuje ten sam deterministyczny rdzeń i ma osobne adaptery platformowe:

```text
BluetoothLEAdvertisementWatcher → BluetoothService → TrikiProtocolDecoder
    → SensorFilter → GyroscopeVolumeController / FullRotationGestureDetector
    → TrikiRuntimeEngine → GSMTC / Core Audio EndpointVolume
                         ↘ ConnectionActivityLease → WakeAdvertisementGate
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

`GyroscopeVolumeController` przyjmuje przefiltrowaną wartość żyroskopu Z w °/s i całkuje ją do kąta obrotu. Zakres 0–25° musi być zachowany nieprzerwanie przez 2 sekundy; kapsel może być trzymany w powietrzu, ale długość wektora akcelerometru musi pozostać w tolerancji 0,80–1,20 g. Wyjście poza tę tolerancję jest traktowane jako gwałtowny ruch: blokuje akcję, czyści nagromadzony obrót i wymusza pełną ponowną stabilizację. Łagodniejszy filtr Z, limit jednego kroku na 140 ms i brak kolejki zaległych kroków wygładzają regulację. Każde 22° obrotu daje jeden krok głośności; znak Z określa kierunek. Przekroczenie 25° również zeruje stabilizację. Położenie pionowe 90° i odwrócone 180° nie może zmienić głośności.

Martwa strefa osi Z 22°/s, próg zwolnienia 12°/s, zerowanie po zmianie kierunku, gwałtownym przyspieszeniu, wyjściu poza 25° i przerwie strumienia ograniczają niezamierzone skoki. Interfejs pokazuje bieżący przechył, długość wektora akcelerometru i wartość żyroskopu Z, dzięki czemu użytkownik wie, co blokuje sterowanie.

Fizyczny przycisk nie korzysta z IMU ani kalibracji. Po potwierdzeniu wariantu `0/1` aplikacja stosuje debounce, liczy pełne cykle wciśnięcie–puszczenie i czeka 450 ms na następny klik. Firmware z licznikiem `0..15` oraz wariant naprzemienny `0/1` są ignorowane, aby identyfikatory ramek nie uruchamiały muzyki. Domyślnie: `×1 → Play/Pause`, `×2 → Like`, `×3 → Dislike`.

`FullRotationGestureDetector` uruchamia się po około 500 ms stabilizacji odwróconego kapsla — przycisk nie jest wymagany. Korzysta z tej samej filtrowanej i całkowanej osi Z co regulator głośności. Kierunek zaczyna być liczony po przekroczeniu 22°/s, a wewnętrzny próg 245° kompensuje około 20–25° tracone na krawędziach przez dwa stopnie filtracji, dzięki czemu akcja kończy się po fizycznym obrocie ręką o około 270°. Ze względu na odwrócenie kapsla ruch dłoni w lewo oznacza Next, a w prawo Previous. Zatrzymanie, zmiana kierunku, wyjście poza 0,80–1,20 g lub zbyt długi obrót zerują próbę. Dwa i trzy kliknięcia przycisku są domyślnie mapowane odpowiednio na Like i Dislike.

## Diagnostyka

Po włączeniu **Ustawienia → Tryb deweloperski** dostępne są:

- monitor czujników z wykresami X/Y/Z, orientacją, długością wektora, RSSI i baterią;
- inspektor BLE z usługami, charakterystykami, właściwościami, deskryptorami i odczytanymi wartościami;
- nagrywanie krótkiej sesji RAW i eksport do pliku tekstowego w HEX/DEC;
- bieżący stan bramki akcelerometru oraz wartość osi Z używaną przez regulator;
- kategorie logów `BLE`, `PROTOCOL`, `IMU`, `CONTROL`, `MEDIA`, `SERVICE`, `PERMISSION`, `UPDATE`;
- Fake Triki generujący sekwencje jednego, dwóch i trzech kliknięć.

## Testy

Testy automatyczne JVM i xUnit obejmują:

- dekodowanie little-endian, skalowanie, startup discard i resynchronizację parsera;
- autodetekcję przycisku/licznika, debounce, wieloklik, odbicia styku, długie przytrzymanie i zerwanie strumienia;
- medianowe odrzucanie skoków, smoothing, adaptacyjną martwą strefę gyro, korekcję biasu i walidację kalibracji;
- dodatni i ujemny kierunek regulatora Z, pełne 2 sekundy w przechyle 0–25°, tolerancję ruchu 0,80–1,20 g, reset po gwałtownym przyspieszeniu, łagodniejsze wygładzenie Z, limit kroków, blokadę powyżej 25°, położenie 90°/180° oraz reset po przerwie strumienia;
- stabilizację bez przycisku, fizyczny obrót osi Z o około 270° na odwróconym kapslu, odwrócone mapowanie lewo → Next i prawo → Previous, odrzucanie zmiany kierunku, zbyt długiego obrotu i niestabilnego przyspieszenia oraz automatyczne ponowne uzbrajanie po uspokojeniu ruchu;
- mapowanie przycisk → akcja i brak wywołania dla `NONE`;
- round-trip serializacji ustawień, mapowań przycisku i kalibracji, migrację starszej konwencji osi oraz bezpieczne ignorowanie pól poprzedniego systemu sterowania;
- parsowanie i numeryczne porównywanie wersji semantycznych używanych przez aktualizator;
- wygaszanie bezczynnego połączenia dokładnie raz, odporność na cofnięcie czasu oraz bramkę nowej reklamy BLE po pełnym zaśnięciu kapsla;
- wersja Windows dodatkowo testuje cały łańcuch regulatora Z i rzeczywisty endpoint Core Audio z przywróceniem pierwotnej głośności po próbie sprzętowej.

## Znane ograniczenia

- Fizyczna walidacja wymaga konkretnego egzemplarza Triki. Projekt kompiluje się i ma testy dekodera na potwierdzonych ramkach, ale progi regulatora należy ostatecznie potwierdzić na rzeczywistym kapslu.
- Częstotliwość IMU nie jest zakodowana jako gwarantowana stała protokołu. UI pokazuje wartość mierzoną na żywo; parser interpoluje znaczniki wewnątrz burstu wyłącznie na potrzeby stabilnego filtru.
- Yaw bez magnetometru dryfuje. Pitch i roll są korygowane grawitacją, natomiast yaw opiera się na całkowaniu żyroskopu.
- Kąt przechyłu jest wyznaczany z kierunku wektora akcelerometru. Silne przyspieszenie liniowe podczas trzymania kapsla w powietrzu celowo blokuje regulator i wymaga ponownych 2 sekund w zakresie 0–25° po powrocie do 0,80–1,20 g.
- Autołączenie wymaga włączonego Bluetooth, przyznanego dostępu do urządzeń w pobliżu i aktywnego ustawienia **Sterowanie w tle**. Wymuszone zatrzymanie aplikacji w ustawieniach Androida blokuje jej usługi i odbiorniki do następnego ręcznego uruchomienia; część nakładek producentów może również wymagać zezwolenia na autostart.
- Metadane i okładka wymagają dostępu do aktywnej MediaSession. Play/Pause, Next, Previous i Stop mają fallback przez standardowe klawisze multimedialne, ale ostateczna obsługa komendy zależy od aktywnego odtwarzacza.
- Aktualizator obsługuje publiczne, stabilne wydania GitHub zawierające jednoznaczny APK release. Android wymaga zgody na instalowanie z tego źródła oraz osobnego potwierdzenia każdej instalacji.
- Like/Dislike z dwóch lub trzech kliknięć działa tylko wtedy, gdy aktywna aplikacja udostępnia standardowe `ACTION_SET_RATING` albo jednoznaczną akcję niestandardową MediaSession. Brak takiej możliwości zwraca błąd; aplikacja nie deklaruje wtedy zmiany oceny.
- Obrót odwróconego kapsla o 270° korzysta z Next/Previous, więc działa wszędzie tam, gdzie odtwarzacz obsługuje standardowe przechodzenie między utworami. Windows Global System Media Transport Controls nie definiuje systemowej komendy Like/Dislike, dlatego oceny są dostępne przez kliknięcia tylko w aplikacji z obsługą MediaSession.
- Publiczny instalator Windows nie jest podpisany komercyjnym certyfikatem Authenticode, dlatego SmartScreen może pokazać ostrzeżenie nieznanego wydawcy. Integralność pliku można porównać z digestem SHA-256 publikowanym przez GitHub.

## Licencja i znaki towarowe

„Żabka”, „Żappka”, „Spotify”, „YouTube Music”, „TIDAL” i „Apple Music” są znakami ich właścicieli. Projekt jest niezależnym narzędziem interoperacyjności i nie jest przez nich sponsorowany ani zatwierdzony.
