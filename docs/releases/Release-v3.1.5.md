---
title: Wydanie v3.1.5
tags:
  - releases
---

# Wydanie v3.1.5 — Przywrócenie i Naprawa Regulacji Jasności Ekranu

Data publikacji: 2026-09-02  
Wersje binarne: Windows `3.1.5.0` (Inno Setup), Android `versionCode = 45` (`3.1.5`)

Powiązane węzły:
- [[Edge-Brightness-Control]] — zaktualizowany opis sterowania jasnością.
- [[Compact-HUD-Windows]] — nakładka HUD w Windows.
- [[INDEX]] — główny indeks dokumentacji.

---

## Główne Usprawnienia

1. **Naprawa Wywołań WMI i Dodanie Obsługi DDC/CI**:
   - Skorygowano parametry wywołania metody `WmiSetBrightness` (poprawny typ `uint32` dla timeoutu i `uint8` dla wartości jasności), co usunęło błędy w komunikacji z ekranami wbudowanymi.
   - Wdrożono natywne wsparcie dla protokołu DDC/CI (`dxva2.dll`) dla zewnętrznych monitorów biurkowych (HDMI / DisplayPort).

2. **Płynna Histereza Pozycji Krawędziowej 90°**:
   - Zwiększono tolerancję przyspieszenia przy obracaniu dłonią, dzięki czemu dynamiczny obrót nie powoduje przypadkowego wyjścia z trybu jasności.

3. **Błyskawiczna Responsywność**:
   - Skrócono czas stabilizacji do 150 ms oraz umożliwiono natychmiastową regulację jasności po wciśnięciu przycisku.
