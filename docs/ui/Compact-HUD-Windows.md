---
title: Mini-Nakładka Compact HUD (Windows)
tags:
  - ui
  - windows
---

# Mini-Nakładka Compact HUD (Windows)

**Compact HUD** to nowoczesne, zawsze widoczne na wierzchu (`AlwaysOnTop`) okno nakładki w prawym dolnym rogu ekranu Windows 11, informujące o każdej zmianie stanu odtwarzacza lub kontrolera.

Powiązane węzły:
- [[Windows-Architecture]] — implementacja w WinUI 3 (`CompactHudWindow.cs`).
- [[Gyro-Volume-Control]] — wywoływanie HUD przy zmianie głośności.
- [[Inverted-Capsule-Gestures]] — wywoływanie HUD przy zmianie utworu.
- [[Edge-Brightness-Control]] — wywoływanie HUD przy zmianie jasności ekranu.

---

## Kluczowe Cechy

1. **Obsługa Rzeczywistych Okładek Albumów (`Image` + RAW byte stream)**:
   - Miniatura odtwarzanego utworu jest asynchronicznie dekodowana ze strumienia bajtów Windows Media i prezentowana z zaokrąglonymi narożnikami ($12\text{ px}$).
   - W przypadku braku okładki następuje automatyczny fallback na ikony Segoe Fluent Icons (`\uE893` / `\uE892`).

2. **Płynny Wskaźnik Głośności bez Jittera**:
   - Usunięto podwójne wywołania ze starymi wartościami pamięci podręcznej.
   - Wskaźnik przesuwa się precyzyjnie i monotonicznie w dół/górę.

3. **Dedykowane Ikony Fluent**:
   - Głośność: `\uE74F` (Mute), `\uE993` (Vol 1), `\uE994` (Vol 2), `\uE995` (Vol 3).
   - Jasność ekranu: `\uE706` (Fluent Brightness / Sun).
   - Zmiana utworu: `\uE892` (Previous), `\uE893` (Next), `\uE768` (Play).

4. **Automatyczne Zamykanie po 2 Sekundach**:
   - Zegar bezczynności (`DispatcherTimer`) płynnie ukrywa okno po 2 sekundach od ostatniej interakcji bez kradzieży fokusu użytkownika (`SW_SHOWNOACTIVATE`).
