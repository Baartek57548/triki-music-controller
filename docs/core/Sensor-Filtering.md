---
title: Filtracja i Kalibracja Czujników IMU
tags:
  - core
  - imu
---

# Filtracja i Kalibracja Czujników IMU

Układ IMU w urządzeniu Triki dostarcza surowe odczyty akcelerometru i żyroskopu. Moduł `SensorFilter` przekształca je w precyzyjny wektor fizyczny.

Powiązane węzły:
- [[BLE-Protocol]] — źródło surowych danych.
- [[Gyro-Volume-Control]] — odbiorca przefiltrowanych odczytów żyroskopu.
- [[Inverted-Capsule-Gestures]] — detekcja pozycji odwróconej na podstawie akcelerometru.
- [[Edge-Brightness-Control]] — detekcja pozycji krawędziowej 90°.

---

## Etapy Przetwarzania (Filter Pipeline)

1. **Kompensacja Błędu Stałego (Calibration Bias)**:
   - Odejmuje zmierzony podczas procedury kalibracji dryf osi:
   $$\vec{a}_{calibrated} = \vec{a}_{raw} - \vec{bias}_a$$
   $$\vec{\omega}_{calibrated} = \vec{\omega}_{raw} - \vec{bias}_g$$

2. **Filtr Medianowy z 3 Próbek (Median3 Filter)**:
   - Eliminuje pojedyncze szpilki pomiarowe (impulsy szumu) z przetwornika ADC.

3. **Adaptacyjna Martwa Strefa Żyroskopu (Dynamic Deadband)**:
   - Ignoruje mikrodrgania poniżej progu szumu (domyślnie `3.5–4.0 °/s`), zapobiegając samoczynnemu pływaniu głośności.

4. **Filtr Dolnoprzepustowy (Low-Pass IIR)**:
   - Wygładza gwałtowne skoki przy użyciu współczynnika $\alpha \approx 0.25$.

5. **Filtr Komplementarny (Pitch / Roll / Yaw)**:
   - Fuzja danych akcelerometru (długoterminowa stabilność wektora grawitacji $1g$) z żyroskopem (szybka reakcja dynamiczna).
