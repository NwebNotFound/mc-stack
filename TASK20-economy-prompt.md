Arbeitsverzeichnis C:\Users\nilsw\mc-stack, Plugin plugins-src\craftcore. bot\ NICHT anfassen. Lies STATUS.md (task12, 13, 17, 18).
1. Bank-Balance (CP-Inflation verhindern, alles in config):
   - Verkaufspreis an die Bank = 25% des Basiswerts, Kaufpreis von der Bank = 150% des Basiswerts (grosser Spread).
   - Abnehmender Ertrag: pro Spieler und Item sinkt der Verkaufspreis mit der Tagesmenge (z.B. nach 64 Stueck -50%, nach 256 -80%), Tageslimit pro Item, Reset 05:00.
   - Dynamische Preise bleiben, aber mit Untergrenze/Obergrenze (20%-300% Basis) und langsamer Erholung.
   - Leicht farmbare Items (Cobblestone, Dirt, Netherrack, Rotten Flesh, Kelp usw.) sehr niedrig oder unverkaeuflich.
   - Basiswerte allgemein ca. halbieren; Start bleibt 0 CP.
2. Auktionshaus /ah als zweiter, spielerbasierter Markt: ALLE Items inkl. NBT/Komponenten (verzauberte Items, Verzauberungsbuecher, Traenke, benannte/beschaedigte Items, Shulker mit Inhalt, Karten, Koepfe) werden als vollstaendige serialisierte ItemStacks (Paper serializeAsBytes) gespeichert und 1:1 ausgeliefert. Befehle /ah, /ah sell <preis>, /ah my (eigene Angebote, entfernen), Suche, Kategorien, Laufzeit 48h, Ablauf -> zurueck an Verkaeufer. 5% Verkaufsgebuehr als CP-Senke. Journal/Escrow-Muster wie Shops. Auch die Spielershops (/shop) sollen NBT voll erhalten (pruefen/fixen) und verzauberte/beschaedigte Items erlauben.
3. Discord-Views: v_ah_listings analog zu v_shop_listings, GRANT SELECT an creepy_ro (Root-Passwort aus craftcore-db Logs, nie ausgeben).
Tests (NBT-Roundtrip fuer verzauberte Buecher, Traenke, Shulker), bauen, Lobby+Survival neu starten, STATUS.md.

UX-VORGABE fuer alle neuen Features (/ah, /bounty, /challenges, /season, /pay, /trade, /bank): Hauptbefehl ohne Argumente oeffnet GUI mit allem (Kaufen, Verkaufen per Item reinlegen + Preis per Amboss-/Schild-Eingabe, eigene Angebote verwalten). Befehle nur als Abkuerzung. Klickbare Bestaetigungen, Tab-Completion, klare Fehlermeldungen mit Loesungsvorschlag, Lore-Hilfe in GUIs.
