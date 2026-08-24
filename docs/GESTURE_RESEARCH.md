# Analiza rozpoznawania gestów Triki

## Wynik analizy

Triki udostępnia pełne IMU 6D: trzy osie akcelerometru i trzy osie żyroskopu, ale nie ma magnetometru. Niezawodne gesty muszą więc łączyć oba sensory i opierać się na wielkościach niezależnych od obrotu kapsla w dłoni: kierunku grawitacji, ruchu względem grawitacji, rzucie prędkości kątowej na grawitację oraz przebiegu całego ruchu. Sam akcelerometr nie rozróżni pewnie skrętu od wstrząsu, a sam żyroskop nie poda stabilnej pozycji ani pionu.

Najważniejszą ochroną przed samoczynną zmianą utworów nie jest pojedynczy wyższy próg. Jest nią maszyna stanów `spoczynek → ruch → spoczynek`, wymaganie kilku zgodnych próbek, bramki wykorzystujące oba sensory, najwyżej jedna akcja na ruch oraz ponowne uzbrojenie po uspokojeniu kapsla.

## Przeanalizowane implementacje

Analizę wykonano na przypiętych rewizjach, aby późniejsze zmiany repozytoriów nie zmieniły podstaw wniosków.

| Projekt | Rewizja | Co wnosi | Ograniczenie |
|---|---|---|---|
| [everything-imu](https://github.com/matiaspalmac/everything-imu/tree/b7b2e825514af398c5dde63cf3d089f4af85c99e/crates/device-hopx) | `b7b2e82` | Najmocniejsze potwierdzenie protokołu na fizycznym sprzęcie: 52 Hz, kolejność gyro/accel, `2048 LSB/g`, `0,070°/s/LSB`, brak magnetometru | Dostarcza poprawny strumień IMU i orientację, ale nie rozwiązuje mapowania gestów muzycznych |
| [TRIKI-Control](https://github.com/koksny/TRIKI-Control/tree/8d1bdff17a419930f7a89c5244928b199d4e6ee3) | `8d1bdff` | Silnik dostrojony na rzeczywistych nagraniach; niezmienniki względem grawitacji, bootstrap medianowy, histereza, osobne bramki tilt/turn/slide/impact/flip | Progi są w surowych jednostkach i profil jest projektowany głównie pod komputer, więc nie można kopiować go 1:1 do Androida |
| [TrikiScope](https://github.com/Maku-hub/TrikiScope/tree/8ad37643148892ca7747e1520f7327a9eb8a8239) | `8ad3764` | Przydatne wzorce dla free-fall, impact, shake, spoczynku i auto-zero | Używa innego założenia skali gyro; proste progi magnitude nie wystarczają do odpornych gestów muzycznych |
| [TrikiAirMouse](https://github.com/kub0vvik/TrikiAirMouse/tree/321a08f837e242ec03077c984fab2708b3917472) | `321a08f` | Praktyczne bias, deadzone, low-pass i ograniczanie maksymalnego skoku | Sterowanie kursorem zależy od kalibracji osi urządzenia; nie jest odporne na dowolną pozycję okrągłego kapsla |
| [TrikiEmu](https://github.com/Maku-hub/TrikiEmu/tree/2a55a15874039b58085780e7185315d5f4362efb) | `2a55a15` | Emulacja urządzenia i możliwość testowania transportu bez fizycznego Triki | Nie jest źródłem modelu rozpoznawania rzeczywistych ruchów |

Szczególnie istotne są udokumentowane ograniczenia 6‑osiowego kapsla w [TRIKI-Control](https://github.com/koksny/TRIKI-Control/blob/8d1bdff17a419930f7a89c5244928b199d4e6ee3/docs/how-it-works.md): grawitacja stabilizuje pitch/roll, lecz nie daje absolutnego kierunku poziomego. Projekt ten dlatego liczy skręt jako `dot(gyro − bias, unit(gravity))`, przechylenie jako zmianę kierunku grawitacji, stuknięcie jako impuls wzdłuż grawitacji, a ślizg jako ruch poprzeczny przy małym obrocie. Jego [aktualny silnik](https://github.com/koksny/TRIKI-Control/blob/8d1bdff17a419930f7a89c5244928b199d4e6ee3/src/triki_motion_engine.py) porzucił też rozróżnianie ślizgu po okręgu od linii, ponieważ okrągły kapsel bez kompasu nie daje do tego stabilnej obserwacji.

## Prawda sprzętowa

[Walidacja everything-imu](https://github.com/matiaspalmac/everything-imu/blob/b7b2e825514af398c5dde63cf3d089f4af85c99e/DEVICES.md#hopx--triki) oraz [parser urządzenia](https://github.com/matiaspalmac/everything-imu/blob/b7b2e825514af398c5dde63cf3d089f4af85c99e/crates/device-hopx/src/protocol.rs) prowadzą do następującego kontraktu wejściowego:

- ramka ma 14 bajtów: `0x22`, identyfikator `0..15`, trzy `int16 LE` gyro i trzy `int16 LE` accel;
- komenda `20 10 00 D0 07 34 00 03` uruchamia strumień około 52 Hz;
- żyroskop pracuje w zakresie ±2000 dps i ma skalę `0,070°/s/LSB`; została ona sprawdzona całkowaniem trzech obrotów o około 90°;
- akcelerometr ma skalę `2048 LSB/g`;
- urządzenie nie ma magnetometru, więc absolutny yaw jest nieobserwowalny i dryfuje.

To wyjaśnia dwie krytyczne klasy wcześniejszych błędów. Odrzucanie ramek z identyfikatorem większym niż `1` usuwało większość prawidłowych danych, a skala `131 LSB/(°/s)` zaniżała ruch żyroskopu około 9,17 raza. Nawet poprawny klasyfikator nie może działać na takim wejściu.

## Podział odpowiedzialności sensorów

| Sygnał | Akcelerometr | Żyroskop | Wspólna decyzja |
|---|---|---|---|
| Spoczynek | magnitude blisko 1 g i stabilny kierunek grawitacji | mała prędkość kątowa po odjęciu biasu | kapsel może zostać uzbrojony lub zakończyć okno ruchu |
| Przechylenie | trwała zmiana kąta lokalnej grawitacji | potwierdzenie, że nastąpił ruch, a nie tylko błędna baza | bezkierunkowy `Lean`, odporny na początkowy obrót kapsla |
| Skręt lewo/prawo | chwilowa oś pionu | znak i całka `gyro · gravity` | `RotateLeft` albo `RotateRight` bez użycia dryfującego yaw |
| Płaski ślizg | przyspieszenie prostopadłe do grawitacji, mały impuls pionowy | niski obrót | `Slide`, a nie przechylenie, stuknięcie lub przypadkowe podniesienie |
| Stuknięcie | krótki impuls wzdłuż grawitacji albo free-fall zakończony uderzeniem | niski obrót | `Tap`, odrzucony podczas gwałtownego obracania |
| Odwrócenie | znak projekcji grawitacji zmieniony i utrzymany | rzeczywisty ruch prowadzący do zmiany pozycji | `Flip`, a nie chwilowy skok lub nieruchoma zła kalibracja |

Filtr medianowy usuwa pojedyncze skoki, adaptacyjna martwa strefa tłumi bias gyro, low-pass ogranicza szum, a filtr komplementarny łączy krótkoterminową dynamikę żyroskopu ze stabilnym pionem akcelerometru. Korekcja grawitacją jest wyłączana przy silnym przyspieszeniu liniowym, aby uderzenie nie zostało błędnie uznane za zmianę orientacji.

## Dlaczego uczenie maszynowe jest warstwą pomocniczą

[uWave](https://www.yecl.org/project_uwave.html) pokazał, że personalizowane rozpoznawanie z niewielu przykładów i Dynamic Time Warping może działać na urządzeniach o małych zasobach. Jego wynik 98,6% dotyczy jednak własnego zbioru gestów akcelerometru, użytkowników i procedury badawczej — nie jest wynikiem dla Triki i nie rozwiązuje braku magnetometru.

W aplikacji zastosowano lokalny model few-shot k-NN na 40 cechach całego okna, obejmujących oba sensory. To profesjonalniejszy wybór na obecnym etapie niż sieć neuronowa:

- 2–5 prób na gest to za mało do bezpiecznego trenowania głębokiego modelu;
- obliczenia i dane pozostają na telefonie;
- cechy oparte na grawitacji ograniczają wpływ pozycji kapsla;
- wynik można wyjaśnić odległością od przykładów i marginesem między klasami;
- model nie może ominąć fizycznej bramki ani wygenerować akcji z nieruchomego urządzenia.

ML personalizuje tempo i kształt ruchu, ale nie tworzy informacji, której sensor nie mierzy. Nie wolno więc uczyć osobnych gestów „przechyl w lewo/prawo” zależnych od absolutnego kierunku kapsla. Kierunek jest wiarygodny dla skrętu, bo pochodzi ze znaku chwilowej prędkości kątowej rzutowanej na grawitację.

## Mapowanie muzyczne

Domyślny profil jest zgodny z sygnałami, które mają najlepszą separację fizyczną. Inspiracją dla zestawu jest [profil Music z TRIKI-Control](https://github.com/koksny/TRIKI-Control/blob/8d1bdff17a419930f7a89c5244928b199d4e6ee3/src/triki_actions.py#L573-L583), ale `Flip` celowo wykonuje `Stop` zgodnie z wymaganiem tej aplikacji.

| Ruch | Akcja | Uzasadnienie |
|---|---|---|
| skręt przeciwnie do wskazówek | ciszej | znak `gyro · gravity` daje najbardziej pewny kierunek |
| skręt zgodnie ze wskazówkami | głośniej | symetryczna, łatwa do powtórzenia para |
| wyraźne przechylenie | poprzedni utwór | ruch bezkierunkowy nie zależy od pozycji kapsla |
| płaski ślizg | następny utwór | osobna sygnatura liniowa przy małym obrocie |
| krótkie stuknięcie | play/pause | krótki impuls, który łatwo zakończyć spoczynkiem |
| odwrócenie i utrzymanie | stop | stan wymagający świadomej, trwałej zmiany pozycji |

## Kryteria jakości i dowody

| Ryzyko | Mechanizm | Dowód automatyczny / diagnostyczny |
|---|---|---|
| fałszywa ramka lub rozcięta notyfikacja | walidacja nagłówka i identyfikatora, buforowanie, resynchronizacja | testy dekodera dla podziału, sklejenia, śmieci i `0..15` |
| szum i pojedynczy skok | mediana, low-pass, deadzone i walidacja magnitude | testy filtra ze szumem i uszkodzoną próbką |
| stały kąt lub bias wywołuje akcję | lokalny spoczynek oraz pełny cykl uzbrojenia | testy długiego spoczynku pod kątem i stałego błędu gyro |
| jeden ruch daje wiele komend | jedna klasyfikacja na okno, cooldown, ponowne uzbrojenie | testy pełnych cykli i powtórzeń |
| gest nauczony mimo złej fizyki | bramka accel + gyro po klasyfikacji k-NN | testy odrzucenia niezgodnej próbki |
| pomylenie gestów | cechy niezmienne względem grawitacji i bramki osi energii | testy lean, slide, rotate, tap, flip i shake |
| błąd w mapowaniu lub wysłaniu komendy | wspólna ścieżka runtime dla sprzętu i Fake Triki | ręczny test debug: wszystkie sześć domyślnych mapowań dotarło do gateway; głośność realnie zmieniła się w emulatorze |

Ostatni punkt nie zastępuje walidacji fizycznego egzemplarza. Test akceptacyjny na Xiaomi 13 i konkretnym Triki powinien obejmować:

1. Pięć minut nieruchomego kapsla w co najmniej trzech pozycjach bez żadnej akcji multimedialnej.
2. Po 20 wykonań każdego z sześciu ruchów; cel to co najmniej 18/20 prawidłowych rozpoznań, najwyżej 1/20 pomyłek na inny gest i zero podwójnych akcji.
3. Powtórzenie skrętów, przechylenia i stuknięcia z trzech różnych początkowych obrotów kapsla.
4. W BLE Inspectorze częstotliwość zbliżona do 52 Hz, accel magnitude w spoczynku blisko 1 g oraz widoczna reakcja wszystkich trzech osi gyro podczas obrotu.
5. Eksport RAW dla każdego nieudanego ruchu wraz z nazwą oczekiwanego gestu; dopiero takie dane uzasadniają korektę progów albo cech modelu.

Bez przejścia tego testu nie należy deklarować, że skuteczność na fizycznym kapslu jest potwierdzona. Aktualne testy dowodzą poprawności protokołu, filtrów, klasyfikatora, mapowania i syntetycznej ścieżki end-to-end, lecz ostateczna walidacja sprzętowa pozostaje osobnym etapem.
