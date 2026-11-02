# Kino: HLS-Plan (nur Planung)

Status: **Entwurf / Planung**. Dieses Dokument enthaelt **keinen Code und keine Implementierung**. Es beschreibt, wie lokale Filme per ffmpeg zu HLS transkodiert, an Clouds Admin gemeldet und zum Player ausgeliefert werden sollen. Nichts davon ist deployt.

## 1. Verzeichnislayout

Alles liegt ausserhalb des Repos, damit keine Filme, Originale oder Segmente in Git landen:

```
C:\Users\nilsw\kino\
  filme\      # Eingang: Originale (mkv/mp4/...), werden NICHT veraendert oder geloescht
  hls\        # Ausgabe: pro Film ein Unterordner mit master.m3u8 + Varianten
  trailer\    # Kurze Vorschau-Clips (WebM/MP4) fuer die Listenansicht
```

Beispiel-Ausgabe:

```
hls\<filmId>\master.m3u8
hls\<filmId>\1080p\index.m3u8 + seg_00000.ts ...
hls\<filmId>\720p\index.m3u8  + ...
hls\<filmId>\480p\index.m3u8  + ...
hls\<filmId>\subs\de.vtt, subs\en.vtt ...
```

`<filmId>` ist ein stabiler Slug (z. B. `sha256`-Kurzform der Datei oder aus Metadaten abgeleitet), damit ein erneuter Lauf denselben Ordner trifft und der Job idempotent bleibt.

## 2. Watcher-Dienst auf `kino\filme`

- Laeuft als Container (Compose-Profil `kino`) **oder** als Windows-Dienst mit Autostart. Entscheidung faellt nach dem Homelab-Umzug; Container ist fuer den Umzug vorzuziehen.
- Ueberwacht `kino\filme` auf neue Dateien. Neue Datei erst **nach stabiler Dateigroesse** (z. B. unveraendert ueber 2 Minuten, zwei Messungen) verarbeiten, damit halb kopierte Dateien nicht angefasst werden.
- **Queue mit genau einem gleichzeitigen Job.** Weitere Dateien warten. Kein paralleles Transcoding, damit Gaming nicht ausgebremst wird.
- Transcoding mit ffmpeg nach HLS:
  - Varianten 1080p / 720p / 480p, je eigenes Playlist-File, oben ein `master.m3u8`.
  - Segmentlaenge **6 s**, `-hls_playlist_type vod`, Segmente als `.ts` (oder fMP4, TBD).
  - Encoder: **NVENC** wenn verfuegbar (`h264_nvenc`), sonst Fallback **x264** (`libx264`, `-preset veryfast`).
  - Untertitel: alle vorhandenen Spuren extrahieren und als **WebVTT** ablegen, im Master als `SUBTITLES`-Rendition verlinkt.
