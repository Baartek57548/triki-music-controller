---
title: Gesty Odwróconego Kapsla (Zmiana Utworu)
tags:
  - core
  - gesture
---

# Gesty Odwróconego Kapsla (Zmiana Utworu)

Moduł `FullRotationGestureDetector` odpowiada za przełączanie utworów (Następny / Poprzedni) po odwróceniu kapsla do góry dnem ($180^\circ$).

Powiązane węzły:
- [[Controls-Screen]] — konfiguracja docelowego kąta obrotu ($90^\circ–360^\circ$).
- [[Compact-HUD-Windows]] — popup zmiany utworu z okładką albumu.
- [[Sensor-Filtering]] — detekcja wektora grawitacji.

---

## Logika Wykrywania Gestu

```mermaid
stateDiagram-v2
    [*] --> Idle: Kapsel w pozycji normalnej (Z ≈ -1g)
    Idle --> Stabilizing: Odwrócenie kapsla (Z ≈ +1g)
    Stabilizing --> Ready: Utrzymanie pozycji przez 500ms
    Stabilizing --> Idle: Powrót do pozycji normalnej
    
    Ready --> Tracking: Rozpoczęcie obrotu wokół osi Z
    Tracking --> Triggered: Osiągnięcie zadanego kąta (np. 200°)
    Tracking --> Ready: Zatrzymanie ruchu lub zmiana kierunku
    
    Triggered --> CoolDown: Wysłanie akcji Next / Previous
    CoolDown --> Ready: Uspokojenie ruchu (histereza)
```

- **Ruch dłoni w lewo (przeciwnie do wskazówek zegara)** -> Akcja `Next` (Następny utwór).
- **Ruch dłoni w prawo (zgodnie ze wskazówkami zegara)** -> Akcja `Previous` (Poprzedni utwór).
- **Konfigurowalny Kąt Docelowy**: Użytkownik może ustawić czułość w zakresie $90^\circ–360^\circ$ (domyślnie $200^\circ$).
