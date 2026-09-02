---
title: ADR-004 Dynamiczne Wersjonowanie i Bezkolizyjny Instalator
tags:
  - decision
  - adr
---

# ADR-004: Dynamiczne Wersjonowanie i Bezkolizyjny Instalator

## Status
Zaakceptowana (Obowiązująca)

## Kontekst
Ręcznie wpisane numery wersji w plikach XAML oraz `AppModels.cs` powodowały pętlę aktualizacji, w której nowo zainstalowana aplikacja meldowała starszą wersję i żądała ponownej aktualizacji. Ponadto brak natychmiastowego zamknięcia procesu blokował pliki wykonywalne przed nadpisaniem przez instalator Inno Setup.

## Decyzja
1. `AppInfo.Version` jest pojedynczym źródłem prawdy dla wersji aplikacji.
2. Wszystkie ekrany UI (Ustawienia, O aplikacji) bindują wersję dynamicznie przez `MainViewModel`.
3. Przed uruchomieniem instalatora Inno Setup aplikacja natychmiast kończy działanie za pomocą `Environment.Exit(0)`.

## Konsekwencje
Bezproblemowa, jednoturowa aktualizacja programu bez pętli wywołań i bez blokowania plików.
