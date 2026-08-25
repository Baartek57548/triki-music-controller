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
- `TrikiBleManager` implementuje maszynę stanów `DISCONNECTED → SCANNING → FOUND → CONNECTING → CONNECTED → READY`, błędy i `RECONNECTING`.
- `SensorFilter` stosuje bias kalibracyjny, medianę z trzech próbek, adaptacyjną martwą strefę żyroskopu, low-pass i filtr komplementarny pitch/roll/yaw.
- `GyroscopeVolumeController` wymaga 120 ms odczytu 0,8–1,2 g, stosuje histerezę 18/10°/s i całkuje żyroskop Z do kroków co 15°.
- `TrikiButtonInterpreter` rozpoznaje wariant pola statusu, eliminuje odbicia styku i liczy od jednego do trzech kliknięć bez fałszywej interpretacji licznika pakietów.
- `AppLogger` przechowuje maksymalnie 400 skróconych wpisów; nie rośnie bez końca.

### Data

`DataStoreSettingsRepository` zapisuje cały snapshot ustawień jako wersjonowalny JSON w atomowym Preferences DataStore. Decoder toleruje nieznane przyszłe pola i normalizuje brak profili. Pola poprzedniego systemu sterowania są ignorowane podczas odczytu, dlatego aktualizacja nie uszkadza zachowanych ustawień przycisku, urządzenia ani kalibracji.

`AndroidMediaControllerGateway` wybiera najpierw sesję w stanie playing/buffering/connecting, a w drugiej kolejności ostatnio aktualizowaną. Gdy Xiaomi lub inny system blokuje dostęp Notification Listener, publiczne `AudioManager.dispatchMediaKeyEvent()` wysyła pełną parę DOWN/UP dla Play/Pause, Next, Previous i Stop. Dostęp do sesji pozostaje potrzebny tylko do metadanych i precyzyjnego stanu odtwarzacza. `AudioManager` obsługuje też globalną głośność strumienia muzyki.

### Runtime

`TrikiRuntime` jest jedynym miejscem łączącym sensor lub przycisk z akcją. Zmiana kalibracji lub parametrów filtru resetuje cały pipeline, aby nie mieszać dwóch układów odniesienia. `TrikiButtonInterpreter` ma pierwszeństwo podczas kliknięcia, ponieważ nacisk również porusza IMU. Po zakończeniu sekwencji kliknięć regulator Z jest zerowany i ponownie wymaga 120 ms stabilnego odczytu akcelerometru. Przerwa strumienia dłuższa niż 250 ms także wymusza ponowne uzbrojenie.

### Presentation

`MainViewModel` orkiestruje intencje użytkownika, ale nie ma logiki protokołu ani regulatora. Compose renderuje stan, obsługuje Activity Result API dla uprawnień/eksportu i zapewnia nawigację. Ekrany szczegółowe są oddzielnymi composables.

## BLE lifecycle

1. Skan działa maksymalnie 15 sekund i tylko na żądanie albo w oknie reconnect.
2. Znaleziony adres jest zapisywany po świadomym wyborze użytkownika.
3. Po connect wykonywane jest discovery i sekwencyjne odczyty metadanych, ponieważ Android GATT dopuszcza jedną operację naraz.
4. Włączenie CCCD NUS TX poprzedza zapis komendy startowej.
5. Po stanie READY RSSI jest odczytywane co 10 sekund, a nie w pętli wysokiej częstotliwości.
6. Utrata połączenia uruchamia backoff 2, 4, 8, 16, 32, maksymalnie 60 sekund.
7. Jawne `Rozłącz` kasuje próbę reconnect i zamyka obiekt `BluetoothGatt`.

Raw buffer ma 300 pakietów, a historia wykresu 360 przefiltrowanych próbek. Oba limity zapobiegają narastaniu pamięci.

## Praca w tle

`TrikiForegroundService` ma typ `connectedDevice`, niski kanał powiadomień i akcję rozłączenia. Uruchamia się tylko przy włączonym ustawieniu „Sterowanie w tle”. Po przejściu do `DISCONNECTED` lub `ERROR` czeka pięć sekund i kończy się, jeśli stan nie zmieni się na aktywny.

Android może ograniczyć start foreground service z tła. Aplikacja rozpoczyna usługę podczas jawnego działania w Activity; sama usługa może następnie prowadzić reconnect, gdy proces pozostaje żywy.

## Obsługa błędów

- Brak adaptera, uprawnień lub włączonego Bluetooth jest walidowany przed skanem.
- Każdy status GATT trafia do stanu błędu i rotującego logu.
- Brak NUS RX/TX przerywa tylko próbę streamu; inspector nadal pokazuje GATT.
- Brak baterii/metadanych jest stanem opcjonalnym, nie wyjątkiem.
- Brak aktywnej MediaSession uruchamia legalny fallback media-key; błąd jest zwracany dopiero, gdy platforma odrzuci również tę drogę.
- Uszkodzone settings JSON wraca do bezpiecznych defaults.
- Kalibracja odrzuca zbyt mało próbek, nieprawidłową grawitację i zbyt duży szum żyroskopu.

## Rozszerzalność

Nową akcję należy dodać do `MediaAction`, adaptera gateway i UI pickera. Akcje specyficzne dla aplikacji powinny dostać osobny gateway zamiast warunków w `TrikiRuntime`. Parametry regulatora Z są skupione w walidowanym `GyroscopeVolumeController.Configuration` i powinny być zmieniane razem z testami granicznymi.
