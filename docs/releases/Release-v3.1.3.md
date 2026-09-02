---
title: Wydanie v3.1.3
tags:
  - releases
---

# Wydanie v3.1.3 — Dopracowanie Trybu Myszki (Air Mouse)

Data publikacji: 2026-09-02  
Wersje binarne: Windows `3.1.3.0` (Inno Setup), Android `versionCode = 43` (`3.1.3`)

Powiązane węzły:
- [[Air-Mouse-Mode]] — zaktualizowany model sub-pikselowy i click-lock.
- [[Compact-HUD-Windows]] — powiadomienia OSD w nakładce Windows.
- [[INDEX]] — główny indeks bazy wiedzy.

---

## Główne Usprawnienia

1. **Sub-pikselowy Akumulator Ułamkowy**:
   - Płynny ruch piksel po pikselu bez gubienia powolnych mikro-ruchów dłoni.

2. **Tłumienie Drgań Kliknięcia (Click-Jitter Lock)**:
   - W momencie fizycznego wciskania przycisku kursor jest stabilizowany przez 90 ms, co zapobiega zsuwaniu się ze wskazywanego elementu.

3. **Adaptacyjny Filtr EMA**:
   - Skuteczna filtracja drżenia dłoni przy wolnym celowaniu połączona z zerowym opóźnieniem przy dynamicznych zamachach.

4. **Zwiększona Czułość Kółka Przewijania (Scroll)**:
   - Czułość scrolla zwiększona do $8.0^\circ$ na krok, zapewniając responsywne przewijanie stron i dokumentów.
