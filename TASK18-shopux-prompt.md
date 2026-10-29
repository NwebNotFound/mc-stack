Arbeitsverzeichnis C:\Users\nilsw\mc-stack, Plugin plugins-src\craftcore. bot\ NICHT anfassen. NUR Spielershop-UX. Lies STATUS.md (task13).
Problem: Spieler kann eingestellte Items nicht wieder entfernen; Bedienung unklar.
Befehle (deutsche Meldungen, Tab-Completion, /shop help):
- /shop -> GUI aller Shops (Spielerkoepfe), Klick oeffnet Shop.
- /shop sell <preis> [menge] -> Item in der Hand einstellen; Bestaetigungs-Chatnachricht mit Klick [Rueckgaengig].
- /shop show -> eigener Shop als GUI; Klick auf Angebot: Untermenue Preis aendern / Menge / Entfernen (Item zurueck ins Inventar, voll -> Nachlieferung beim Join). Auch /shop remove <nr>.
- /shop <spieler> -> fremden Shop, Klick -> Kaufbestaetigung mit Preis und Menge.
- Lore in allen GUIs erklaert Klicks (Links = kaufen, Shift = alles, Rechts = Details). Benachrichtigung an Verkaeufer bei Verkauf (auch offline beim naechsten Join).
Escrow/Journal-Sicherheit aus task13 beibehalten. Tests, bauen, Survival+Lobby neu starten, STATUS.md.

Zusaetzlich fuer den Discord-Bot (nur lesend): stabile SQL-Views craftcore.v_shop_listings (listing_id, seller_uuid, seller_name, item_material, item_display_name, amount, price, status ACTIVE/SOLD/REMOVED, created_at, updated_at) und craftcore.v_shop_events (event_id, type CREATED/SOLD/REMOVED/OUT_OF_STOCK, listing_id, seller_name, buyer_name, price, created_at; Tabelle shop_events wird bei jeder Aktion befuellt). Root-Passwort steht in den craftcore-db Logs (GENERATED ROOT PASSWORD); damit GRANT SELECT auf beide Views an 'creepy_ro'@'%'. Passwort nie ausgeben. Schema in STATUS.md unter "Discord-Schnittstelle Shops" dokumentieren.
