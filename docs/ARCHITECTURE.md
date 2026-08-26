# Architektura

## Cele

Architektura oddziela elementy platformowe Androida i Windows (GATT, cykl życia, sesje multimedialne i głośność) od deterministycznej logiki IMU. Dzięki temu parser, filtr, regulator głośności oraz gesty są testowane bez telefonu i fizycznego Triki: na JVM dla Androida i w xUnit dla Windows.

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
- `TrikiBleManager` implementuje maszynę stanów `DISCONNECTED → SCANNING → FOUND → CONNECTING → CONNECTED → READY`. Pierwsze połączenie jest bezpośrednie; domyślnie zapamiętany adres przechodzi przez `RECONNECTING` z systemowym GATT `autoConnect=true`. Opcjonalny stan `WAITING_FOR_WAKE` zamyka aktywne GATT po 12 sekundach bezczynności i używa `WakeAdvertisementGate`, aby odróżnić trwającą reklamę od nowego wybudzenia po co najmniej 5 sekundach ciszy.
- `SensorFilter` stosuje bias kalibracyjny, medianę z trzech próbek, adaptacyjną martwą strefę żyroskopu, low-pass i filtr komplementarny pitch/roll/yaw.
- `GyroscopeVolumeController` wymaga 2 sekund ciągłego przechyłu 0–25° górą do góry. Kapsel może poruszać się w powietrzu, lecz długość wektora przyspieszenia musi pozostać w zakresie 0,8–1,2 g; gwałtowny ruch zeruje stabilizację i całkę. EMA Z `0,16`, histereza `22/12°/s`, jeden krok na `22°` obrotu i odstęp co najmniej 140 ms zapewniają delikatną regulację bez zaległych kroków.
- `TrikiButtonInterpreter` rozpoznaje wariant pola statusu, eliminuje odbicia styku i liczy od jednego do trzech kliknięć bez fałszywej interpretacji licznika pakietów.
- `FullRotationGestureDetector` po 500 ms stabilizacji wymaga odwróconego kapsla (grawitacja na dodatniej osi Z), bez udziału przycisku całkuje filtrowaną oś żyroskopu Z. Próg całkowania 245° kompensuje około 20–25° tracone przez dwa stopnie filtracji, więc odpowiada fizycznemu obrotowi dłoni o około 270°. Warstwa mapowania uwzględnia odwrócenie kapsla: ruch dłoni w lewo wywołuje Next, a w prawo Previous. Zatrzymanie, zmiana kierunku, wyjście poza tolerancję akcelerometru lub zbyt długi obrót zerują próbę, po czym detektor automatycznie uzbraja się po uspokojeniu.
- `AppLogger` przechowuje maksymalnie 400 skróconych wpisów; nie rośnie bez końca.

### Data

`DataStoreSettingsRepository` zapisuje cały snapshot ustawień jako wersjonowalny JSON w atomowym Preferences DataStore. Decoder toleruje nieznane przyszłe pola i normalizuje brak profili. Pola poprzedniego systemu sterowania są ignorowane podczas odczytu, a profile kalibracji ze starszą konwencją osi są migrowane do sprzętowo potwierdzonego położenia górą do góry (`Z ≈ −1 g`). Aktualizacja nie uszkadza więc zachowanych ustawień przycisku, urządzenia ani kalibracji.

`AndroidMediaControllerGateway` wybiera najpierw sesję w stanie playing/buffering/connecting, a w drugiej kolejności ostatnio aktualizowaną. Gdy Xiaomi lub inny system blokuje dostęp Notification Listener, publiczne `AudioManager.dispatchMediaKeyEvent()` wysyła pełną parę DOWN/UP dla Play/Pause, Next, Previous i Stop. Like/Dislike wymaga aktywnej sesji: adapter preferuje jej zadeklarowaną akcję niestandardową, a następnie standardowe `ACTION_SET_RATING` dla serca lub kciuka. `AudioManager` obsługuje globalną głośność, a `RatingFeedbackPlayer` generuje rozróżnialne krótkie tony sukcesu i błędu.

