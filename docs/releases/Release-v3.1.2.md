---
title: Wydanie v3.1.2
tags:
  - releases
---

# Wydanie v3.1.2 — Pełny Zasięg Pionowy Kursora i Ulepszona Detekcja Krawędzi

Data publikacji: 2026-09-02  
Wersje binarne: Windows `3.1.2.0` (Inno Setup), Android `versionCode = 42` (`3.1.2`)

Powiązane węzły:
- [[Air-Mouse-Mode]] — zaktualizowany model pełnego zasięgu pionowego.
- [[Compact-HUD-Windows]] — powiadomienia OSD w nakładce Windows.
- [[INDEX]] — główny indeks bazy wiedzy.

---

## Główne Usprawnienia

1. **Pełny Zasięg Ruchu Kursora w Pionie (Góra/Dół)**:
   - Usunięto sztuczne ograniczenie kąta pochylenia, co pozwala kursorowi bez przeszkód osiągać samą górną krawędź ekranu i rogi monitora.

2. **Doprecyzowanie Trybu Scrolla**:
   - Wykrywanie trybu kółka myszy (Scroll) powiązano z fizycznym obróceniem na boczną krawędź kontrolera ($|a_y| \ge 0.70g$), zapobiegając przypadkowemu przełączaniu w tryb scrolla przy podnoszeniu ręki do góry.

3. **Ergonomiczne Wzmocnienie Osi Pionowej**:
   - Zoptymalizowano krzywą balistyki oraz dodano subtelne wzmocnienie pionowe dopasowane do naturalnego zakresu ruchu nadgarstka.
