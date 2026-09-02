---
title: ADR-003 Wymóg Przytrzymania Przycisku przy Regulacji Jasności
tags:
  - decision
  - adr
---

# ADR-003: Wymóg Przytrzymania Przycisku przy Regulacji Jasności

## Status
Zaakceptowana (Obowiązująca)

## Kontekst
Pozycja krawędziowa 90° może wystąpić przypadkowo podczas odkładania kontrolera na biurko, wkładania do kieszeni lub podnoszenia. Samodzielna zmiana jasności w pozycji 90° powodowała niezamierzone rozjaśnianie/przyciemnianie ekranu.

## Decyzja
Wymagane jest **ciągłe trzymanie wciśniętego przycisku fizycznego** podczas obracania kapsla na krawędzi 90°.
Dodatkowo zwolnienie przycisku po regulacji wywołuje `ConsumeCurrentHold()`, co blokuje wyemitowanie fałszywego pojedynczego kliknięcia (pauza/play).

## Konsekwencje
Eliminacja przypadkowych zmian jasności ekranu i wyeliminowanie fałszywych pauz po zakończeniu regulacji.
