# Gesture Engine

## Założenia

Gesture engine otrzymuje dane już zdekodowane i skalibrowane. Nie zna Bluetooth, Compose ani MediaSession. Jego wynik to zero lub więcej immutable `GestureEvent`, które dopiero `ActionMapper` tłumaczy według aktywnego profilu.

## Pipeline

1. Bias żyroskopu i akcelerometru jest odejmowany zgodnie z profilem kalibracji.
2. Mediana z trzech kolejnych wartości każdej osi odrzuca pojedynczy skok pakietu, zanim zostanie rozciągnięty przez wygładzanie.
3. Składowe gyro mniejsze od `max(2,5°/s, 2,8 × szum kalibracji)` są zerowane jako drgania spoczynkowe.
4. Low-pass filter wygładza każdą oś: `filtered += alpha × (current − filtered)`.
5. Filtr komplementarny łączy tilt z grawitacji z całkowaniem gyro. Korekcja accel działa tylko dla magnitude 0,72–1,28 g, aby dynamiczny ruch nie przechylał sztucznie orientacji.
6. Silnik czeka na co najmniej 280 ms stabilnego spoczynku i zapamiętuje bieżący kąt oraz 12 ostatnich próbek jako lokalną bazę neutralną.
7. Po wykryciu faktycznego ruchu zbierane jest jedno ograniczone okno `spoczynek → ruch → spoczynek`.
8. Reguły fizyczne i lokalny model k-NN analizują całe okno. Wynik musi przejść limit odległości, margines między klasami oraz bramkę właściwą dla danego typu ruchu.
9. Silnik zwraca najwyżej jeden gest, ponownie wymaga stabilnego uzbrojenia, a cooldown chroni przed szybkim powtórzeniem.

## Gesty

| Gest | Warunek bazowy (preset Normal) | Stabilizacja |
|---|---|---|
| Tilt left/right | zmiana `roll` względem ostatniego spoczynku ≥ 28° | ≥ 40 ms ponad progiem i peak prędkości roll ≥ 28°/s |
| Rotate left/right | peak `abs(gyro Z) ≥ 220°/s` | całka obrotu Z ≥ 22° w jednym oknie ruchu |
| Throw up | free-fall poniżej 0,38 g przez ≥ 35 ms | wymagany późniejszy impact ≥ 2,4 g; sam impuls nie wystarcza |
| Flip | `accel Z < -0,72 g`, magnitude 0,65–1,4 g | ≥ 80 ms do góry nogami, ruch gyro i stabilny koniec z ujemnym Z |
| Shake | gyro magnitude ≥ 285°/s i odchylenie accel magnitude ≥ 0,16 g | wymagana rzeczywista zmiana kierunku wektora gyro |
| Double shake | dwa pełne cykle zmiany kierunku w jednym oknie ruchu | zwraca tylko `DOUBLE_SHAKE` |

Progi nie opisują „prawdy sprzętowej”; są jawną polityką UX i można je stroić. Wartości bazowe zaczynają od zakresów wykorzystanych w publicznych doświadczeniach TrikiScope, ale mechanizm aplikacji jest własną maszyną stanów i ma testy przeciw spamowi.

## Czułość

- **Low** — wyższe progi, mocniejsze wygładzanie, cooldown 850 ms.
- **Normal** — balans do codziennego sterowania, cooldown 650 ms.
- **High** — niższe progi i szybszy filtr, cooldown 520 ms.
- **Very High** — dla delikatnych ruchów; najwyższe ryzyko false-positive, cooldown 420 ms.
- **Advanced** — użytkownik ustawia tilt, rotate, shake i impact; walidacja domenowa ogranicza wartości do bezpiecznych zakresów.

## Kalibracja

Kreator zbiera trzy sekundy próbek. Wymagane jest co najmniej 50 ramek. Średni wektor przyspieszenia jest normalizowany do 1 g, dzięki czemu bias nie usuwa grawitacji. Średnia gyro staje się biasem osi. RMS magnitude określa poziom szumu.

