[English](README.md) · **Deutsch** · [Русский](README.ru.md)

# Voice-to-Messenger

Einsprechen – und eine fertige Nachricht erhalten. Die App verwandelt spontane Sprache in sauberen,
versandfertigen Text (Wahl des Tons, eigener Stil auf Basis früherer Nachrichten, Emojis,
Formatierung als Liste, optionale Übersetzung) mit 2–3 Fingertipps:
diktieren → **Kopieren / Teilen / Als Notiz speichern**.

Das Hauptproblem, das das Produkt löst: keine langen Nachrichten mehr von Hand tippen und keine Sprachnachrichten
schicken – aber auch nicht mit ChatGPT kämpfen, das der Antwort überflüssige Sätze hinzufügt.

## Struktur

- **`backend/`** – ein schlanker Flask-Proxy: verwaltet die API-Schlüssel, wendet System-Prompt und Stile an
  und wird über ein Web-Panel gesteuert. LLM-Anbieter (OpenAI-kompatibel und Anthropic)
  lassen sich ohne Codeänderung wechseln; jeder hat sein eigenes Modell für die Textverarbeitung und sein eigenes
  Modell für die Spracherkennung, und das Panel schlägt ausgewählte Varianten mit Preisen vor – jede
  Liste ist mit genau dem Aufruf geprüft, für den sie angeboten wird, damit eine Auswahl im Panel nicht
  zu einem Fehler beim Anbieter führt.
  Spracherkennung auf dem Gerät oder per serverseitigem Whisper (`/api/transcribe`).
  Jede Anfrage an einen Anbieter wird in ein Verbrauchsprotokoll geschrieben: wie viele Tokens und Sekunden Audio
  sie gekostet hat (vorerst nur zur Beobachtung, nichts wird abgerechnet).
- **`android/`** – eine native App (Kotlin + Jetpack Compose): Diktierbildschirm,
  Live-Übersetzung, Notizen, Einstellungen und eine schwebende Blase über Messengern. Zwischen den Bildschirmen
  wird über eine untere Leiste gewechselt, die in der ganzen App gleich ist. Die Oberfläche gibt es auf Russisch,
  Deutsch und Englisch. Ein einzelnes Diktat ist auf fünf Minuten begrenzt: Ist das Limit
  erreicht, stoppt die Aufnahme von selbst und fragt, ob gesendet oder gelöscht werden soll.
  Dazu ein verstecktes Admin-Panel: dieselben Backend-Einstellungen lassen sich direkt am Telefon ändern,
  ein Reiter für den Verbrauch und ein Spielebereich (2048, Schulte-Tabellen, Stroop-Test), um die Hände
  zu beschäftigen, während das Modell nachdenkt.

## Live-Übersetzung

Ein eigener Bildschirm: Das Mikrofon hört dem Gesprächspartner zu und übersetzt in die gewählte Sprache – als Text
auf dem Bildschirm und als Sprache im Kopfhörer. Die Sprache des Sprechers erkennt das Modell, sie wird nicht
manuell eingestellt; gewählt wird nur die Sprache, **in** die übersetzt wird (Russisch, Deutsch,
Englisch, Italienisch, Chinesisch).

Es gibt zwei Modi, umschaltbar im Admin-Panel mit einer Schaltfläche (über das Erkennungsmodell):

```
drei Modelle: Mikrofon (PCM 16 kHz) → WebSocket /api/live → Voxtral Realtime (Erkennung,
    erkennt die Sprache selbst) → Textstücke → LLM-Übersetzung jedes Satzes → Voxtral TTS → Kopfhörer

ein Modell: Mikrofon → /api/live → gpt-realtime-translate (hört, übersetzt und spricht
    selbst) → Text als Laufband und durchgehender Ton im Kopfhörer
```

Drei Modelle sind günstiger und zeigen im Laufband das Original; ein Modell ist schneller und übersetzt zusammenhängender,
wird aber minutenweise abgerechnet, solange die Sitzung offen ist.

Was die Kosten begrenzt: Stille wird nicht an das Modell gesendet (Lautstärke-Schwelle auf dem
Gerät), die Sitzung schließt sich nach dem im Admin-Panel festgelegten Limit von selbst (standardmäßig
10 Minuten), und wird bereits in der Zielsprache gesprochen, wird die Übersetzung gar nicht aufgerufen. Der Verbrauch
wird im selben Protokoll mit drei Zeilen pro Sitzung erfasst: `live_stt`, `live_translate`, `live_tts`.

Einschränkung: Die Übersetzung läuft nur, solange der Bildschirm geöffnet ist. Im Hintergrund entzieht das System
der App das Mikrofon, deshalb bleibt der Bildschirm während der Sitzung an.

## Architektur

```
[Android] Mikrofon → ASR (auf dem Gerät / serverseitiges Whisper) → Text
        → Schalter „Lokal / Server" → fertiger Text → Kopieren / Teilen / Notiz
        ↳ die Blase über anderen Apps nimmt denselben Weg
        ↳ Live-Übersetzung: Mikrofon → WebSocket → Text und Sprache im Kopfhörer
[Backend] /api/transcribe (Whisper) · /api/process (LLM + System-Prompt)
        · /api/live (Live-Übersetzung, WebSocket) · /api/options (Stile und Sprachen)
        · /admin (Panel) · /admin/api/* (dasselbe Panel vom Telefon aus)
        ↳ jede Anfrage an einen Anbieter → Verbrauchsprotokoll (Tokens und Sekunden Audio, roh)
```

Details in `backend/README.md` (auf Russisch). Der Entwicklungsplan steht im freigegebenen Designdokument.
