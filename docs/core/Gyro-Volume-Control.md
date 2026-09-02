---
title: Algorytm Płynnej Regulacji Głośności Żyroskopem
tags:
  - core
  - imu
---

# Algorytm Płynnej Regulacji Głośności Żyroskopem

Moduł `GyroscopeVolumeController` umożliwia intuicyjną regulację głośności systemowej poprzez obracanie kontrolera stojącego w pozycji pionowej.

Powiązane węzły:
- [[Sensor-Filtering]] — filtracja danych wejściowych.
- [[Compact-HUD-Windows]] — wizualizacja głośności w mini-nakładce.
- [[Inverted-Capsule-Gestures]] — gest zmiany utworu w pozycji odwróconej.

---

## Zasada Działania

1. **Bramka Kąta Stabilizacji (2.0 s)**:
   - Kapsel musi znajdować się w pozycji stojącej (odchylenie od pionu $0–25^\circ$, wektor przyspieszenia $0.8–1.2 g$).
   - Wymagany jest ciągły czas stabilizacji $2000\text{ ms}$, co zapobiega przypadkowym zmianom głośności podczas podnoszenia ze stołu.

2. **Całkowanie Kąta Obrotu (Continuous Integration)**:
   - W stanie gotowości (`Ready`), obrót wokół osi Z integruje kąt obrotu:
   $$\Delta \theta = \omega_z \cdot \Delta t$$
   - Czułość wynosi domyślnie **$22^\circ$ na $1$ krok głośności** (ok. 2% głośności systemowej).

3. **Histereza i Tłumienie (EMA Z)**:
   - Wygładzanie wykładnicze EMA Z ze współczynnikiem $0.16$.
   - Progi histerezy $22^\circ/\text{s}$ (aktywacja) i $12^\circ/\text{s}$ (dezaktywacja) eliminują drżenie dłoni.
   - Minimalny interwał między krokami to $140\text{ ms}$.
