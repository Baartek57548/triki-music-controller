# Protokół Żabka Triki

## Metoda i poziom pewności

Poniższe dane pochodzą z porównania [Maku-hub/TrikiScope](https://github.com/Maku-hub/TrikiScope), [koksny/TRIKI-Control](https://github.com/koksny/TRIKI-Control) oraz pomiarów sprzętowych opisanych w [matiaspalmac/everything-imu](https://github.com/matiaspalmac/everything-imu/blob/main/DEVICES.md). Źródła pokazują co najmniej dwa warianty firmware, dlatego parser przyjmuje wspólny, potwierdzony podzbiór zamiast uznawać jedną obserwację za jedyną wersję protokołu.

Poziomy używane w tym dokumencie:

- **potwierdzone live** — opisane jako wynik eksperymentu na urządzeniu;
- **potwierdzone implementacją** — występuje w działającej implementacji referencyjnej i jej testach;
- **standard opcjonalny** — standard Bluetooth SIG, odczytywany tylko wtedy, gdy urządzenie go udostępnia;
- **obserwacja** — wynik capture, nie gwarancja firmware/protokołu.

## GATT

| Element | UUID | Właściwości | Kierunek | Pewność |
|---|---|---|---|---|
| Nordic UART Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | service | — | potwierdzone implementacją/live |
| NUS RX | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | write / write without response | telefon → Triki | potwierdzone implementacją |
| NUS TX | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | notify | Triki → telefon | potwierdzone implementacją |
| LED control | `6e400004-b5a3-f393-e0a9-e50e24dcca9e` | read / write | dwukierunkowo | bit 0 potwierdzony live |
| Battery Level | `00002a19-0000-1000-8000-00805f9b34fb` | read, opcjonalnie notify | Triki → telefon | standard opcjonalny |
| Manufacturer Name | `00002a29-0000-1000-8000-00805f9b34fb` | read | Triki → telefon | standard opcjonalny |
| Model Number | `00002a24-0000-1000-8000-00805f9b34fb` | read | Triki → telefon | standard opcjonalny |
| Serial Number | `00002a25-0000-1000-8000-00805f9b34fb` | read | Triki → telefon | standard opcjonalny |
| Firmware Revision | `00002a26-0000-1000-8000-00805f9b34fb` | read | Triki → telefon | standard opcjonalny |
| Hardware Revision | `00002a27-0000-1000-8000-00805f9b34fb` | read | Triki → telefon | standard opcjonalny |
| Software Revision | `00002a28-0000-1000-8000-00805f9b34fb` | read | Triki → telefon | standard opcjonalny |

Pełny zestaw usług nie jest zakodowany na sztywno. `TrikiBleManager` zapisuje wynik `discoverServices()`, properties i deskryptory, dzięki czemu warianty firmware są widoczne w BLE Inspectorze.

## Uruchomienie strumienia

Po subskrypcji CCCD charakterystyki NUS TX telefon zapisuje do NUS RX osiem bajtów:

```text
20 10 00 D0 07 34 00 03
```

Wartość `0x34` uruchamia strumień około 52–53 Hz i jest używana przez implementacje pracujące z sekwencyjnym identyfikatorem pakietu. TrikiScope obserwował również `0x68` i strumień około 104 Hz. Znaczenie pól komendy nie zostało wiarygodnie ustalone; aplikacja wybiera stabilniejszy wariant 52 Hz, który daje pełne ramki bez utraty identyfikatorów.

## Ramka IMU

Stała długość: **14 bajtów**.

| Offset | Długość | Typ | Pole | Skala | Jednostka |
|---:|---:|---|---|---:|---|
| 0 | 1 | `uint8` | nagłówek | dokładnie `0x22` | — |
| 1 | 1 | `uint8` | identyfikator/status | obserwowane `0..15`; starszy wariant `0/1` | — |
| 2 | 2 | `int16 LE` | gyro X | × 0,070 | °/s |
| 4 | 2 | `int16 LE` | gyro Y | × 0,070 | °/s |
| 6 | 2 | `int16 LE` | gyro Z | × 0,070 | °/s |
| 8 | 2 | `int16 LE` | accel X | ÷ 2048 | g |
| 10 | 2 | `int16 LE` | accel Y | ÷ 2048 | g |
| 12 | 2 | `int16 LE` | accel Z | ÷ 2048 | g |

Akcelerometr pracuje w skali `2048 LSB/g`. Niezależna walidacja sprzętowa obrotami o znany kąt potwierdziła dla żyroskopu zakres ±2000 dps i `70 mdps/LSB`; wcześniejsze narzędzie TrikiScope używało skali `131 LSB/(°/s)`, która dla tego wariantu zaniża ruch około 9,17 raza. Aplikacja stosuje skalę potwierdzoną przez całkowanie rzeczywistych obrotów.

Sprzętowo potwierdzony zapis spoczynku na płaskim podłożu, górą do góry, wynosi około `(24, 0, -2050)` jednostek akcelerometru. Bezpieczna pozycja odpowiada więc grawitacji na ujemnej osi Z; dodatnia oś Z oznacza kapsel odwrócony o 180°. Konwencję dokumentuje zestaw testów [TRIKI-Control](https://github.com/koksny/TRIKI-Control/blob/main/tests/test_triki_motion_engine.py).

Parser akceptuje nagłówki `22 00` … `22 0F`. To obejmuje firmware z licznikiem pakietów `0..15` i starszy wariant raportujący tylko `0/1`. Po utracie synchronizacji wyszukuje kolejną prawidłową parę, zachowując końcowe `0x22`, jeżeli drugi bajt przyjdzie w następnej notyfikacji. Jedna notyfikacja może zawierać część ramki albo kilka ramek.

## Próbkowanie i czas

Komenda `0x34` daje natywną częstotliwość około 52–53 Hz. Nie ma potwierdzonego pola protokołu gwarantującego dokładną częstotliwość, a notyfikacje mogą grupować kilka ramek.

Aplikacja:

1. znakuje notyfikację czasem monotonicznym telefonu;
2. dla kilku ramek w jednym burście rozkłada timestampy w przybliżeniu co 19,23 ms, aby filtr orientacji nie dostał `dt = 0`;
3. pokazuje w UI częstotliwość wyliczoną z przesuwnego okna dwóch sekund;
4. nie prezentuje 52 Hz jako wartości gwarantowanej.

## Przycisk i LED

- Dwa publiczne, sprzętowo testowane warianty nie są zgodne semantycznie: TrikiScope obserwuje `22 00` jako puszczenie i `22 01` jako wciśnięcie, natomiast everything-imu obserwuje licznik sekwencji `0..15`. Decoder zachowuje więc neutralną nazwę `status` i akceptuje cały zakres.
- `TrikiButtonInterpreter` nie generuje zdarzeń w trybie `UNKNOWN`. Wartość `2..15` natychmiast potwierdza `SEQUENCE_COUNTER`. Tryb `BUTTON_FLAG` wymaga co najmniej 12 obserwacji `0/1` i serii czterech identycznych wartości; naprzemienny licznik `0/1` nigdy nie spełnia tego warunku.
- Po potwierdzeniu flagi zbocza przechodzą debounce 18 ms. Liczone są tylko pełne naciśnięcia 25 ms–2 s. Okno 450 ms rozróżnia jeden, dwa i trzy kliki; trzeci kończy sekwencję od razu. Niespodziewane `2..15`, luka strumienia ponad 300 ms lub reset połączenia kasują sekwencję bez akcji.
- Podczas sekwencji przycisku regulator osi Z jest resetowany, aby mechaniczny ruch kapsla przy naciskaniu nie zmienił równolegle głośności. Po zwykłym kliknięciu 2-sekundowa stabilizacja zakresu 0–25° zaczyna się od nowa.
- Przytrzymanie przez co najmniej 500 ms otwiera krótkie okno detekcji ruchu pionowego. Rozpoznane +20 cm/−20 cm konsumuje bieżące przytrzymanie, więc puszczenie nie jest liczone jako dodatkowy klik; luka strumienia lub utrata flagi przycisku zeruje estymator.
- Bit 0 charakterystyki `6e400004-…` steruje LED: `00` wyłącza, `01` włącza. Pozostałe bity nie mają potwierdzonego znaczenia i aplikacja ich nie zapisuje.

## Bateria i informacje o urządzeniu

Po discovery aplikacja odczytuje wyłącznie dostępne standardowe charakterystyki Battery Service i Device Information. Brak charakterystyki, brak property `READ` albo błąd odczytu nie przerywa strumienia IMU. Jeśli Battery Level ma `NOTIFY`, aplikacja opcjonalnie włącza CCCD po uruchomieniu NUS.

## Diagnostyka nieznanego firmware

BLE Inspector pokazuje:

- wszystkie znalezione service/characteristic UUID;
- properties `READ`, `WRITE`, `WRITE_NO_RESPONSE`, `NOTIFY`, `INDICATE`;
- deskryptory i wartości odczytanych metadanych;
- każdą notyfikację wraz z characteristic UUID, timestampem, HEX i DEC;
- eksport ograniczonej sesji do pliku tekstowego.

Inspektor BLE pozostaje właściwym narzędziem do diagnozy nieznanej ramki lub wariantu firmware. Nieznany pakiet pozostaje RAW; decoder nie dopasowuje „podobnych” ramek ani nie zgaduje skali.