- **Idempotenz:** existiert `hls\<filmId>\master.m3u8` bereits und passt die Quelldatei (Groesse/Hash), wird der Job uebersprungen. Fertige Jobs werden in einem kleinen Status-File (JSON, ausserhalb von Git) vermerkt.
- **Fehler-Log:** pro Job eine Logdatei unter `kino\logs\`, plus Sammellog. Fehlgeschlagene Jobs landen in einer Retry-Liste, werden aber nicht automatisch endlos wiederholt.
- **Originale werden nie geloescht oder verschoben.** Der Watcher liest nur.

## 3. Meldung an Clouds Admin

Nach erfolgreichem Transcoding meldet der Watcher den fertigen Film an Clouds Admin (Website/Worker-Seite).

- Endpoint und Schema: **TBD**. Falls `docs/kino-plan.md` existiert, wird das dort definierte Schema uebernommen; sonst muss es mit dem cloudflare-Bot abgestimmt werden.
- Vorlaeufige Payload-Idee (nicht final): `filmId`, `titel`, `masterPath`/`masterUrl`, `varianten` (Aufloesungen), `untertitel` (Sprachcodes), `dauerSek`, `status`.
- **Auth per Shared-Secret aus `.env`** (Header, z. B. `Authorization: Bearer <secret>`). Das Secret wird nie im Repo, in Logs oder in Meldungen ausgegeben.
- Die Meldung ist wiederholbar (idempotent): gleiche `filmId` aktualisiert den Eintrag, statt Duplikate anzulegen. Fehlschlagende Meldungen werden mit Backoff erneut versucht.

## 4. Auslieferung

### Plan A: Cloudflare R2

- Aktuell geplante Filme werden nach R2 hochgeladen, Segmente direkt aus dem Bucket ausgeliefert (Public Bucket oder Custom Domain).
- R2 hat **10 GB Gratis-Limit**; alles darueber ist kostenpflichtig. Deshalb: automatische Entfernung alter Filme (Rotation) nach festem Kontingent.
- **Zahlungsmethode bei Cloudflare ist noetig**, sobald das Gratis-Limit realistisch ueberschritten werden kann — das ist ein Kostenrisiko und braucht Nils' Entscheidung.
- **Aktivierung erst nach ausdruecklichem OK von Nils.** Bis dahin bleibt Plan A nur Planung.
- Vorteil: kein Heim-Upload zum Client. Nachteil: Kosten und Upload-Bandbreite vom Heimanschluss.

### Plan B: lokaler Static-Server hinter cloudflared-Tunnel

- Ein einfacher Static-Server (nginx oder vergleichbar) liefert `hls\` aus, **nur gebunden an 127.0.0.1**. Keine Portfreigabe in der Fritzbox, die Heim-IP bleibt verborgen.
- Erreichbar ausschliesslich ueber einen **ausgehenden cloudflared-Tunnel**.
- Segmente gehen **direkt** zum Client (nicht durch den Worker) — der Worker proxied keinen Videotraffic.
- Zugriff ueber **kurzlebige, HMAC-signierte URL** vom Worker: der Worker signiert Pfad + Ablaufzeit, der Origin prueft die Signatur und lehnt abgelaufene oder manipulierte URLs ab.
- Signatur-Secret nur in `.env`, nicht im Repo. Kurze Gueltigkeit (Minuten), damit URLs nicht geteilt/weiterverwendet werden koennen.
- Nachteil: Bandbreite und Verfuegbarkeit haengen am Heimanschluss und am PC-Nils.

## 5. Secrets

Alle Secrets (HMAC-Key, Shared-Secret fuer Clouds Admin, R2-Zugangsdaten, ggf. Tunnel-Token) liegen ausschliesslich in `.env` bzw. `secrets/`. Sie werden **nie** committet, nicht geloggt, nicht in PR-Texte oder Chats geschrieben. `.env`, `secrets/`, `data/` und `backups/` bleiben wie bisher ignoriert.

## 6. Offene Fragen und Risiken

- **Upload-Bandbreite:** Wie lange dauert der Upload groesserer Bibliotheken (Plan A) bzw. wie stark leidet der Heimanschluss waehrend des Spielens?
- **CPU/GPU-Last waehrend Gaming:** NVENC entlastet die CPU, teilt sich aber die GPU mit Spielen. Wann darf transkodiert werden (Zeitfenster, Pause-Erkennung)?
- **Cloudflare-ToS fuer Video:** Ob und in welchem Umfang Video-Streaming ueber R2/Worker/Cache ToS-konform ist, muss geprueft werden, bevor Plan A aktiviert wird.
- **Kosten:** R2 ueber 10 GB sowie Traffic-Kosten — ohne Nils' OK keine Aktivierung.
- **Schema-Abhaengigkeit:** Solange kein `docs/kino-plan.md` mit API-Schema existiert, bleibt Abschnitt 3 offen.
- **Homelab-Umzug:** Der Watcher muss mit dem geplanten Umzug auf den i5/Mini-PC portierbar bleiben (Kompose/Compose-Profil, Pfade konfigurierbar).
