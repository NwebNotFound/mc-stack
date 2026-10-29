# Befehlsinventar eigener Plugins

Stand: 28.10.2026. Eckige Klammern bezeichnen optionale Eingaben. Andere installierte Plugins und Velocity-Befehle sind hier nicht inventarisiert. Der UX-Ausbau ist ein Teilstand; siehe STATUS.md.

## CraftCore (Lobby und Survival)

| Befehl | Funktion |
|---|---|
| `/menu` | Zentrales Menü |
| `/help [handel\|teleport\|team\|welt]` | Kategorien und klickbare Hilfe, soweit auf dem Backend aufgerufen |
| `/balance`, `/bal` | CP-Kontostand |
| `/pay`, `/pay <Spieler> <Betrag>` | CP senden; bekannte Spieler auch serverübergreifend |
| `/team`, `/team create <Name>`, `invite <Spieler>`, `join <Name>`, `leave`, `kick <Spieler>`, `info`, `promote <Spieler>`, `demote <Spieler>` | Teams verwalten |
| `/claim`, `/claim create|list`, `/unclaim` | Chunk beanspruchen, Claims ansehen oder Chunk freigeben |
| `/spawn` | Nach Wartezeit zum Lobby-Spawn |
| `/home`, `/home <Name>`, `/sethome [Name]` | Home nutzen oder setzen; nur Survival |
| `/bank`, `/money`, `/market [Suchbegriff]` | Markt und Preise; money ist Alias von bank |
| `/shop [Spieler]`, `/shop help`, `/shop sell <Preis> [Menge]`, `/shop show`, `/shop cancel`, `/shop remove <Nr>` | Spielershops; `/shop show` öffnet die Bearbeitung eigener Angebote per Klick; `undo <ID>` für konkrete Rücknahme-Links |
| `/ah`, `/ah my`, `/ah sell <Preis>`, `/ah search <Suchbegriff>` | Auktionshaus |
| `ah bid <auction_id> <uuid> <betrag_cent> <request_id> [source]` | Konsole/RCON: derzeit `ERR AUCTION_UNAVAILABLE`, Gebotsfunktion noch nicht implementiert |
| `/trade`, `/trade <Spieler>`, `/trade accept` | Handel anfragen oder annehmen |
| `/warp`, `/vip` | Menues mit Hinweis: Funktionen noch nicht eingerichtet |
| `/npc set bank\|ah` | Lobby-NPC setzen; Berechtigung `craftcore.npc.set` (standardmäßig OP) |

## Weitere eigene Plugins

| Plugin | Befehle |
|---|---|
| ParkourBridge (Lobby) | `/parkour leave`, `/parkour setboard` (OP) |
| LobbyJoinSpawn (Lobby) | `/setlobbyspawn` (OP) |
| VoiceSculk | Keine eigenen Befehle |
| VipWorldsAFK | Keine eigenen Befehle |

Ein `/tpa`-System existiert nicht. Die bisherigen Grenzen und Risiken insbesondere von Markt, Shop, Auktionshaus, Inventaren und Datenbank stehen in STATUS.md. Die Auflistung beschreibt vorhandene Funktionen, keine Zusicherung ihrer Absturzsicherheit.

Die argumentlosen Hauptbefehle oeffnen GUIs; `/menu` buendelt Bank, Shop, AH, Homes und Warps. Bei Eingaben oeffnet der Menuepunkt einen anklickbaren Chatvorschlag. Unbekannte aehnliche Hauptbefehle und Team-Unterbefehle erhalten einen Tippfehlervorschlag. `/claim create` beansprucht den aktuellen Chunk.
