---
title: Wydanie v3.1.6
tags:
  - releases
---

# Wydanie v3.1.6 — Kompleksowy Audyt Architektury i Synchronizacja Platform

Data publikacji: 2026-09-03  
Wersje binarne: Windows `3.1.6.0` (Inno Setup), Android `versionCode = 46` (`3.1.6`)

Powiązane węzły:
- [[System-Overview]] — zaktualizowana architektura systemu.
- [[Android-Architecture]] — zsynchronizowana logika platformy Android.
- [[INDEX]] — główny indeks dokumentacji.

---

## Główne Usprawnienia

1. **Audyt Architektury i Zarządzania Pamięcią (Windows / WinUI 3)**:
   - Wdrożono pełny cykl życia `IDisposable` w klasach usługowych (`AppServices`, `BluetoothService`, `CompactHudService`, `MainViewModel`), eliminując wycieki pamięci i niezwolnione uchwyty COM/WinRT.

2. **Pełny Parytet Platformowy (Android / Kotlin)**:
   - Zsynchronizowano moduły `TrikiButtonInterpreter.kt`, `EdgePoseBrightnessController.kt` oraz `GyroscopeVolumeController.kt` z implementacją Windows, gwarantując identyczne zachowanie kontrolera na smartfonach i komputerach.

3. **Rozszerzony Zestaw Testów E2E i Testów Jednostkowych**:
   - 119/119 testów jednostkowych Windows, 115/115 testów Androida oraz 60/60 testów wielopoziomowych E2E (Tiers 1-5).
