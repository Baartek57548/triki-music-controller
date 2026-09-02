---
title: Architektura Aplikacji Android
tags:
  - architecture
  - android
---

# Architektura Aplikacji Android

Aplikacja mobilna **Triki Music Controller** dla systemu Android została zbudowana według wzorca **Clean Architecture + MVVM** przy użyciu nowoczesnego stosu technologicznego:
- **Język**: Kotlin 2.x
- **Interfejs**: Jetpack Compose + Material Design 3 (Emerald/Obsidian Theme)
- **Asynchroniczność**: Kotlin Coroutines & `StateFlow` / `SharedFlow`
- **Pamięć trwała**: Jetpack DataStore Preferences (wersjonowany JSON)

Powiązane węzły:
- [[System-Overview]] — ogólny model przepływu danych.
- [[BLE-Protocol]] — implementacja `TrikiBleManager` w Androidzie.
- [[Settings-Architecture]] — 4-kategoriowy ekran `SettingsScreen`.
- [[Controls-Screen]] — studio sterowania w Compose.

---

## Kluczowe Komponenty

1. **`TrikiBleManager`**:
   - Maszyna stanów BLE (`DISCONNECTED` -> `SCANNING` -> `FOUND` -> `CONNECTING` -> `CONNECTED` -> `READY`).
   - Obsługuje automatyczne ponawianie połączenia (`autoConnect=true`) oraz tryb oszczędzania energii `WAITING_FOR_WAKE`.

2. **`AndroidMediaControllerGateway`**:
   - Monitoruje aktywne sesje audio przez `NotificationListenerService` i `MediaSessionManager`.
   - Zapewnia fallback na `AudioManager.dispatchMediaKeyEvent()` w przypadku restrykcyjnych nakładek producentów (np. MIUI / HyperOS).

3. **`SystemBrightnessManager`**:
   - Zmienia jasność ekranu w systemie Android po uzyskaniu uprawnienia `WRITE_SETTINGS`.

4. **`GitHubUpdateManager`**:
   - Bezpiecznie sprawdza wydania na GitHubie, weryfikuje sumy kontrolne SHA-256 oraz certyfikat podpisujący APK przed przekazaniem do systemowego instalatora przez `FileProvider`.
