# Gesture Engine

## Założenia

Gesture engine otrzymuje dane już zdekodowane i skalibrowane. Nie zna Bluetooth, Compose ani MediaSession. Jego wynik to zero lub więcej immutable `GestureEvent`, które dopiero `ActionMapper` tłumaczy według aktywnego profilu.

## Pipeline

1. Bias żyroskopu i akcelerometru jest odejmowany zgodnie z profilem kalibracji.
2. Mediana z trzech kolejnych wartości każdej osi odrzuca pojedynczy skok pakietu, zanim zostanie rozciągnięty przez wygładzanie. Surowy, skalibrowany accel pozostaje równoległym wejściem tylko dla krótkiego impulsu, rozpoczęcia ruchu i jego cech dynamicznych, dzięki czemu prawdziwe pojedyncze stuknięcie nie znika z treningu.
3. Składowe gyro mniejsze od `max(2,5°/s, 2,8 × szum kalibracji)` są zerowane jako drgania spoczynkowe.
4. Low-pass filter wygładza każdą oś: `filtered += alpha × (current − filtered)`.
5. Filtr komplementarny łączy tilt z grawitacji z całkowaniem gyro. Korekcja accel działa tylko dla magnitude 0,72–1,28 g, aby dynamiczny ruch nie przechylał sztucznie orientacji.
6. Silnik czeka na co najmniej 280 ms stabilnego spoczynku i zamraża lokalny wektor grawitacji z 12 ostatnich próbek. Segmentacja używa zmiany kierunku całego wektora grawitacji, a nie jednej osi roll, więc działa także dla spoczynku na `−Z` i na boku.
7. Po wykryciu faktycznego ruchu zbierane jest jedno ograniczone okno `spoczynek → ruch → spoczynek`.
8. Reguły fizyczne i lokalny model k-NN analizują całe okno. Wynik musi przejść limit odległości, margines między klasami oraz bramkę właściwą dla danego typu ruchu.
9. Silnik zwraca najwyżej jeden gest, ponownie wymaga stabilnego uzbrojenia, a cooldown chroni przed szybkim powtórzeniem. Po `Flip` ruch powrotny do poprzedniej strony jest konsumowany bez akcji; następne sterowanie działa dopiero po stabilnym powrocie.

## Gesty

| Gest | Warunek bazowy (preset Normal) | Stabilizacja |
|---|---|---|
| Lean | kąt między początkową i bieżącą grawitacją ≥ 12° | ≥ 40 ms ponad progiem i peak żyroskopu ≥ 28°/s |
| Slide | pozioma składowa accel ≥ 0,14 g | mała zmiana grawitacji, gyro ≤ 80°/s i mały impuls pionowy |
| Rotate left/right | rzut gyro na lokalny wektor grawitacji ≥ 42°/s | całka rzutu ≥ 7° i dominacja osi obrotu ≥ 48% |
| Tap | surowy impuls accel ≥ 1,24 g wzdłuż grawitacji | gyro ≤ 95°/s; alternatywnie free-fall ≥ 35 ms zakończony uderzeniem |
| Flip | projekcja accel na początkową grawitację < −0,72 | ≥ 80 ms do góry nogami, ruch gyro i stabilny odwrócony koniec |
| Shake | gyro magnitude ≥ 260°/s i odchylenie accel magnitude ≥ 0,16 g | wymagana rzeczywista zmiana kierunku wektora gyro |
| Double shake | dwa pełne cykle zmiany kierunku w jednym oknie ruchu | zwraca tylko `DOUBLE_SHAKE` |

Progi nie opisują „prawdy sprzętowej”; są jawną polityką UX i można je stroić. Wartości bazowe zaczynają od zakresów wykorzystanych w publicznych doświadczeniach TrikiScope, ale mechanizm aplikacji jest własną maszyną stanów i ma testy przeciw spamowi.

## Czułość

- **Low** — wyższe progi, mocniejsze wygładzanie, cooldown 850 ms.
- **Normal** — balans do codziennego sterowania, cooldown 650 ms.
- **High** — niższe progi i szybszy filtr, cooldown 520 ms.
- **Very High** — dla delikatnych ruchów; najwyższe ryzyko false-positive, cooldown 420 ms.
- **Advanced** — użytkownik ustawia tilt, rotate, shake i impact; walidacja domenowa ogranicza wartości do bezpiecznych zakresów.

## Kalibracja

Kreator kalibracji zbiera trzy sekundy próbek. Wymagane jest co najmniej 50 ramek. Średni wektor przyspieszenia jest normalizowany do 1 g, dzięki czemu bias nie usuwa grawitacji. Średnia gyro staje się biasem osi. RMS magnitude określa poziom szumu.

Kalibracja poprawia martwą strefę i dokładność, ale nie blokuje sterowania. Bez zapisanego profilu silnik sam uzbraja się z bieżącego stabilnego spoczynku i używa zerowego biasu. Dzięki temu samouczek oraz podstawowe gesty działają również przed kalibracją; wymaganie cyklu `spoczynek → ruch → spoczynek` nadal chroni przed stałym biasem i nieruchomym kapslem.

