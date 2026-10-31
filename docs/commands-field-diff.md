# Feldnamenvergleich

`docs/commands.json` und `docs/commands-discord.json` verwenden dieselben neun Feldnamen: `name`, `aliases`, `args`, `description`, `example`, `rank`, `server`, `status` und `group`. Es gibt keine Feldnamenunterschiede. Gleichnamige Befehle auf Minecraft und Discord sind getrennte Eintr?ge; `server` unterscheidet sie.

Das Schema enth?lt keinen Wert `vip` f?r `server`. `all` bei `/spawn` bezeichnet daher den gemeinsamen Eintrag; seine Beschreibung grenzt Lobby, Survival und VIP ausdr?cklich gegen PvP ab. Bei Wirtschaftsbefehlen nennt die Beschreibung die PvP-Sperre. `all` allein ist keine Zusage, dass ein Befehl auf jedem Server funktioniert.
