# Protokół Żabka Triki

## Metoda i poziom pewności

Poniższe dane pochodzą z analizy publicznego projektu [Maku-hub/TrikiScope](https://github.com/Maku-hub/TrikiScope) w rewizji `8ad37643148892ca7747e1520f7327a9eb8a8239` (2026-06-18). TrikiScope opisuje obserwacje na rzeczywistym urządzeniu oraz testy parsera. W aplikacji wartości te są odseparowane w `TrikiProtocol`; informacje niepotwierdzone trafiają wyłącznie do inspectora RAW.

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
20 10 00 D0 07 68 00 03
```

Znaczenie poszczególnych pól komendy nie zostało wiarygodnie ustalone. Aplikacja nie nadaje im nazw i nie generuje alternatywnych wartości.

## Ramka IMU

Stała długość: **14 bajtów**.

| Offset | Długość | Typ | Pole | Skala | Jednostka |
|---:|---:|---|---|---:|---|
| 0 | 1 | `uint8` | nagłówek | dokładnie `0x22` | — |
| 1 | 1 | `uint8` | status | bit 0: `0` puszczony, `1` wciśnięty | — |
| 2 | 2 | `int16 LE` | gyro X | ÷ 131 | °/s |
| 4 | 2 | `int16 LE` | gyro Y | ÷ 131 | °/s |
| 6 | 2 | `int16 LE` | gyro Z | ÷ 131 | °/s |
| 8 | 2 | `int16 LE` | accel X | ÷ 2048 | g |
| 10 | 2 | `int16 LE` | accel Y | ÷ 2048 | g |
| 12 | 2 | `int16 LE` | accel Z | ÷ 2048 | g |

Format odpowiada konfiguracji LSM6DSL: żyroskop ±250 dps (`131 LSB/(°/s)`) i akcelerometr ±16 g (`2048 LSB/g`). Drugi bajt jest statusem, nie częścią osi gyro X.

Parser akceptuje wyłącznie nagłówki `22 00` oraz `22 01`. Po utracie synchronizacji wyszukuje kolejną prawidłową parę, zachowując końcowe `0x22`, jeżeli drugi bajt przyjdzie w następnej notyfikacji. Jedna notyfikacja może zawierać część ramki albo kilka ramek.

## Próbkowanie i czas

Capture opisany przez TrikiScope wskazuje transmisję burstami oraz efektywną częstotliwość w okolicy 98–104 Hz. Nie ma potwierdzonego pola protokołu gwarantującego dokładną częstotliwość.

Aplikacja:

1. znakuje notyfikację czasem monotonicznym telefonu;
2. dla kilku ramek w jednym burście rozkłada timestampy w przybliżeniu co 9,615 ms, aby filtr orientacji nie dostał `dt = 0`;
3. pokazuje w UI częstotliwość wyliczoną z przesuwnego okna dwóch sekund;
4. nie prezentuje 104 Hz jako wartości gwarantowanej.

## Przycisk i LED

- `status & 0x01 != 0` oznacza wciśnięty fizyczny przycisk. Payload IMU zachowuje układ osi.
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

Nieznany pakiet pozostaje RAW. Decoder nie dopasowuje „podobnych” ramek ani nie zgaduje skali.
