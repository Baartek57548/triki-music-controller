---
title: Architektura Aplikacji Windows
tags:
  - architecture
  - windows
---

# Architektura Aplikacji Windows

Aplikacja desktopowa **Triki Music Controller** dla systemu Windows 11 została stworzona w oparciu o:
- **Platforma**: .NET 10 (C# 13)
- **Powłoka UI**: WinUI 3 (Windows App SDK 1.6 / 2.x)
- **Komunikacja BLE**: `Windows.Devices.Bluetooth.GenericAttributeProfile`
- **Instalator**: Inno Setup 6 (64-bit modern dynamic wizard)

Powiązane węzły:
- [[System-Overview]] — architektura ogólna.
- [[Compact-HUD-Windows]] — mini-nakładka Compact HUD z obsługą okładek.
- [[Settings-Architecture]] — implementacja `SettingsPage.xaml`.
- [[ADR-004-Dynamic-Version-Binding]] — mechanizm dynamicznego wersjonowania i bezpiecznych aktualizacji.

---

## Kluczowe Komponenty

1. **`BluetoothLeService`**:
   - Rejestruje `BluetoothLEAdvertisementWatcher` i nawiązuje połączenie GATT z urządzeniem Triki.
   - Posiada wbudowaną maszynę stanów z automatyczną resynchronizacją strumienia i buforem ramki.

2. **`MediaControlService`**:
   - Korzysta z `GlobalSystemMediaTransportControlsSessionManager` (GSMTC).
   - Przechwytuje metadane aktualnie odtwarzanego utworu (tytuł, wykonawca, okładka albumu w formacie RAW bytes stream) oraz wysyła komendy Play, Pause, Next, Previous.

3. **`SystemVolumeService` & `SystemBrightnessService`**:
   - Precyzyjne sterowanie głośnością główną Windows (`CoreAudioApi` / `IAudioEndpointVolume`).
   - Regulacja jasności ekranu za pośrednictwem WMI (`WmiMonitorBrightnessMethods`).

4. **`UpdateService`**:
   - Pobiera najnowszy instalator `triki-music-controller-windows-vX.X.X-setup.exe` z GitHub Releases.
   - Weryfikuje integralność SHA-256, sprawdza nagłówek PE (`MZ`) i bezpiecznie uruchamia proces instalacyjny.
