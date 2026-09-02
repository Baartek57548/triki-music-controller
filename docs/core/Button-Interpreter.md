---
title: Interpreter Przycisku Fizycznego
tags:
  - core
  - button
---

# Interpreter Przycisku Fizycznego

Moduł `TrikiButtonInterpreter` analizuje pole statusu przesyłane w ramkach BLE i dekoduje interakcje użytkownika z fizycznym przyciskiem Triki.

Powiązane węzły:
- [[BLE-Protocol]] — źródło pola statusu w pakiecie.
- [[Edge-Brightness-Control]] — współpraca z konsumpcją przytrzymania.
- [[Controls-Screen]] — mapowanie akcji kliknięć.

---

## Parametry i Mechanika

- **Automatyczne Wykrywanie Protokołu**: Rozróżnia urządzenia wysyłające flagę przycisku (`0/1`) od tych z sekwencyjnym licznikiem pakietów.
- **Debouncing (Eliminacja drgań styków)**: Okno filtracji $18\text{ ms}$.
- **Kwalifikacja Kliknięcia**: Czas wciśnięcia od $25\text{ ms}$ do $2000\text{ ms}$.
- **Okno Multi-Click**: Czas oczekiwania na kolejne kliknięcie wynosi $450\text{ ms}$.
- **Zwracane Zdarzenia**:
  - `SingleClick` (domyślnie: Odtwórz / Wstrzymaj)
  - `DoubleClick` (domyślnie: Polub utwór / Like)
  - `TripleClick` (domyślnie: Odrzuć utwór / Dislike)
- **Konsumpcja Przytrzymania (`ConsumeCurrentHold`)**: Metoda używana przez silnik gestów krawędziowych do zablokowania emisji kliknięcia po zakończeniu obrotu.
