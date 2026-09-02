---
title: Wydanie v3.1.4
tags:
  - releases
---

# Wydanie v3.1.4 — Histereza Krawędziowa i Audyt Jakościowy Air Mouse

Data publikacji: 2026-09-02  
Wersje binarne: Windows `3.1.4.0` (Inno Setup), Android `versionCode = 44` (`3.1.4`)

Powiązane węzły:
- [[Air-Mouse-Mode]] — model sterowania z histerezą krawędziową i sub-pikselami.
- [[Compact-HUD-Windows]] — nakładka HUD w Windows.
- [[INDEX]] — główny indeks dokumentacji.

---

## Główne Usprawnienia

1. **Histereza Kątowa Trybu Kółka Przewijania (Scroll 90°)**:
   - Wprowadzono oddzielne progi wejścia ($|Y| \ge 0.70g, |Z| \le 0.35g$) oraz wyjścia ($|Y| < 0.55g, |Z| > 0.50g$), co eliminuje migotanie/drżenie trybów na granicy kąta obrotu dłoni.

2. **Poprawka Stałej Win32 Prawego Przycisku Myszy (PPM)**:
   - Skorygowano wartość flagi `MOUSEEVENTF_RIGHTUP` na `0x0010` (zamiast błędnej kombinacji flag `0x0009`).

3. **Zabezpieczenie Strumienia Danych i Stabilność Matematyczna**:
   - Dodano reset akumulatorów po przerwie w transmisji próbki (`gap reset > 250ms`) oraz filtrację wartości nieliczbowych (`float.IsFinite`).
