---
title: Wydanie v3.1.0
tags:
  - releases
---

# Wydanie v3.1.0 — Tryb Myszki Żyroskopowej (Air Mouse) i Baza Wiedzy Obsidian

Data publikacji: 2026-09-02  
Wersje binarne: Windows `3.1.0.0` (Inno Setup), Android `versionCode = 40` (`3.1.0`)

Powiązane węzły:
- [[Air-Mouse-Mode]] — specyfikacja i algorytmy trybu Air Mouse.
- [[Settings-Architecture]] — panel uprawnień w Ustawieniach.
- [[Compact-HUD-Windows]] — powiadomienia OSD w nakładce Windows.
- [[INDEX]] — główny indeks bazy wiedzy.

---

## Główne Nowości i Zmiany

1. **Tryb Myszki Żyroskopowej (Air Mouse) w Windows**:
   - Włączanie i wyłączanie trybu przytrzymaniem fizycznego przycisku przez 4 sekundy.
   - Płynne sterowanie kursorem myszy w powietrzu przy odwróconym kapslu z nieliniową balistyką prędkościową.
   - Kliknięcia: 1 klik = Lewy Przycisk Myszy (LPM), 2 kliki = Prawy Przycisk Myszy (PPM).
   - Kółko przewijania (Scroll) w pozycji 90° na krawędzi kontrolera.
   - Dedykowane powiadomienia w mini-nakładce Windows Compact HUD oraz sygnały dźwiękowe.

2. **Uporządkowanie Uprawnień Systemowych**:
   - Przeniesiono uprawnienia do zakładki *Ustawienia* na Androidzie.
   - Dodano panel informacyjny uprawnień w *Ustawieniach* Windows z bezpośrednim skrótem do Ustawień systemowych (`ms-settings:privacy-radios`).

3. **Zintegrowana Baza Wiedzy Obsidian Knowledge Vault**:
   - Kompleksowa dokumentacja techniczna w katalogu `docs/` z powiązaniami dwukierunkowymi (wikilinks), rejestrem decyzji architektonicznych (ADR) oraz kolorowaniem węzłów w widoku grafu.
