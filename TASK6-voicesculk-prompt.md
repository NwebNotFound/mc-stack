Arbeitsverzeichnis C:\Users\nilsw\mc-stack. Fasse NUR das Custom-Plugin VoiceSculk an (Quellcode unter plugins-src, z.B. plugins-src\voicesculk; das Jar liegt in config/survival-plugins). NICHT anfassen: plugins-src\craftcore (daran arbeitet parallel ein anderer Codex-Run), bot\ und alle anderen Dateien.
Ziel:
1. Flüstern (Simple Voice Chat Whisper, MicrophonePacketEvent isWhispering bzw. passende API) aktiviert KEINE Sculk-Sensoren.
2. Normales Sprechen aktiviert nur Sensoren innerhalb eines Radius um den Sprecher, Standard 8 Bloecke, in config.yml einstellbar (radius, plus whisper-triggers: false). Gruppen-Voicechat ebenfalls ignorieren (konfigurierbar).
3. Bestehendes Cooldown/Throttling beibehalten, Performance ok.
Bauen, Jar ersetzen, nur den Survival-Container neu starten (docker compose restart survival), Log pruefen, dass VoiceSculk sauber laedt. Ergebnis kurz in STATUS.md unter VoiceSculk eintragen.
