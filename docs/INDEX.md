---
title: Triki Music Controller - Główny Indeks Grafu
tags:
  - hub
  - index
---

# Triki Music Controller — Baza Wiedzy (Obsidian Vault)

Witamy w centralnej bazie wiedzy i grafie powiązań projektu **Triki Music Controller** — bezprzewodowego kontrolera multimediów opartego na żyroskopie i akcelerometrze (BLE), wspierającego systemy **Windows 11 (WinUI 3)** oraz **Android (Jetpack Compose)**.

---

## Główne Węzły Grafu (MOC - Maps of Content)

### 1. Architektura Systemu (`#architecture`)
- [[System-Overview]] — Całościowy model przepływu danych i zasady podziału na warstwy.
- [[Android-Architecture]] — Architektura aplikacji mobilnej (Kotlin, Jetpack Compose, StateFlow).
- [[Windows-Architecture]] — Architektura aplikacji desktopowej (C# / .NET 10, WinUI 3, Compact HUD).

### 2. Algorytmy i Silnik Sterowania (`#core` `#imu` `#ble`)
- [[BLE-Protocol]] — Specyfikacja pakietu 20-bajtowego, usługi GATT i zarządzanie wybudzeniem (`WakeAdvertisementGate`).
- [[Sensor-Filtering]] — Kalibracja, filtr Median3, filtr komplementarny i adaptacyjna martwa strefa żyroskopu.
- [[Gyro-Volume-Control]] — Płynna regulacja głośności w pozycji stojącej z 2-sekundową bramką stabilizacji.
- [[Inverted-Capsule-Gestures]] — Przełączanie utworów (Next/Previous) gestem odwróconego kapsla z konfigurowalnym kątem (`90°–360°`).
- [[Edge-Brightness-Control]] — Regulacja jasności ekranu na krawędzi (90°) z wymogiem przytrzymania przycisku.
- [[Air-Mouse-Mode]] — Tryb myszki żyroskopowej (kursor, LPM, PPM, scroll 90°, aktywacja 4s).
- [[Button-Interpreter]] — Obsługa kliknięć, eliminacja odbić styku i konsumpcja przytrzymania (`ConsumeCurrentHold`).
- [[Multi-Device-Arbitration]] — Inteligentne przełączanie kontrolera między komputerem a telefonem.
- [[Spotify-Connect-Integration]] — Zdalne sterowanie urządzeniami w sieci domowej przez Spotify Connect.

### 3. Interfejs Użytkownika i Doświadczenie (`#ui`)
- [[Settings-Architecture]] — Czysty podział ustawień na 4 intuicyjne kategorie.
- [[Controls-Screen]] — Studio telemetrii, suwak czułości obrotu i mapowanie przycisków.
- [[Compact-HUD-Windows]] — Mini-nakładka z ikonami Fluent, okładkami albumów i płynnym wskaźnikiem.

### 4. Decyzje Architektoniczne (`#decision` `#adr`)
- [[Architecture-Decisions]] — Rejestr kluczowych decyzji projektowych (ADR).
- [[ADR-001-Strict-Zero-Emoji-Policy]] — Bezwzględny brak emotikonów w całym projekcie.
- [[ADR-002-Dual-Platform-Core-Parity]] — 100% spójność algorytmów między C# i Kotlin.
- [[ADR-003-Button-Hold-For-Brightness]] — Wymóg trzymania przycisku przy regulacji jasności ekranu.
- [[ADR-004-Dynamic-Version-Binding]] — Dynamiczne wersjonowanie i niezawodny instalator Inno Setup.

### 5. Wydania i Dziennik Zmian (`#releases`)
- [[Releases-Overview]] — Przegląd wszystkich wydań aplikacji.
- [[Release-v3.1.1]] — Wersja 3.1.1: Poprawki osi pionowej i czułości Air Mouse, cicha automatyczna instalacja.
- [[Release-v3.1.0]] — Wersja 3.1.0: Tryb myszki żyroskopowej (Air Mouse), uprawnienia w Ustawieniach, baza wiedzy Obsidian.
- [[Release-v3.0.1]] — Wersja 3.0.1: Uporządkowanie ustawień, okładki albumów i regulacja jasności.
- [[Release-v3.0.0]] — Wersja 3.0.0: Nowoczesny motyw Material Design 3 i stabilizacja telemetryczna.
- [[Release-v2.9.9]] — Wersja 2.9.9: Spójność interfejsu i oczyszczenie etykiet.

---

*Wskazówka: Otwórz **Graph View** w Obsidianie (`Ctrl+G`), aby zobaczyć interaktywny graf zależności całego projektu.*
