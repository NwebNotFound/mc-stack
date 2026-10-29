# Statistik- und Markt-Views

Stand 29.10.2026, Teil A. Alle fünf Views erhalten SELECT für `creepy_ro`.

`v_shop_listings` enthaelt alle offenen Spielerangebote: die bisherigen Spalten bleiben bestehen; `seller_name` ist der Spielername, `unit_price_cents` der auf ganze Cent aufgerundete Preis je Stueck (mindestens 1 Cent), `remaining_quantity` die noch angebotene Stueckzahl. `price_cents` bleibt der bisherige Gesamtpreis des Restangebots in Cent. Details zu den Auktions-Views stehen unten; ihre creepy_ro-Freigabe ist noch offen.

Der Konsolen-/RCON-Aufruf `ah bid <auction_id> <uuid> <betrag_cent> <request_id> [source]` ist implementiert; das bisherige Festpreis-AH bleibt aktiv.

- `v_player_stats_ext`: bisherige Spalten von `v_player_stats`, dazu `distance_m` (Meter), `kd` (Kills geteilt durch mindestens einen Tod), `playtime` (Sekunden, Summe der gespeicherten Backendzeiten) und `details` (JSON-Objekte `blocks_mined`, `distance_cm`, `kills_entity`, `deaths_entity`). Fehlende Zahlen sind 0, fehlende Details `{}`.
- `v_player_stats_detail`: `uuid`, `category`, `key`, `value`; Distanzen hier in Zentimetern.
- `v_leaderboards`: `board`, `period`, `rank`, `uuid`, `name`, `value`; Boards money (CP), playtime (Sekunden), blocks_mined, kills, kd und distance (Meter). `all` ist der aktuelle Wert; `week` ist die Differenz zur ersten gespeicherten Aufnahme dieser Berliner Kalenderwoche. Snapshot täglich und beim Start. Vergangene Wochenanfänge und Werte vor Erfassungsbeginn sind nicht rekonstruierbar.
- `v_market`: `item`, `buy_price`, `sell_price`, `base_price` in CP, `volume_24h_qty`, `volume_24h_cp`, `trades_24h`, `change_24h_pct`, `updated_at` (Unix-Sekunden). Kurswerte werden bei Start und Preisabfrage gespeichert; die 24-Stunden-Änderung vergleicht den Verkaufskurs mit dem Handelsdurchschnitt der Stunde vor 24 Stunden, sonst 0.
- `v_market_history`: `item`, `hour_ts` (Unix-Sekunden, Stundenbeginn UTC), `avg_price`, `min_price`, `max_price` (CP pro Stück), `volume_qty`. Gewichtet nach Stückzahl; enthält nur Stunden mit erfassten Abschlüssen der letzten 90 Tage.

Die live vorhandene `v_market` hat nach `updated_at` zusätzlich `buy_enabled` und `sell_enabled`. Bei einer entsprechenden Sperre aus `market_locks` ist der jeweilige Preis NULL. Der lokale Quellstand erhält diese Spalten beim nächsten Anlegen der View; die Synchronisierung der Sperren durch das Plugin ist im B2-Teilstand noch nicht implementiert.

`market_events` ist die einzige Quelle für Umsatz und Historie und wird bei Abschlüssen transaktional geschrieben (`shop`, `bank`, `ah`, `order`); AH erfasst den Kaufpreis vor Gebühr. Die Tabellen `sale_notices`, `shop_events` und alte `bank_journal`-Zeilen werden nicht aggregiert, sodass diese Abschlüsse nicht doppelt gezählt werden. Bankjournal-Bestände ohne Zeitpunkt lassen sich nicht zeitlich rekonstruieren. Der Scheduler berechnet ab Start stündlich die Aggregate und täglich Snapshots; historische Daten vor dieser Erfassung fehlen. Authentifizierte Spieltests sind noch offen.

Live-Stand: alle fünf Views mit `creepy_ro` gelesen; INSERT und UPDATE auf jeder View abgelehnt. Einschränkung: das übernommene `updated_at` kann bei fehlender Statistik NULL sein. Die Wochenbasis entsteht beim ersten Joblauf nach dem Wochenwechsel und kann bis zu einer Stunde später liegen.


## Gebotsauktionen

`v_auctions` enthaelt `id` (numerische Auktions-ID), `item` (Material), `menge` (Stueck), `seller` (UUID), `min_price`, `current_bid` und `min_next_bid` (ganze Cent), `current_bidder` (UUID oder NULL), `bid_count` (Anzahl), `ends_at` und `created_at` (Unix-Millisekunden), `status` und `source_last_bid`. `v_auction_bids` enthaelt `id`, `auction_id`, `bidder` (UUID), `amount` (Cent), `created_at` (Unix-Millisekunden) und `source` (`ingame`, `discord`, `web`). Die Rechte fuer `creepy_ro` sind noch nicht gesetzt.

Der Befehl `ah bid <auction_id> <uuid> <betrag_cent> <request_id> [source]` liefert `OK ...` oder `ERR <code>`. `source` ist standardmaessig `ingame`; Spieler duerfen nur ihre eigene UUID und `ingame` angeben. Wiederholung mit identischen Parametern liefert dieselbe Antwort; abweichende Parameter mit demselben Schluessel liefern `ERR DUPLICATE`. `/ah auction <mindestpreis_CP> <dauer>`, z. B. `/ah auction 10.00 6h`, stellt den gehaltenen Stapel ein. Beim Ausfall zwischen Datenbankvormerkung und Speichern des Inventars bleibt die Auktion zur manuellen Klaerung in `PENDING`; sie wird nicht automatisch ausgeliefert.
