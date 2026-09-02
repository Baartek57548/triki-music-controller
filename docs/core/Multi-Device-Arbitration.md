---
title: Arbitraż Wielu Urządzeń (Multi-Device Arbitration)
tags:
  - core
  - arbitration
---

# Arbitraż Wielu Urządzeń (Multi-Device Arbitration)

Funkcja **Multi-Device Arbitration** umożliwia inteligentne współdzielenie pojedynczego kontrolera Triki pomiędzy komputerem Windows a telefonem Android w tym samym pomieszczeniu.

Powiązane węzły:
- [[Settings-Architecture]] — wybór trybu arbitrażu w Ustawieniach.
- [[BLE-Protocol]] — zarządzanie sesjami połączeń GATT.
- [[System-Overview]] — architektura systemowa.

---

## Tryby Działania

1. **Priorytet Aktywnej Muzyki (`MediaPriority` - Zalecany)**:
   - Urządzenie (komputer lub telefon), na którym aktualnie odtwarzany jest dźwięk, ma pierwszeństwo do utrzymywania połączenia z kontrolerem Triki.
   - Drugie urządzenie przechodzi w stan uśpienia i łączy się dopiero, gdy pierwsze zatrzyma odtwarzanie.

2. **Zawsze Łącz (`AlwaysConnect` - Agresywny)**:
   - Urządzenie niezwłocznie łączy się z kontrolerem przy każdej okazji.

3. **Tylko Podczas Odtwarzania (`OnlyWhenPlaying`)**:
   - Urządzenie łączy się z kontrolerem wyłącznie wtedy, gdy lokalnie trwa odtwarzanie multimediów.
