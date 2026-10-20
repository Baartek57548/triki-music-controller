---
title: Protokół Komunikacji BLE
tags:
  - core
  - ble
---

# Protokół Komunikacji BLE (Triki Protocol)

Kontroler **Triki** komunikuje się za pośrednictwem profilu GATT Bluetooth Low Energy (BLE), przesyłając pakiety telemetryczne IMU o stałej długości **20 bajtów** w częstotliwości ok. 50 Hz (co 20 ms).

Powiązane węzły:
- [[Sensor-Filtering]] — proces dekodowania i kalibracji surowych próbek.
- [[Button-Interpreter]] — interpretacja pola statusu (przycisk fizyczny).
- [[System-Overview]] — architektura systemu.
- [[TRIKI_PROTOCOL]] — wczesna specyfikacja inżynierii wstecznej i testów sprzętowych (Research Reference).

---

## Struktura Ramki Danych (20 bajtów)

| Bajty | Typ danych | Znaczenie | Jednostka / Skalowanie |
|---|---|---|---|
| `0..3` | `uint32_le` | Numer ramki / Timestamp | Milisekundy lub indeks |
| `4..5` | `int16_le` | Żyroskop Oś X | `raw / 16.4` -> °/s (dps) |
| `6..7` | `int16_le` | Żyroskop Oś Y | `raw / 16.4` -> °/s (dps) |
| `8..9` | `int16_le` | Żyroskop Oś Z | `raw / 16.4` -> °/s (dps) |
| `10..11` | `int16_le` | Akcelerometr Oś X | `raw / 2048.0` -> g |
| `12..13` | `int16_le` | Akcelerometr Oś Y | `raw / 2048.0` -> g |
| `14..15` | `int16_le` | Akcelerometr Oś Z | `raw / 2048.0` -> g |
| `16..17` | `int16_le` | Pole Statusu / Przycisk | `0` = Zwolniony, `1` = Wciśnięty (lub licznik) |
| `18..19` | `uint16_le` | Suma kontrolna / Rezerwa | CRC / Checksum |

---

## Tryb Połączenia na Żądanie (`WakeAdvertisementGate`)

Opcjonalny tryb Eco:
1. Po **2 minutach bezczynności** (brak ruchu i brak kliknięć) aplikacja zwalnia połączenie GATT i przechodzi w stan nasłuchu (`WAITING_FOR_WAKE`).
2. Po celowym zwolnieniu GATT bramka `WakeAdvertisementGate` dopuszcza reklamę po minimum 5 sekundach ciszy. Jeśli kontroler nadaje bez przerwy, dopuszcza ponowną próbę po maksymalnie 15 sekundach od rozpoczęcia oczekiwania.
3. Start aplikacji i odzyskiwanie nieoczekiwanego rozłączenia pomijają oczekiwanie na ciszę; pierwsza reklama zapamiętanego kontrolera może rozpocząć łączenie.
4. Wybrany arbitraż wielu urządzeń nadal ustala dopuszczenie i opóźnienie próby. Ręczne „Rozłącz” wyłącza automatyczne wznawianie.
