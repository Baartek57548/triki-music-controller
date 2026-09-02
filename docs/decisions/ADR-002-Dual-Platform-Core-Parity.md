---
title: ADR-002 Stuprocentowa Parzystość Algorytmów Core
tags:
  - decision
  - adr
---

# ADR-002: Stuprocentowa Parzystość Algorytmów Core (Dual-Platform Core Parity)

## Status
Zaakceptowana (Obowiązująca)

## Kontekst
Użytkownik korzystający z kontrolera Triki na komputerze PC i telefonie komórkowym oczekuje identycznej fizyki, czułości, histerezy i zachowania urządzenia na obu platformach.

## Decyzja
Wszystkie algorytmy matematyczne, filtry i detektory gestów w warstwie Core są utrzymywane w stanie **100% parzystości matematycznej i behawioralnej** pomiędzy językiem C# (`windows/TrikiMusicController.Windows/Core/`) a językiem Kotlin (`app/src/main/java/pl/trikimusic/controller/core/`):
- `SensorFilter` (Median3, bias, martwa strefa, IIR, komplementarny),
- `GyroscopeVolumeController` (bramka 2.0s, integracja kąta, EMA Z, histereza),
- `FullRotationGestureDetector` (odwrócony kapsel, całkowanie Z, konfigurowalny kąt),
- `EdgePoseBrightnessController` (pozycja 90°, wymagany hold przycisku),
- `TrikiButtonInterpreter` (debouncing, sekwencje multi-click, konsumpcja hold).

## Konsekwencje
Identyczne wrażenia z użytkowania bez względu na to, czy kontroler jest sparowany z Windows 11 czy Androidem.
