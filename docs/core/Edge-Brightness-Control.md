---
title: Regulacja Jasności Ekranu na Krawędzi 90°
tags:
  - core
  - imu
---

# Regulacja Jasności Ekranu na Krawędzi 90°

Moduł `EdgePoseBrightnessController` umożliwia precyzyjną zmianę jasności ekranu monitora lub wyświetlacza smartfona poprzez obracanie kontrolera postawionego na bocznej krawędzi (kąt 90°).

Powiązane węzły:
- [[Button-Interpreter]] — obsługa i konsumpcja przytrzymania przycisku (`ConsumeCurrentHold`).
- [[ADR-003-Button-Hold-For-Brightness]] — decyzja o wymogu przytrzymania przycisku.
- [[ADR-002-Dual-Platform-Core-Parity]] — spójność parametrów algorytmu między Windows i Android.
- [[Compact-HUD-Windows]] — wizualizacja jasności w Windows HUD.
- [[Release-v3.1.5]] — implementacja histerezy i przyspieszonej stabilizacji.

---

## Reguły Sterowania

1. **Histereza Pozycji Krawędziowej (Kąt 90°)**:
   - **Wejście w tryb krawędziowy**: Akcelerometr osi Z $|a_z| \le 0.45g$, składowa płaszczyzny XY $0.65g \le \sqrt{a_x^2 + a_y^2} \le 1.35g$.
   - **Podtrzymanie trybu (histereza wyjścia)**: Akcelerometr osi Z $|a_z| \le 0.60g$, składowa płaszczyzny XY $\ge 0.52g$ ($0.65g \times 0.8$).
   - Histereza zapobiega przypadkowemu wypadnięciu z trybu jasności przy dynamicznym obracaniu kontrolera dłonią.

2. **Czas Stabilizacji i Pominięcie Przyciskiem (150 ms)**:
   - Domyślny czas oczekiwania na ustabilizowanie pozycji na krawędzi wynosi **150 ms** (`DefaultStabilizationNanos = 150_000_000`).
   - Wciśnięcie fizycznego przycisku natychmiast uaktywnia regulację, pomijając licznik stabilizacji.

3. **Wymóg Przytrzymania Przycisku (`Hold Requirement` - ADR-003)**:
   - Regulacja jasności działa **wyłącznie wtedy, gdy użytkownik trzyma wciśnięty fizyczny przycisk**.
   - Zwolnienie przycisku natychmiast zatrzymuje regulację i zeruje zakumulowany kąt obrotu.

4. **Pochłanianie Puszczenia Przycisku (`ConsumeCurrentHold`)**:
   - Podczas regulacji jasności silnik wywołuje `ConsumeCurrentHold()`.
   - Dzięki temu zwolnienie przycisku po zakończeniu regulacji **nie generuje fałszywego pojedynczego kliknięcia (Play/Pause)**.

5. **Kierunek i Skalowanie Kąta**:
   - Obrót w prawo (zgodnie z ruchem wskazówek zegara) -> Zwiększenie jasności ekranu (+).
   - Obrót w lewo (przeciwnie do wskazówek zegara) -> Zmniejszenie jasności ekranu (-).
   - Skalowanie: **$2.5^\circ$ obrotu = $1\%$ jasności** ($250^\circ$ pełnego obrotu = $100\%$).
   - Martwa strefa żyroskopu: $3.0^\circ/\text{s}$ (eliminuje mikroszarpnięcia i szum przetwornika).
   - Zabezpieczenie luki czasowej: Przy $\Delta t > 250\text{ ms}$ krok całkowania jest ograniczany do $dt = 0.02\text{ s}$.

---

## Tabela Parametrów Konfiguracyjnych (v3.1.5 Baseline)

| Parametr | Wartość | Jednostka | Opis |
|---|---|---|---|
| `DefaultStabilizationNanos` | `150_000_000` | ns (150 ms) | Czas stabilizacji w pozycji krawędziowej |
| `DegreesPerPercentBrightness` | `2.5` | °/% | Skalowanie kąta ($250^\circ = 100\%$ jasności) |
| `GyroscopeDeadbandDps` | `3.0` | °/s | Martwa strefa prędkości kątowej osi Z |
| `EdgeEnterMaxZ` | `0.45` | g | Maksymalne $\|a_z\|$ do wejścia w pozycję 90° |
| `EdgeExitMaxZ` | `0.60` | g | Maksymalne $\|a_z\|$ do podtrzymania pozycji 90° |
| `EdgeMinPlaneG` | `0.65` | g | Minimalne przyspieszenie w płaszczyźnie XY (wejście) |
| `EdgeMaxPlaneG` | `1.35` | g | Maksymalne przyspieszenie w płaszczyźnie XY (wejście) |
| `EdgeExitMinPlaneG` | `0.52` | g | Minimalne przyspieszenie w płaszczyźnie XY (wyjście) |
| `MaximumSampleGapNanos` | `250_000_000` | ns (250 ms) | Próg wykrywania luki strumienia (clamping do $0.02\text{ s}$) |