Do czasu zapisania poprawnej kalibracji `TrikiRuntime` aktualizuje monitor i diagnostykę, ale nie uruchamia żadnej akcji multimedialnej. Chroni to przed interpretacją fizycznego kąta leżącego urządzenia albo stałego biasu jako gestu.

Kalibracja jest odrzucana, jeśli:

- magnitude średniej grawitacji nie mieści się w 0,75–1,25 g;
- RMS żyroskopu przekracza 25°/s;
- dotarło mniej niż 50 próbek.

## Nagrywanie Start/Stop

Ekran **Naucz gest** rejestruje dokładny przedział wybrany przez użytkownika. Start dodaje krótki pre-roll z historii, Stop kończy okno i uruchamia ten sam klasyfikator co tryb live. Podczas nagrania wykonywanie akcji multimedialnych jest zawieszone, ale filtrowane próbki nadal trafiają na wykres i do analizatora.

Ten sam mechanizm zasila kreator pierwszej konfiguracji. Użytkownik otrzymuje instrukcję właściwą dla każdego z ośmiu gestów, może dodać próbkę nawet wtedy, gdy klasyczny detektor wskazał inną klasę, powtórzyć ruch z innej pozycji, pominąć go oraz przypisać inną akcję lub `Brak akcji`.

Każde zaakceptowane nagranie jest przycinane do aktywnego ruchu i zamieniane na 39 cech z akcelerometru i żyroskopu. Dwie próbki aktywują personalizację gestu, a maksymalnie pięć ostatnich pozwala pokryć kilka typowych pozycji kapsla. Surowy przebieg nie jest utrwalany. Pojedyncza próbka może tylko potwierdzić regułę bazową; nadpisanie klasy wymaga dojrzałego modelu, wysokiej pewności i fizycznej zgodności ruchu.

Nagranie jest ograniczone do 15 sekund i 2000 próbek. Wynik pokazuje wykryty gest, pewność, peak gyro oraz zakres magnitude akcelerometru. Akceptacja potwierdza jakość nagrania; nie obniża automatycznie progów bezpieczeństwa.

## Kompromisy

- Mocniejsze smoothing ogranicza jitter, ale zwiększa opóźnienie.
- Dłuższy cooldown eliminuje przypadkowe podwójne akcje, ale ogranicza tempo świadomych powtórzeń.
- Oczekiwanie na końcowy spoczynek dodaje około 280 ms opóźnienia, ale eliminuje akcje od statycznego kąta i ogranicza jeden ruch do jednego zdarzenia.
- Yaw jest gyro-only, więc długoterminowo dryfuje. Cechy obrotu wykorzystują całkę rzutu gyro na chwilowy wektor grawitacji, a nie absolutny yaw.
- Akcelerometr wyznacza pion, ale bez magnetometru nie wyznacza kierunku poziomego. Personalizacja może filtrować `tilt left/right`, lecz nie wolno jej samodzielnie odwracać tego kierunku po dowolnej zmianie yaw.
- Throw bazujący na free-fall jest bezpieczniejszy niż integracja przyspieszenia do pozycji, która szybko akumuluje błąd bez dodatkowych sensorów.

## Testowanie

`GestureEngineTest` podaje sztuczne `FilteredSensorData` z kontrolowanym czasem. Testy obejmują 1000 próbek nieruchomego urządzenia pod kątem, 2000 zaszumionych próbek spoczynkowych, pojedynczy uszkodzony sample, stały błąd gyro, pełne cykle wszystkich gestów, personalizowany flip z pozycji bocznej oraz analizę ręcznego nagrania. `SensorFilterAndCalibrationTest` pokrywa medianę, wygładzanie, bias oraz walidację stabilności. `PersonalizedGestureClassifierTest` weryfikuje wymiar cech, jakość obu sensorów, odporność grawitacyjną na obrót, uczenie k-NN i fizyczne odrzucanie niezgodnej próbki.

W buildzie debug `FakeTrikiDataSource` generuje pełne sekwencje IMU i przechodzi przez ten sam `TrikiRuntime`, `GestureEngine` i `ActionMapper` co fizyczny kontroler.