`GitHubUpdateManager` sprawdza wyłącznie najnowsze stabilne wydanie wskazanego repozytorium. Akceptuje pojedynczy APK release z zaufanej ścieżki HTTPS, ogranicza metadane i plik do stałych rozmiarów, a po pobraniu sprawdza rozmiar, opcjonalny digest SHA-256 z GitHub, identyfikator pakietu, rosnący `versionCode` i certyfikat podpisujący. Dopiero zweryfikowany plik z prywatnego cache jest udostępniany systemowemu instalatorowi przez `FileProvider`.

### Runtime

`TrikiRuntime` jest jedynym miejscem łączącym sensor lub przycisk z akcją. Przekazuje próbki do detektora obrotu 270° bez warunku przycisku; po rozpoznaniu kierunku wyłącza regulator Z i wysyła Next dla ruchu dłoni w lewo albo Previous dla ruchu w prawo przez `ActionMapper`. Dwa i trzy kliknięcia przycisku pozostają niezależne i są domyślnie mapowane na Like/Dislike. Po każdej interakcji przyciskiem 2-sekundowa stabilizacja kąta rozpoczyna się od nowa. Przerwa strumienia dłuższa niż 250 ms, utrata połączenia, odwrócenie lub przekroczenie 25° także zerują stabilizację i obrót.

### Presentation

`MainViewModel` orkiestruje intencje użytkownika, ale nie ma logiki protokołu ani regulatora. Compose renderuje stan, obsługuje Activity Result API dla uprawnień/eksportu i zapewnia nawigację. `VolumeControlPresentation` mapuje telemetrię bramki na jednoznaczne komunikaty: brak połączenia, ruch, odwrócenie, przechył, stabilizacja i gotowość. Ekrany szczegółowe są oddzielnymi composables.

Wersja release uruchamia jedno sprawdzenie aktualizacji po zakończeniu onboardingu, aby dialog sieciowy nie przerywał pierwszego uruchomienia. Brak nowszej wersji i błąd automatycznego sprawdzenia nie blokują startu aplikacji; ręczne sprawdzenie z ekranu **O aplikacji** pokazuje wynik. Pobieranie wymaga jawnej decyzji użytkownika, a instalacja pozostaje kontrolowana przez Androida.

## BLE lifecycle

1. Aktywny skan pierwszego urządzenia działa maksymalnie 15 sekund i wyłącznie na żądanie użytkownika. Domyślny tryb oczekiwania na zapamiętane urządzenie realizuje GATT `autoConnect`; dodatkowy tryb „tylko podczas użycia” prowadzi ciągły, zbalansowany nasłuch wyłącznie po świadomym włączeniu tej opcji.
2. Znaleziony adres jest zapisywany po świadomym wyborze użytkownika.
3. Po connect wykonywane jest discovery i sekwencyjne odczyty metadanych, ponieważ Android GATT dopuszcza jedną operację naraz.
4. Włączenie CCCD NUS TX poprzedza zapis komendy startowej.
5. Po stanie READY RSSI jest odczytywane co 10 sekund, a nie w pętli wysokiej częstotliwości.
6. Po utracie pierwszego połączenia aplikacja rejestruje pasywne `autoConnect`. Błędy stosu GATT ponawiają samą rejestrację po 1, 2, 4, 8 i maksymalnie 15 sekundach; nie powstają cykliczne okna aktywnego skanowania.
7. Jawne `Rozłącz` kasuje oczekujące połączenie i zamyka obiekt `BluetoothGatt`; wyłączenie autołączenia w UI lub powiadomieniu dodatkowo utrwala tę decyzję w ustawieniach.
8. W trybie na żądanie `ConnectionActivityLease` odnawia 12-sekundową dzierżawę przy przycisku, geście, obrocie lub odchyleniu przyspieszenia. Po jej wygaśnięciu połączenie jest parkowane dokładnie raz, a nowa reklama może połączyć urządzenie dopiero po wykrytej przerwie nadawania.

