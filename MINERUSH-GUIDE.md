# MineRush – Stand und Anleitung

MineRush selbst ist noch nicht implementiert: Es gibt keine MineRush-Welten, Runs, Parties, Raeume, Bosse oder Challenges. Ein eigenstaendiger Loot-Tabelleneditor ist auf Survival vorhanden; die Tabellen werden noch nicht von Kisten oder Gegnern verwendet.

## Loot-Tabellen (Survival, OP)

1. `/mr loot edit beispiel` oeffnet die Tabelle. Erlaubt sind Buchstaben, Zahlen, `_` und `-` (maximal 32 Zeichen).
2. Ein Item im eigenen Inventar anklicken: Es wird samt Metadaten kopiert, das Original bleibt erhalten. Seine Stapelgroesse wird als feste Menge uebernommen.
3. In der oberen Ansicht aendern Links-/Rechtsklick das Gewicht um 1, Shift-Rechts wechselt die Seltenheit, Shift-Links entfernt den Eintrag. Die Ansicht hat Platz fuer 54 Eintraege. Aenderungen werden sofort gespeichert.
4. Die Datei liegt unter `data/survival/plugins/CraftCore/loot/beispiel.yml`. Dort koennen `min` und `max` fuer einen Mengenbereich angepasst werden (1 bis zur maximalen Stapelgroesse). Gewicht muss zwischen 1 und 1000000 liegen. `rarity` ist `GEWOEHNLICH`, `UNGEWOEHNLICH`, `SELTEN`, `EPISCH` oder `LEGENDAER`. Das Item ist als Paper-ItemStack mit Metadaten serialisiert. Vor manuellen Aenderungen den Editor schliessen; ungueltige Dateien werden nicht geoeffnet. Seltenheit ist derzeit nur eine Kennzeichnung; die spaetere Auswahl muss die Gewichte auswerten.

Der Editor wurde noch nicht mit einem angemeldeten Client getestet. `/mr reload` und die uebrigen Editor-Funktionen gibt es noch nicht; die Loot-Datei wird beim Oeffnen neu eingelesen.

Im Quellcode existieren vorbereitende, isolierte Regeln fuer Raumvalidierung und Anschlussrotation. Sie sind nicht an Paper, eine Speicherung oder einen Generator angebunden und stehen im Spiel nicht zur Verfuegung. Builder-Rechte fuer `minerush_build` wurden noch nicht eingerichtet.

Die Lobby-NPCs sind ein unabhaengiger Teilstand: Als OP in der Lobby an die gewuenschte Position stellen und `/npc set bank` oder `/npc set ah` ausfuehren. Die NPCs werden in der CraftCore-Konfiguration des Lobby-Servers gespeichert. Rechtsklick oeffnet eine Vorschau des bestehenden Markts bzw. Auktionshauses; Kategorien und Seiten sind benutzbar, Handel ueber diese Vorschau ist wegen der nicht absturzsicheren Inventar-/Datenbank-Auslieferung gesperrt. Die bestehenden Befehle und deren dokumentierte Risiken bleiben unveraendert. Die Ingame-Funktion ist noch nicht mit einem Client geprueft.
