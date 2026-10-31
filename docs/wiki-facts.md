# Wiki-Fakten

- **Adresse:** `mc.nwebnotfound.dev`.
- **Minecraft-Version:** 26.3 laut den vorhandenen `data/lobby/paper-26.3-235.jar` und `data/survival/paper-26.3-235.jar` im Live-Verzeichnis `C:/Users/nilsw/mc-stack/`. Die Compose-Vorgabe in `compose.yaml` ist `LATEST`; die aktuell laufende Version wurde nicht abgefragt.
- **Simple Voice Chat:** 2.6.25 laut `data/survival/plugins/voicechat-bukkit-2.6.25.jar`; f?r Lobby ist kein entsprechendes JAR vorhanden.
- **Whitelist:** In Discord `/setup` f?r die Anleitung und `/link [mc_name]` zum Verkn?pfen nutzen; den Code vom Kick-Bildschirm mit `/verify <code>` eingeben. Die Anleitung l?uft per Direktnachricht. Quellen: `bot/bot.py`, `docs/commands-discord.json`.
- **Spawn:** `/spawn` ist auf Lobby, Survival und VIP verf?gbar und f?hrt zum Lobby-Spawn; auf PvP nicht. Survival-Konfiguration: 10 Sekunden warten und stillstehen. Quellen: `plugins-src/craftcore/src/main/java/de/mcstack/craftcore/Features.java`, `SpawnRoute.java`, `data/survival/plugins/CraftCore/config.yml` im Live-Verzeichnis.
- **CP verdienen:** Tages- und Wochenaufgaben mit `/challenges` ansehen und auf Survival abschlie?en; die Aufgaben geben die jeweilige CP-Belohnung an. Der Einladende erh?lt laut Konfiguration 500 CP, wenn die eingeladene Person die im Code gepr?fte Spielzeit von zwei Stunden erreicht. Spieler k?nnen au?erdem ?ber `/pay` CP ?bertragen und ?ber Shops, Auktionen oder Handel Waren mit anderen Spielern handeln. Der Servermarkt-Ankauf und das Verkaufsmen? sind derzeit deaktiviert; sie sind keine verf?gbare Verdienstquelle. Quellen: `plugins-src/craftcore/src/main/java/de/mcstack/craftcore/Challenges.java`, `InviteRewards.java`, `data/survival/plugins/CraftCore/config.yml`.

Weitere Einzelheiten und Einschr?nkungen stehen in `docs/feature-status.md`.