Raw buffer ma 300 pakietów, a historia wykresu 360 przefiltrowanych próbek. Oba limity zapobiegają narastaniu pamięci.

## Windows 11

Projekt `windows/TrikiMusicController.Windows` jest natywną, niepakietowaną aplikacją WinUI 3 x64. `BluetoothService` prowadzi watcher reklam BLE, zapamiętuje adres po pierwszym pełnym połączeniu, odkrywa Nordic UART Service, włącza TX Notify i zapisuje potwierdzoną komendę startową. Po uśpieniu urządzenia watcher pozostaje aktywny; naciśnięcie przycisku powoduje reklamę znanego adresu i automatyczne połączenie. W dodatkowym trybie na żądanie ta sama bramka ciszy chroni przed natychmiastowym połączeniem z kapslem, który jeszcze nie zdążył zasnąć.

`TrikiRuntimeEngine` zachowuje priorytet Androida: obrót dłoni o 270° na odwróconym kapslu działa bez przycisku i mapuje lewo na Next, a prawo na Previous; następnie obsługiwane są sekwencje przycisku, a regulator Z działa tylko bez aktywnej interakcji przyciskiem. `MediaControlService` używa Global System Media Transport Controls do Play/Pause/Next/Previous/Stop, natomiast ścieżka regulatora Z omija MediaSession i wywołuje bezpośrednio systemowy `SystemVolumeService`. `SystemVolumeService` wybiera domyślny endpoint roli `Console` i ustawia jego skalarny poziom Core Audio, czyli master volume systemu, bez zależności od kroków sterownika i bez zmiany głośności pojedynczej aplikacji; pojedyncza akcja zmienia poziom o 2 punkty procentowe. GSMTC nie ma standardowej akcji oceny utworu, dlatego Like/Dislike pozostaje mapowaniem kliknięć wymagającym obsługi oceniania przez odtwarzacz.

`UpdateService` sprawdza najnowsze stabilne wydanie GitHub na starcie. Akceptuje dokładnie jeden instalator Windows z oczekiwaną nazwą i ścieżką HTTPS, wymaga rosnącej wersji, limituje rozmiar oraz porównuje SHA-256 przed sprawdzeniem nagłówka PE i otwarciem kreatora. Publikacja jest self-contained, a Inno Setup instaluje ją per-user, oferuje autostart `--background`, skróty i pełny deinstalator.

## Praca w tle

`TrikiForegroundService` ma typ `connectedDevice`, niski kanał powiadomień i akcję wyłączającą autołączenie. Uruchamia się tylko przy włączonym ustawieniu „Sterowanie w tle”. Dla zapamiętanego urządzenia pozostaje aktywny w stanie `RECONNECTING` albo `WAITING_FOR_WAKE`, dzięki czemu proces i oczekujący klient GATT lub skaner istnieją, gdy uśpione Triki zostanie wybudzone przyciskiem. Jeżeli nie ma zapamiętanego urządzenia albo użytkownik wyłączy pracę w tle, usługa kończy się.

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
- Skrajne i niefinitywne wartości kalibracji są odrzucane, a normalizacja kąta ma stały koszt także dla największych wartości `Float`.
- Kalibracja odrzuca zbyt mało próbek, nieprawidłową grawitację, pozycję inną niż płaska górą do góry i zbyt duży szum żyroskopu.
- Aktualizator odrzuca prerelease, niejednoznaczny asset, niezaufany URL, nadmiarowy rozmiar, obcy package ID, niemalejący `versionCode` i inny certyfikat podpisujący.

## Rozszerzalność

Nową akcję należy dodać do `MediaAction`, adaptera gateway i UI pickera. Akcje specyficzne dla aplikacji powinny dostać osobny gateway zamiast warunków w `TrikiRuntime`. Parametry regulatora Z są skupione w walidowanym `GyroscopeVolumeController.Configuration` i powinny być zmieniane razem z testami granicznymi.
