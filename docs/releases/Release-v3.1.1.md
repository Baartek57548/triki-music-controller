---
title: Wydanie v3.1.1
tags:
  - releases
---

# Wydanie v3.1.1 — Usprawnienia Air Mouse i W Pełni Automatyczna Instalacja

Data publikacji: 2026-09-02  
Wersje binarne: Windows `3.1.1.0` (Inno Setup), Android `versionCode = 41` (`3.1.1`)

Powiązane węzły:
- [[Air-Mouse-Mode]] — zaktualizowany model balistyki i osi pionowej.
- [[Settings-Architecture]] — panel uprawnień w Ustawieniach.
- [[Compact-HUD-Windows]] — powiadomienia OSD w nakładce Windows.
- [[INDEX]] — główny indeks bazy wiedzy.

---

## Główne Usprawnienia

1. **Poprawka Osi Ruchu Kursora (Góra/Dół)**:
   - Skorygowano kierunek osi pionowej: ruch ręką w górę przesuwa kursor w górę ekranu, a ruch w dół przesuwa kursor w dół.

2. **Optymalizacja Czułości i Płynności**:
   - Obniżono współczynnik bazowej czułości z `0.38` do `0.18`, co zapewnia stabilne, precyzyjne celowanie bez niepożądanego drżenia ręki.
   - Zwiększono filtr martwej strefy do `4.0°/s` oraz wygładzanie EMA (`0.45` / `0.55`).

3. **W Pełni Automatyczna Cicha Aktualizacja od A do Z**:
   - Po kliknięciu „Pobierz i zainstaluj” aplikacja pobiera plik, sprawdza sumę SHA-256, bezgłośnie podmienia pliki w tle i automatycznie uruchamia nową wersję z oknem informacji o zmianach.
