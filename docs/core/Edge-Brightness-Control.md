---
title: Regulacja Jasności Ekranu na Krawędzi 90°
tags:
  - core
  - imu
---

# Regulacja Jasności Ekranu na Krawędzi 90°

Moduł `EdgePoseBrightnessController` umożliwia precyzyjną zmianę jasności ekranu monitora lub wyświetlacza smartfona.

Powiązane węzły:
- [[Button-Interpreter]] — obsługa i konsumpcja przytrzymania przycisku (`ConsumeCurrentHold`).
- [[ADR-003-Button-Hold-For-Brightness]] — decyzja o wymogu przytrzymania przycisku.
- [[Compact-HUD-Windows]] — wizualizacja jasności w Windows HUD.

---

## Reguły Sterowania

1. **Pozycja Krawędziowa (Kąt 90°)**:
   - Akcelerometr osi Z bliski zera ($|a_z| \le 0.40g$).
   - Wektor przyspieszenia w płaszczyźnie XY bliski $1.0g$ ($0.75g \le \sqrt{a_x^2 + a_y^2} \le 1.25g$).

2. **Wymóg Przytrzymania Przycisku (`Hold Requirement`)**:
   - Regulacja jasności działa **wyłącznie wtedy, gdy użytkownik trzyma wciśnięty fizyczny przycisk**.
   - Zwolnienie przycisku natychmiast zatrzymuje regulację i zeruje zakumulowany obrót.

3. **Pochłanianie Puszczenia Przycisku (`ConsumeCurrentHold`)**:
   - Podczas regulacji jasności silnik wywołuje `ConsumeCurrentHold()`.
   - Dzięki temu zwolnienie przycisku **nie generuje fałszywego pojedynczego kliknięcia (Play/Pause)**.

4. **Kierunek i Czułość**:
   - Obrót w prawo (zgodnie z ruchem wskazówek zegara) -> Zwiększenie jasności ekranu (+).
   - Obrót w lewo (przeciwnie do wskazówek zegara) -> Zmniejszenie jasności ekranu (-).
   - Skalowanie: $3.6^\circ$ obrotu = $1\%$ jasności ($360^\circ$ pełnego obrotu = $100\%$).
