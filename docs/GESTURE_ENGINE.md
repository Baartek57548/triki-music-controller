# Gesture Engine

## Założenia

Gesture engine otrzymuje dane już zdekodowane i skalibrowane. Nie zna Bluetooth, Compose ani MediaSession. Jego wynik to zero lub więcej immutable `GestureEvent`, które dopiero `ActionMapper` tłumaczy według aktywnego profilu.

## Pipeline

1. Bias żyroskopu i akcelerometru jest odejmowany zgodnie z profilem kalibracji.
2. Low-pass filter wygładza każdą oś: `filtered += alpha × (current − filtered)`.
3. Filtr komplementarny łączy tilt z grawitacji z całkowaniem gyro. Korekcja accel działa tylko dla magnitude 0,72–1,28 g, aby dynamiczny ruch nie przechylał sztucznie orientacji.
4. Detektory analizują orientation, magnitude i krótkie sekwencje próbek.
5. Histereza i cooldown blokują wielokrotne wyzwolenie jednego ruchu.

## Gesty

| Gest | Warunek bazowy (preset Normal) | Stabilizacja |
|---|---|---|
| Tilt left/right | `abs(roll) ≥ 28°` | latch do powrotu poniżej 12° + cooldown |
| Rotate left/right | `abs(gyro Z) ≥ 220°/s` | minimum 3 kolejne próbki + cooldown |
| Throw up | minimum 2 próbki poniżej 0,38 g albo silny impuls Z | cooldown |
| Flip | `accel Z < -0,72 g`, magnitude 0,65–1,4 g | minimum 8 próbek + cooldown |
| Shake | gyro magnitude ≥ 285°/s i odchylenie accel magnitude ≥ 0,18 g | minimum 4 próbki, opóźniona emisja |
| Double shake | drugi pulse shake w ciągu 480 ms | anuluje pending single-shake |

Progi nie opisują „prawdy sprzętowej”; są jawną polityką UX i można je stroić. Wartości bazowe zaczynają od zakresów wykorzystanych w publicznych doświadczeniach TrikiScope, ale mechanizm aplikacji jest własną maszyną stanów i ma testy przeciw spamowi.

## Czułość

- **Low** — wyższe progi, mocniejsze wygładzanie, cooldown 850 ms.
- **Normal** — balans do codziennego sterowania, cooldown 650 ms.
- **High** — niższe progi i szybszy filtr, cooldown 520 ms.
- **Very High** — dla delikatnych ruchów; najwyższe ryzyko false-positive, cooldown 420 ms.
- **Advanced** — użytkownik ustawia tilt, rotate, shake i impact; walidacja domenowa ogranicza wartości do bezpiecznych zakresów.

## Kalibracja

Kreator zbiera trzy sekundy próbek. Wymagane jest co najmniej 50 ramek. Średni wektor przyspieszenia jest normalizowany do 1 g, dzięki czemu bias nie usuwa grawitacji. Średnia gyro staje się biasem osi. RMS magnitude określa poziom szumu.

Kalibracja jest odrzucana, jeśli:

- magnitude średniej grawitacji nie mieści się w 0,75–1,25 g;
- RMS żyroskopu przekracza 25°/s;
- dotarło mniej niż 50 próbek.

## Kompromisy

- Mocniejsze smoothing ogranicza jitter, ale zwiększa opóźnienie.
- Dłuższy cooldown eliminuje przypadkowe podwójne akcje, ale ogranicza tempo świadomych powtórzeń.
- Rozróżnienie single/double shake wymaga opóźnienia pojedynczego shake do 480 ms. Bez tego ten sam ruch uruchamiałby dwie akcje.
- Yaw jest gyro-only, więc długoterminowo dryfuje. Gesty rotate bazują na chwilowej prędkości Z, a nie absolutnym yaw.
- Throw bazujący na free-fall jest bezpieczniejszy niż integracja przyspieszenia do pozycji, która szybko akumuluje błąd bez dodatkowych sensorów.

## Testowanie

`GestureEngineTest` podaje sztuczne `FilteredSensorData` z kontrolowanym czasem. Testy weryfikują latch/release tilt, sustained rotation, delayed single shake, double-shake bez duplikatu, free-fall/throw i cooldown. `SensorFilterAndCalibrationTest` pokrywa wygładzanie, bias oraz walidację stabilności.

W buildzie debug `FakeTrikiDataSource` generuje pełne sekwencje IMU i przechodzi przez ten sam `TrikiRuntime`, `GestureEngine` i `ActionMapper` co fizyczny kontroler.
