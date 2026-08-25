# Architektura

## Cele

Architektura oddziela niestabilne elementy platformy Android (GATT, uprawnienia, sesje multimedialne i service lifecycle) od deterministycznej logiki IMU. Dzięki temu parser, filtr, kalibracja, regulator głośności i mapowanie przycisku są testowane na JVM bez telefonu i fizycznego Triki.

## Przepływ danych

```text
BluetoothLeScanner
  → BluetoothGattCallback
  → TrikiBleManager
      ├─ StateFlow<TrikiBleState>
      ├─ StateFlow<List<RawBlePacket>>
      └─ SharedFlow<TrikiSensorData>
             ↓
        TrikiRuntime
             ↓
        SensorFilter
             ↓
        GyroscopeVolumeController
             ↓
        ActionMapper
             ↓
        MediaControllerGateway
             ↓
        MediaController.TransportControls / AudioManager
```

UI jedynie obserwuje immutable `StateFlow`. Nie interpretuje bajtów BLE i nie przelicza danych czujników na akcje.

## Warstwy

### Domain

`domain/model` zawiera immutable modele urządzenia, IMU, orientacji, przycisku, ustawień i MediaSession. `domain/repository` definiuje kontrakty persistence i sterowania multimediami. `ActionMapper` jest małym use case bez zależności od Android UI.

### Core

- `TrikiProtocolDecoder` ma bufor streamu, resynchronizację, skalowanie i statystyki odrzuconych bajtów.
- `TrikiBleManager` implementuje maszynę stanów `DISCONNECTED → SCANNING → FOUND → CONNECTING → CONNECTED → READY`. Pierwsze połączenie jest bezpośrednie; po jego powodzeniu zapamiętany adres przechodzi przez `RECONNECTING` z systemowym GATT `autoConnect=true`, które pasywnie czeka na wybudzenie urządzenia.
- `SensorFilter` stosuje bias kalibracyjny, medianę z trzech próbek, adaptacyjną martwą strefę żyroskopu, low-pass i filtr komplementarny pitch/roll/yaw.
- `GyroscopeVolumeController` uzbraja się po 900 ms spoczynku żyroskopu poniżej 5°/s, przy 0,8–1,2 g i przechyle do 25° górą do góry. Po uzbrojeniu stosuje histerezę położenia do 32°, blokadę ruchu poza osią 22°/s, histerezę Z 18/10°/s i całkuje żyroskop Z do kroków co 15°.
- `TrikiButtonInterpreter` rozpoznaje wariant pola statusu, eliminuje odbicia styku i liczy od jednego do trzech kliknięć bez fałszywej interpretacji licznika pakietów.
- `AppLogger` przechowuje maksymalnie 400 skróconych wpisów; nie rośnie bez końca.

### Data

`DataStoreSettingsRepository` zapisuje cały snapshot ustawień jako wersjonowalny JSON w atomowym Preferences DataStore. Decoder toleruje nieznane przyszłe pola i normalizuje brak profili. Pola poprzedniego systemu sterowania są ignorowane podczas odczytu, a profile kalibracji ze starszą konwencją osi są migrowane do sprzętowo potwierdzonego położenia górą do góry (`Z ≈ −1 g`). Aktualizacja nie uszkadza więc zachowanych ustawień przycisku, urządzenia ani kalibracji.

`AndroidMediaControllerGateway` wybiera najpierw sesję w stanie playing/buffering/connecting, a w drugiej kolejności ostatnio aktualizowaną. Gdy Xiaomi lub inny system blokuje dostęp Notification Listener, publiczne `AudioManager.dispatchMediaKeyEvent()` wysyła pełną parę DOWN/UP dla Play/Pause, Next, Previous i Stop. Dostęp do sesji pozostaje potrzebny tylko do metadanych i precyzyjnego stanu odtwarzacza. `AudioManager` obsługuje też globalną głośność strumienia muzyki.

`GitHubUpdateManager` sprawdza wyłącznie najnowsze stabilne wydanie wskazanego repozytorium. Akceptuje pojedynczy APK release z zaufanej ścieżki HTTPS, ogranicza metadane i plik do stałych rozmiarów, a po pobraniu sprawdza rozmiar, opcjonalny digest SHA-256 z GitHub, identyfikator pakietu, rosnący `versionCode` i certyfikat podpisujący. Dopiero zweryfikowany plik z prywatnego cache jest udostępniany systemowemu instalatorowi przez `FileProvider`.

### Runtime

`TrikiRuntime` jest jedynym miejscem łączącym sensor lub przycisk z akcją. Zmiana kalibracji lub parametrów filtru resetuje cały pipeline, aby nie mieszać dwóch układów odniesienia. `TrikiButtonInterpreter` ma pierwszeństwo podczas kliknięcia, ponieważ nacisk również porusza IMU. Po zakończeniu sekwencji kliknięć regulator Z jest zerowany i ponownie wymaga pełnych 900 ms poziomego bezruchu. Przerwa strumienia dłuższa niż 250 ms, utrata połączenia, odwrócenie, nadmierny przechył lub ruch poza osią także wymuszają ponowne uzbrojenie.

### Presentation

