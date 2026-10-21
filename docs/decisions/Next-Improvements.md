---
title: Kolejne usprawnienia Triki
tags:
  - decisions
  - roadmap
---

# Kolejne usprawnienia Triki

Wszystkie trzy poniższe funkcje wdrożono w [[Release-v3.2.0]]. Domyślny tryb to Normal 30 minut; tryby Eco 2/5/10 minut i stałe połączenie pozostają do wyboru. Historia zachowuje 200 wpisów i oferuje eksport JSON. Obie czułości mają zakres 50–200% i osobne włączniki.

| Priorytet | Usprawnienie | Efekt dla użytkownika | Zakres techniczny |
| --- | --- | --- | --- |
| 1 | Wybór czasu: Normal 30 minut, Eco 2/5/10 minut lub stałe połączenie | Dopasowanie gotowości do sposobu używania, bez zmiany kodu | Zapis ustawienia na obu platformach, walidacja, migracja do domyślnego Normal 30 minut i reset licznika po zmianie |
| 2 | Historia połączeń i eksport diagnostyki | Możliwość sprawdzenia, dlaczego połączenie zniknęło i kiedy aplikacja ponowi próbę | Rejestrowanie przejść BLE, celowego uśpienia, timeoutów GATT i zaniku IMU; ograniczony rozmiar logu; jawne wskazanie nieznanej przyczyny |
| 3 | Osobna czułość i włączniki gestów głośności oraz jasności | Mniej przypadkowych zmian i możliwość wyłączenia zbędnego gestu | Walidowane parametry kontrolerów, podgląd działania, przywracanie wartości domyślnych i zgodne ustawienia Windows/Android |

Najpierw należy potwierdzić na fizycznym Triki automatyczne wznawianie po bezczynności, wybudzeniu i utracie zasięgu. Testy algorytmów oraz uruchomienie aplikacji nie zastępują tego sprawdzenia.

Powiązania: [[Settings-Architecture]], [[BLE-Protocol]], [[Gyro-Volume-Control]], [[Edge-Brightness-Control]], [[Releases-Overview]].