Kalibracja jest odrzucana, jeśli:

- magnitude średniej grawitacji nie mieści się w 0,75–1,25 g;
- RMS żyroskopu przekracza 25°/s;
- dotarło mniej niż 50 próbek.

## Nagrywanie Start/Stop

Ekran **Naucz gest** rejestruje dokładny przedział wybrany przez użytkownika. Start dodaje krótki pre-roll z historii, Stop kończy okno i uruchamia ten sam klasyfikator co tryb live. Podczas nagrania wykonywanie akcji multimedialnych jest zawieszone, ale filtrowane próbki nadal trafiają na wykres i do analizatora.

Ten sam mechanizm zasila kreator pierwszej konfiguracji. Użytkownik otrzymuje instrukcję właściwą dla każdego z ośmiu gestów, może dodać próbkę nawet wtedy, gdy klasyczny detektor wskazał inną klasę, powtórzyć ruch z innej pozycji, pominąć go oraz przypisać inną akcję lub `Brak akcji`.

Każde zaakceptowane nagranie jest przycinane do aktywnego ruchu i zamieniane na 40 cech z akcelerometru i żyroskopu, w tym kąt grawitacji, poziome przyspieszenie, całkę obrotu wokół grawitacji i przebieg czasowy. Dwie próbki aktywują personalizację gestu, a maksymalnie pięć ostatnich pozwala pokryć kilka typowych pozycji kapsla. Surowy przebieg nie jest utrwalany. Model może dodać lub wzmocnić rozpoznanie, ale nie może zablokować prawidłowego wyniku reguły fizycznej.

Nagranie jest ograniczone do 15 sekund i 2000 próbek. Wynik pokazuje wykryty gest, pewność, peak gyro oraz zakres magnitude akcelerometru. Akceptacja potwierdza jakość nagrania; nie obniża automatycznie progów bezpieczeństwa.

## Kompromisy

- Mocniejsze smoothing ogranicza jitter, ale zwiększa opóźnienie.
- Dłuższy cooldown eliminuje przypadkowe podwójne akcje, ale ogranicza tempo świadomych powtórzeń.
- Oczekiwanie na końcowy spoczynek dodaje około 280 ms opóźnienia, ale eliminuje akcje od statycznego kąta i ogranicza jeden ruch do jednego zdarzenia.
- Yaw jest gyro-only, więc długoterminowo dryfuje. Cechy obrotu wykorzystują całkę rzutu gyro na chwilowy wektor grawitacji, a nie absolutny yaw.
- Akcelerometr wyznacza pion, ale bez magnetometru nie wyznacza absolutnego kierunku poziomego. Dlatego niezawodne sterowanie używa kierunku obrotu wokół grawitacji, bezkierunkowego przechylenia oraz płaskiego przesunięcia zamiast „tilt lewo/prawo”.
- Krótkie stuknięcie jest bezpieczniejsze dla małego urządzenia niż wymagane podrzucenie; free-fall z późniejszym uderzeniem pozostaje obsługiwanym wariantem tego samego gestu.

## Testowanie

`GestureEngineTest` podaje sztuczne `FilteredSensorData` z kontrolowanym czasem. Testy obejmują 1000 próbek nieruchomego urządzenia pod kątem, 2000 zaszumionych próbek spoczynkowych, pojedynczy uszkodzony sample, stały błąd gyro, płaskie przesunięcie, pełne cykle wszystkich gestów, obrót i flip z pozycji bocznej oraz analizę ręcznego nagrania. `SensorFilterAndCalibrationTest` pokrywa medianę, wygładzanie, bias, walidację stabilności i przejście orientacji przez granicę ±180°. `PersonalizedGestureClassifierTest` weryfikuje wymiar cech, użycie obu sensorów, niezmienność po obrocie kapsla, uczenie k-NN i fizyczne odrzucanie niezgodnej próbki.

`ReferenceMotionCompatibilityTest` zaczyna od potwierdzonego spoczynku `(24, 0, −2051)`, skali `0,070°/s/LSB`, `2048 LSB/g` i okresu 19,23 ms. Odtwarza profile ruchu z publicznych testów TRIKI-Control przez produkcyjny `SensorFilter` i `GestureEngine`: oba kierunki krótkiego skrętu, łagodne przechylenie 14°, płaski ślizg, impuls `−2600` oraz odwrócenie. Sprawdza też ciszę zaszumionego `−Z`, niezmienność skrętu w czterech pozycjach kapsla oraz możliwość nauczenia modelu wszystkich sześciu podstawowych gestów.

W buildzie debug `FakeTrikiDataSource` generuje pełne sekwencje IMU i przechodzi przez ten sam `TrikiRuntime`, `GestureEngine` i `ActionMapper` co fizyczny kontroler.