`MainViewModel` orkiestruje intencje użytkownika, ale nie ma logiki protokołu ani regulatora. Compose renderuje stan, obsługuje Activity Result API dla uprawnień/eksportu i zapewnia nawigację. `VolumeControlPresentation` mapuje telemetrię bramki na jednoznaczne komunikaty: brak połączenia, ruch, odwrócenie, przechył, stabilizacja i gotowość. Ekrany szczegółowe są oddzielnymi composables.

Wersja release uruchamia jedno sprawdzenie aktualizacji po zakończeniu onboardingu, aby dialog sieciowy nie przerywał pierwszego uruchomienia. Brak nowszej wersji i błąd automatycznego sprawdzenia nie blokują startu aplikacji; ręczne sprawdzenie z ekranu **O aplikacji** pokazuje wynik. Pobieranie wymaga jawnej decyzji użytkownika, a instalacja pozostaje kontrolowana przez Androida.

## BLE lifecycle

1. Aktywny skan działa maksymalnie 15 sekund i wyłącznie na żądanie użytkownika; oczekiwanie na zapamiętane urządzenie realizuje GATT `autoConnect`, nie cykliczny skaner.
2. Znaleziony adres jest zapisywany po świadomym wyborze użytkownika.
3. Po connect wykonywane jest discovery i sekwencyjne odczyty metadanych, ponieważ Android GATT dopuszcza jedną operację naraz.
4. Włączenie CCCD NUS TX poprzedza zapis komendy startowej.
5. Po stanie READY RSSI jest odczytywane co 10 sekund, a nie w pętli wysokiej częstotliwości.
6. Po utracie pierwszego połączenia aplikacja rejestruje pasywne `autoConnect`. Błędy stosu GATT ponawiają samą rejestrację po 1, 2, 4, 8 i maksymalnie 15 sekundach; nie powstają cykliczne okna aktywnego skanowania.
7. Jawne `Rozłącz` kasuje oczekujące połączenie i zamyka obiekt `BluetoothGatt`; wyłączenie autołączenia w UI lub powiadomieniu dodatkowo utrwala tę decyzję w ustawieniach.

Raw buffer ma 300 pakietów, a historia wykresu 360 przefiltrowanych próbek. Oba limity zapobiegają narastaniu pamięci.

## Praca w tle

`TrikiForegroundService` ma typ `connectedDevice`, niski kanał powiadomień i akcję wyłączającą autołączenie. Uruchamia się tylko przy włączonym ustawieniu „Sterowanie w tle”. Dla zapamiętanego urządzenia pozostaje aktywny także w stanie `RECONNECTING`, dzięki czemu proces i oczekujący klient GATT istnieją, gdy uśpione Triki zostanie wybudzone przyciskiem. Jeżeli nie ma zapamiętanego urządzenia albo użytkownik wyłączy pracę w tle, usługa kończy się.

`TrikiBootReceiver` odtwarza usługę po `BOOT_COMPLETED` wyłącznie wtedy, gdy zapisane ustawienia zawierają urządzenie i włączone sterowanie w tle. Nie uruchamia skanowania okresowego. Po nieoczekiwanym zerwaniu klient GATT jest ponownie rejestrowany z ograniczonym backoffem, po czym Android pasywnie czeka na dostępność znanego adresu.

Ten podział odpowiada oficjalnemu modelowi Androida: pierwsze połączenie używa trybu bezpośredniego, natomiast `autoConnect=true` automatycznie łączy znane urządzenie, gdy ponownie stanie się dostępne. Szczegóły opisuje dokumentacja [Communicate in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background).

Android może ograniczyć start foreground service z tła, a nakładki producentów mogą wymagać osobnej zgody na autostart. Aplikacja uruchamia usługę podczas jawnego pierwszego połączenia, przywraca ją po restarcie telefonu tylko dla zapisanej zgody i reaguje na ponowne włączenie Bluetooth. Wymuszone zatrzymanie aplikacji przez użytkownika zawsze wyłącza jej odbiorniki do następnego ręcznego uruchomienia.

## Obsługa błędów

- Brak adaptera, uprawnień lub włączonego Bluetooth jest walidowany przed skanem.
- Każdy status GATT trafia do stanu błędu i rotującego logu.
- Brak NUS RX/TX przerywa tylko próbę streamu; inspector nadal pokazuje GATT.
- Brak baterii/metadanych jest stanem opcjonalnym, nie wyjątkiem.
- Brak aktywnej MediaSession uruchamia legalny fallback media-key; błąd jest zwracany dopiero, gdy platforma odrzuci również tę drogę.
- Uszkodzone settings JSON wraca do bezpiecznych defaults.
- Kalibracja odrzuca zbyt mało próbek, nieprawidłową grawitację, pozycję inną niż płaska górą do góry i zbyt duży szum żyroskopu.
- Aktualizator odrzuca prerelease, niejednoznaczny asset, niezaufany URL, nadmiarowy rozmiar, obcy package ID, niemalejący `versionCode` i inny certyfikat podpisujący.

## Rozszerzalność

Nową akcję należy dodać do `MediaAction`, adaptera gateway i UI pickera. Akcje specyficzne dla aplikacji powinny dostać osobny gateway zamiast warunków w `TrikiRuntime`. Parametry regulatora Z są skupione w walidowanym `GyroscopeVolumeController.Configuration` i powinny być zmieniane razem z testami granicznymi.
