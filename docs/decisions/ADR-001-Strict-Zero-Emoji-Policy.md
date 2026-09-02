---
title: ADR-001 Bezwzględny Zakaz Emotikonów
tags:
  - decision
  - adr
---

# ADR-001: Bezwzględny Zakaz Emotikonów (Strict Zero Emoji Policy)

## Status
Zaakceptowana (Obowiązująca)

## Kontekst
Używanie emotikonów w kodzie źródłowym, interfejsie użytkownika, komunikatach wydań (release notes) i commitach wprowadzało niespójności wizualne oraz problemy z renderowaniem na różnych platformach (WinUI 3 vs Compose).

## Decyzja
Wprowadzono bezwzględną zasadę **dokładnie 0 emotikonów** w:
- Kodzie źródłowym i komentarzach,
- Etykietach UI, nagłówkach, tekstach pomocniczych i ikonach tekstowych (zamiast tego stosowane są wektorowe ikony systemowe: Segoe Fluent Icons w Windows, Material Symbols w Androidzie),
- Treściach wydań GitHub i dziennikach zmian,
- Wiadomościach commitów Gita oraz odpowiedziach asystenta.

## Konsekwencje
Czysty, profesjonalny i elegancki wygląd interfejsu aplikacji na obu systemach operacyjnych.
