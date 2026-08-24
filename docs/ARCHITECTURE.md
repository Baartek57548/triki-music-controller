# Architektura

## Cele

Architektura oddziela niestabilne elementy platformy Android (GATT, uprawnienia, sesje multimedialne i service lifecycle) od deterministycznej logiki IMU. Dzięki temu parser, filtr, kalibracja, gesture engine i mapowanie akcji są testowane na JVM bez telefonu i fizycznego Triki.

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
        GestureFeatureExtractor
             ↓
        PersonalizedGestureClassifier (k-NN) + GestureEngine
             ↓
        ActionMapper
             ↓
        MediaControllerGateway
             ↓
        MediaController.TransportControls / AudioManager
```

UI jedynie obserwuje immutable `StateFlow`. Nie interpretuje bajtów BLE i nie klasyfikuje gestów.

## Warstwy

### Domain

`domain/model` zawiera immutable modele urządzenia, IMU, orientacji, gestów, profili, ustawień i MediaSession. `domain/repository` definiuje kontrakty persistence i sterowania multimediami. `ActionMapper` jest małym use case bez zależności od Android UI.

### Core

- `TrikiProtocolDecoder` ma bufor streamu, resynchronizację, skalowanie i statystyki odrzuconych bajtów.
- `TrikiBleManager` implementuje maszynę stanów `DISCONNECTED → SCANNING → FOUND → CONNECTING → CONNECTED → READY`, błędy i `RECONNECTING`.
- `SensorFilter` stosuje bias kalibracyjny, medianę z trzech próbek, adaptacyjną martwą strefę żyroskopu, low-pass i filtr komplementarny pitch/roll/yaw.
- `GestureFeatureExtractor` przycina nagranie do aktywnego ruchu i tworzy 40 znormalizowanych cech: energie i maksima obu sensorów, całki gyro, obrót względem grawitacji, zmianę wektora grawitacji, przyspieszenie poziome, impulsy, odwrócenia kierunku oraz osiem przedziałów czasowych dla accel i gyro.
- `PersonalizedGestureClassifier` jest małym lokalnym modelem k-NN. Przechowuje maksymalnie pięć wektorów na gest, stosuje adaptacyjny promień klasy, margines względem drugiej klasy i fizyczną bramkę bezpieczeństwa.
- `GestureEngine` klasyfikuje kompletne okno `spoczynek → ruch → spoczynek`, łączy model personalizowany z regułami bazowymi, utrzymuje lokalną bazę kąta i zwraca najwyżej jedno zdarzenie z jednego ruchu.
- `GestureRecordingAnalyzer` uruchamia ten sam silnik na ręcznie wybranym zakresie Start/Stop i zwraca metryki jakości nagrania.
- `AppLogger` przechowuje maksymalnie 400 skróconych wpisów; nie rośnie bez końca.

### Data

`DataStoreSettingsRepository` zapisuje cały snapshot ustawień jako wersjonowalny JSON w atomowym Preferences DataStore. Decoder toleruje nieznane przyszłe pola, normalizuje brak profili i próbki o niewłaściwym schemacie. Zapisywane są wyłącznie 40-elementowe wektory cech oraz etykieta gestu; surowe nagrania IMU nie trafiają do modelu ani poza telefon.

`AndroidMediaControllerGateway` wybiera najpierw sesję w stanie playing/buffering/connecting, a w drugiej kolejności ostatnio aktualizowaną. Gdy Xiaomi lub inny system blokuje dostęp Notification Listener, publiczne `AudioManager.dispatchMediaKeyEvent()` wysyła pełną parę DOWN/UP dla Play/Pause, Next, Previous i Stop. Dostęp do sesji pozostaje potrzebny tylko do metadanych i precyzyjnego stanu odtwarzacza. `AudioManager` obsługuje też globalną głośność strumienia muzyki.

### Runtime

`TrikiRuntime` jest jedynym miejscem łączącym sensor lub przycisk z akcją. Utrzymuje bieżący snapshot ustawień, więc zmiana profilu lub czułości działa bez restartu połączenia. Zmiana kalibracji/progów resetuje stan filtrów, aby nie mieszać dwóch układów odniesienia. Kalibracja poprawia bias i martwą strefę, ale nie jest bramką: stabilny lokalny spoczynek automatycznie uzbraja gesty. `TrikiButtonInterpreter` adaptacyjnie wybiera tryb statusu i ma pierwszeństwo przed niejednoznacznym ruchem IMU podczas kliknięcia. Podczas nagrania treningowego lub kroku kreatora wszystkie akcje są czasowo zawieszone, a `SharedFlow<FilteredSensorData>` nadal zasila analizator i wykres.

Po pierwszej poprawnej kalibracji albo zmianie wersji schematu uczenia nawigacja otwiera `GestureWizardScreen`. `GestureWizardUiState` utrzymuje bieżący krok, akcje, nauczone i pominięte próby oraz stan atomowego zapisu. Każde mapowanie i zaakceptowana próbka trafiają od razu do DataStore; numer ukończonej wersji uczenia jest zapisywany dopiero na ekranie podsumowania, więc przerwanego kreatora nie uznaje się za zakończony.

### Presentation

`MainViewModel` orkiestruje intencje użytkownika, ale nie ma logiki protokołu. Odpowiada również za ograniczony czasowo cykl Start/Stop rejestratora i zawsze przywraca wykonywanie akcji przy Stop, wyjściu z ekranu lub zniszczeniu ViewModelu. Compose renderuje stan, obsługuje Activity Result API dla uprawnień/eksportu i zapewnia nawigację. Ekrany szczegółowe są oddzielnymi composables.

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

Nową akcję należy dodać do `MediaAction`, adaptera gateway i UI pickera. Akcje specyficzne dla aplikacji powinny dostać osobny gateway zamiast warunków w `GestureEngine`. Nowy gest należy dodać do `GestureType` i jako niezależny detector korzystający z `FilteredSensorData`; mapowania i profile obsłużą go po migracji persistence.
