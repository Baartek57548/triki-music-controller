---
title: Wydanie v3.0.1
tags:
  - releases
---

# Wydanie v3.0.1 — Uporządkowanie Ustawień, Okładki Albumów w HUD i Regulacja Jasności

Data publikacji: 2026-09-01 / 2026-09-02  
Wersje binarne: Windows `3.0.1.0` (Inno Setup), Android `versionCode = 39` (`3.0.1`)

Powiązane węzły:
- [[Settings-Architecture]] — nowa architektura Ustawień.
- [[Compact-HUD-Windows]] — okładki albumów i eliminacja jittera w HUD.
- [[Edge-Brightness-Control]] — regulacja jasności z wymogiem przytrzymania przycisku.
- [[ADR-003-Button-Hold-For-Brightness]] & [[ADR-004-Dynamic-Version-Binding]] — decyzje techniczne.

---

## Główne Zmiany

1. **Przebudowa Ustawień (Czysta architektura 4 sekcji)**:
   - *Połączenie i zasilanie*, *Działanie i wygląd*, *Integracje* (Spotify Connect) oraz *O aplikacji i narzędzia*.
2. **Wyświetlanie Okładek Albumów w Windows Compact HUD**:
   - Asynchroniczne dekodowanie miniatur RAW ze strumienia Windows Media z eleganckimi zaokrąglonymi rogami ($12\text{ px}$) i fallbackiem na ikony Segoe Fluent.
3. **Płynna Regulacja Głośności bez Jittera**:
   - Usunięto podwójne wywołanie HUD ze starymi wartościami cache. Pasek schodzi płynnie i monotonicznie.
4. **Regulacja Jasności Ekranu (90° + Hold Przycisku)**:
   - Wymóg trzymania przycisku fizycznego podczas obrotu na krawędzi 90° oraz automatyczna konsumpcja puszczenia (`ConsumeCurrentHold`).
5. **Przeniesienie Konfiguracji Kąta Obrotu do Sterowania**:
   - Suwak czułości obrotu ($90^\circ–360^\circ$) oraz presety umieszczone w zakładce *Sterowanie*.
