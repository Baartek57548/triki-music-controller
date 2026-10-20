# Triki Music Controller v3.1.8

Data: 2026-10-20. Windows 3.1.8.0, Android versionCode 48.

- Wydłużono czas bezczynności w trybie Eco z 12 sekund do 2 minut w Windows i Androidzie. Ruch lub przycisk odnawia ten czas.
- Usunięto blokadę ponownego połączenia przy ciągłym nadawaniu reklam BLE przez kontroler.
- Start aplikacji i odzyskiwanie zerwanego połączenia przyjmują pierwszą dostępną reklamę zapamiętanego Triki, z zachowaniem wybranego arbitrażu wielu urządzeń.
- Tylko celowe uśpienie Eco czeka na 5 sekund ciszy w reklamach. Jeśli kontroler nadaje bez przerwy, po maksymalnie 15 sekundach zostaje dopuszczona automatyczna próba połączenia.
- Ręczne „Rozłącz” nadal zatrzymuje automatyczne łączenie do kolejnej decyzji użytkownika.
- Uaktualniono opisy ustawień i dodano testy ciągłych reklam, granic timeoutu, startu i ręcznego rozłączenia.

Połączenie wymaga dostępności kontrolera; urządzenie połączone z innym hostem może pozostawać niedostępne.

Powiązania: [[Releases-Overview]], [[System-Overview]], [[BLE-Protocol]].
