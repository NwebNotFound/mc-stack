# Kino: HLS-Pipeline und Auslieferung (Planung)

Status: **Entwurf / Planung, überarbeitet nach Review.** Dieses Dokument enthält **keinen Code und keine Implementierung**. Es beschreibt, wie lokale Filme per ffmpeg vollautomatisch zu HLS transkodiert, signiert ausgeliefert und an Clouds Admin gemeldet werden. Nichts davon ist deployt.

Bezug: Vertrag und Datenmodell stehen in [NwebNotFound/NwebNotFound#48, `docs/kino-plan.md`](https://github.com/NwebNotFound/NwebNotFound/pull/48) (§1.4 Ablage und Leiter, §1.5 Signaturen, §5.2 Origin-Agent, §5.4 Admin). Dieses Dokument ist die **PC-/Origin-Seite** dazu: Watcher, Encoder, Proxy, Aufräumen. Bei Abweichungen gilt [Abschnitt 1.3](#13-abweichungen-zu-48). Nils' einziger Eingriff im Betrieb ist, eine Datei in `kino\filme` abzulegen — alles danach läuft ohne Klick.

---

## 1. Ordner, Identität, Abweichungen

### 1.1 Verzeichnislayout

Alles liegt außerhalb des Repos, damit keine Filme, Originale oder Segmente in Git landen:

```
C:\Users\nilsw\kino\            # <kino-root>, per .env überschreibbar
  filme\      # Eingang: Originale, Nils legt ab. Wird NIE verändert, verschoben oder gelöscht.
  hls\        # Ausgabe: ein Ordner pro file_hash
  trailer\    # optional, lokale Trailer als Werbe-Fallback (#48 §3). Leer ist gültig.
  logs\       # Job-Logs und Sammellog, ohne Secrets, ohne signierte URLs
  state\      # Queue, Status, Attempts, Storage-Index (JSON, klein)
```

Beispiel-Ausgabe:

```
hls\<file_hash>\master.m3u8
hls\<file_hash>\1080p\index.m3u8 + seg_00000.ts ...
hls\<file_hash>\720p\index.m3u8  + ...
hls\<file_hash>\480p\index.m3u8  + ...
hls\<file_hash>\1080p-hevc\index.m3u8 ...     # optional, nur wenn GPU und Quelle es hergeben
hls\<file_hash>\subs\de.m3u8, subs\de.vtt, subs\en.m3u8 ...
hls\<file_hash>\ready.json
hls\<file_hash>\source.json                   # file_name, rel_path, file_hash, size, mtime, built_at
```

### 1.2 Zwei Kennungen, klar getrennt

- **`file_hash` = sha256 über den Dateiinhalt.** Das ist der Schlüssel des Watchers, der Ordnername unter `hls\` und der Idempotenz-Schlüssel überall. Er entsteht ausschließlich auf dem PC.
- **`film_id` = ULID.** Die vergibt **der Worker** beim Ingest und gibt sie in der Antwort zurück (#48 §1.2). Der Watcher erfindet keine ID, fragt keine ab und benennt nichts um: der Ordner heißt immer `hls\<file_hash>\`, die Zuordnung `film_id → file_hash → rel_path` liegt in D1. Damit gibt es kein Mapping von Hand und keinen zweiten Namensraum.
- `rel_path` ist immer relativ zu `kino\filme` (`filme/Der Film (2024).mkv`), nie absolut, nie mit Host, nie mit der Heim-IP.
- Vollständiger Hash: Lesen in 4-MB-Blöcken mit SHA-256 liegt auf einer NVMe bei ~1–2 GB/s, ein 8-GB-Film braucht also wenige Sekunden. Das passiert einmal pro Datei, nicht pro Lauf (Größe + mtime als Vorfilter, dann Hash).

### 1.3 Abweichungen zu #48

| Punkt | #48 (Cloud/Worker) | Dieses Dokument (Origin) | Grund |
| --- | --- | --- | --- |
| Ordnername | `hls\<film_id>\` (ULID) | `hls\<file_hash>\` | Der Watcher kennt die `film_id` beim Encodieren noch nicht; der Hash ist vor dem Ingest bekannt und kollisionsfrei. Die D1 hält die Brücke (#48 bekommt dafür `file_hash` und `rel_path` in `kino_files`). |
| Segmentlänge | 4 s | **6 s** | Weniger Requests, gleiche Latenz im Saal. Cloud passt #48 §1.4 auf 6 s an. |
| Leiter | 1080p 4,5 / 720p 2,5 Mbit/s | **1080p ~6 / 720p ~3 / 480p ~1,2 Mbit/s** (Maxrate) | Nils' Vorgabe; 480p kommt neu dazu (schwaches Mobilnetz). `BANDWIDTH` im Master folgt der Maxrate. |
| Audio | AAC-LC 128k | **AAC-LC Stereo 160k**, optional 5.1 | Nils' Vorgabe; 5.1 bleibt opt-in. |
| Container | `.ts` | `.ts` für H.264, fMP4/CMAF nur für HEVC/AV1 | `.ts` ist für hls.js und Safari unkritisch; HEVC/AV1 sind reine Zusatzspuren für können-de-Browser und brauchen CMAF. |
| Untertitel | WebVTT extrahieren | Wie #48, aber Bild-Untertitel werden **übersprungen und gemeldet** | PGS/VobSub lässt sich nicht nach WebVTT wandeln. |
| Ingest | Schema in §5.2/§5.4 | Endpoint + HMAC in Abschnitt 4 | Ersetzt das „TBD" des ersten Entwurfs. |
| `ready.json` | §5.2, vom Encoder geschrieben | Gleich, plus `source.json` | Der Scan des Agenten liest beides. |

---

## 2. Watcher

### 2.1 Betriebsmodell

- **Autostart beim Booten:** Docker-Container mit `restart: unless-stopped` im Compose-Profil `kino`, oder Windows-Dienst mit Autostart. Container ist für den späteren Homelab-Umzug vorzuziehen (i5/Mini-PC, gleiche Pfade als Volume). Fehlt der Mount, beendet sich der Dienst mit klarer Meldung, statt still nichts zu tun.
- **Selbstheilung:** `restart: unless-stopped` plus `healthcheck` im Container (auf `/healthz`, alle 30 s, 3 Fehlschläge → Neustart), zusätzlich ein geplanter Task auf dem Host, der bei fehlendem Healthcheck neu startet. Ein Absturz mitten im Job ist eingeplant: der Job läuft beim nächsten Start aus dem Zustand weiter (siehe 2.4).
- **Genau ein gleichzeitiger Job.** Weitere Dateien warten in der Queue. NVENC und Spiel teilen sich die GPU, zwei Encoder gleichzeitig sind der schnellste Weg zu Rucklern im Spiel.
- **Lock:** eigenes `Local\KinoWatcher`, damit nicht zwei Instanzen (Dienst + Handstart) dasselbe Verzeichnis bearbeiten. Das Wartungs-Mutex `Local\MCStackMaintenance` bleibt unberührt.

### 2.2 Erkennung neuer Dateien

1. `kino\filme` rekursiv scannen, Endungen `mkv, mp4, m4v, avi, ts, m2ts, mov, webm`. `.part`, `.tmp`, `.crdownload` und versteckte Dateien ignorieren.
2. **Stabile Größe:** Größe und mtime müssen über zwei Messungen im Abstand von 2 Minuten unverändert sein. Sonst weiter warten.
3. **Mindestgröße** 200 MB und **ffprobe-Validierung** (mindestens ein Videostream, Dauer > 60 s). Kaputte Datei → Status `failed` mit Grund, **nicht** in eine Schleife.
4. Danach `file_hash` bilden, Job in `state\queue.json` anlegen, `attempts` auf 0.

### 2.3 Kein Eingriff nötig

Kein „Film bereit"-Knopf, kein Kopieren von IDs, kein Konfigurieren pro Film. Erkennung von HDR, Seitenverhältnis, Tonspuren und Sprache kommt automatisch aus `ffprobe`. Was automatisch nicht geht (Bild-Untertitel, unbekannte Sprache, kein NVENC), wird **automatisch entschieden und protokolliert**, nicht zurückgefragt.

### 2.4 Idempotenz, Crash-Sicherheit, Retry

- Schlüssel ist **`file_hash`**, nie der Dateiname. Eine umbenannte Datei ist derselbe Job; gleicher Name mit anderem Inhalt ist ein neuer Job.
- Encoding läuft nach `hls\<file_hash>.tmp\` und wird erst am Ende **atomar** nach `hls\<file_hash>\` umbenannt (Rename im selben Volume). `ready.json` entsteht als letztes innerhalb von `.tmp`, damit ein abgebrochener Lauf nie als fertig gilt.
- Existiert `hls\<file_hash>\ready.json` und stimmen `file_hash` und `size` in `source.json`, wird der Job ohne Arbeit übersprungen.
- `.tmp`-Reste vom letzten Absturz werden beim Start erkannt und entfernt — ausschließlich innerhalb `hls\*.tmp`, **nie** Originale.
- Retry-Liste mit **`attempts`-Zähler**, exponentieller Backoff (1, 5, 15 min), maximal 3 Versuche. Danach `failed`, Discord-Hinweis, Datei bleibt liegen. Kein endloses Wiederholen.
- Jobstatus in `state\jobs\<file_hash>.json`: `queued | hashing | transcoding | uploading | ready | failed`, `attempts`, `error`, `started_at`, `finished_at`, `last_progress_pct`. Nach einem Neustart läuft die Queue sauber weiter.

### 2.5 Logs

`kino\logs\<file_hash>.log` plus `kino\logs\watcher.log`. Regeln: keine Secrets, keine signierten URLs, keine Tokens, keine vollständigen ffmpeg-Kommandozeilen mit Header-Werten. ffmpeg-Stderr wird auf Fehlerzeilen gefiltert, Rotation bei 20 MB, 14 Tage Aufbewahrung.

---

## 3. Transcoding

### 3.1 Leiter und Qualitätsziele

| Variante | Auflösung | Video-Bitrate (VBR, Ziel) | Maxrate / Bufsize | Audio | `BANDWIDTH` im Master |
| --- | --- | --- | --- | --- | --- |
| 1080p | 1920×1080 (max) | ~4,5–6 Mbit/s | 6 M / 12 M | AAC-LC 160k | 6.500.000 |
| 720p | 1280×720 | ~2,2–3 Mbit/s | 3 M / 6 M | AAC-LC 160k | 3.400.000 |
| 480p | 854×480 | ~0,8–1,2 Mbit/s | 1,2 M / 2,4 M | AAC-LC 160k | 1.500.000 |
| 1080p-hevc *(optional)* | 1920×1080 | ~3–4 Mbit/s | 4,5 M / 9 M | AAC-LC 160k | 5.000.000 |
| 1080p-av1 *(optional)* | 1920×1080 | ~2,5–3,5 Mbit/s | 4 M / 8 M | AAC-LC 160k | 4.500.000 |

`BANDWIDTH` = Maxrate + Audio + 15 % Puffer, damit hls.js nicht zu knapp schätzt. Nie über die Quellauflösung hochskalieren: ein 720p-Original bekommt keine 1080p-Stufe (dann nur 720p/480p). Seitenverhältnis erhalten, gerade Zahlen (`scale=-2:1080`).

### 3.2 Encoder-Strategie: NVENC, x264-Fallback, HEVC/AV1 als Zusatz

- **Primär NVENC** (`h264_nvenc`), `-preset p7 -tune hq -rc vbr -cq 19/21/23` je Stufe (1080p/720p/480p), `-maxrate`/`-bufsize` wie oben, `-profile high -pix_fmt yuv420p`, `-rc-lookahead 32`, `-spatial-aq 1 -aq-strength 8`.
- **Fallback x264**, wenn kein NVENC verfügbar ist oder der NVENC-Testlauf fehlschlägt: `libx264 -preset slow -crf 18/20/22` je Stufe, dieselben `-maxrate`/`-bufsize`, `-profile high`.
- **Codec-Optionen als Zusatzspuren, nie als Ersatz:** `hevc_nvenc` (Turing+) mit `-cq 23/25/27` und `av1_nvenc` (Ada, RTX 40+) mit `-cq 27/30/33` werden nur gebaut, wenn die GPU es kann und die Quelle es lohnt. **H.264 bleibt immer vollständig vorhanden** — die einzige Variante, die jeder Browser und Safari sicher abspielt.
- Der Master listet alle Varianten mit `CODECS`-Attribut, der Player wählt:

```
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Deutsch",LANGUAGE="de",DEFAULT=YES,AUTOSELECT=YES,URI="audio/de/index.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Englisch",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="audio/en/index.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",DEFAULT=YES,AUTOSELECT=YES,URI="subs/de/index.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=6500000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac",SUBTITLES="subs"
1080p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120.90,mp4a.40.2",AUDIO="aac"
1080p-hevc/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3400000,RESOLUTION=1280x720,CODECS="avc1.64001F,mp4a.40.2",AUDIO="aac"
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480,CODECS="avc1.64001E,mp4a.40.2",AUDIO="aac"
480p/index.m3u8
```

hls.js nimmt HEVC/AV1 nur, wenn `MediaSource.isTypeSupported` es bestätigt; Safari nutzt die native Variante. Fällt eine Zusatzvariante aus, bleibt H.264 als Netz.

### 3.3 Segmente, GOP und Keyframes

- **6 s Segmente** (`-hls_time 6 -hls_list_size 0`), kein Scene-Cut, damit jede Segmentgrenze exakt auf einem Keyframe liegt. Das ist die Voraussetzung dafür, dass der Player ohne Seek mitten in den Film einsteigen kann (#48 §1.3 Driftkorrektur, §1.4 kein Seek).
- x264: `-force_key_frames "expr:gte(t,n_forced*6)" -sc_threshold 0 -g <fps*6> -keyint_min <fps*6>`.
- NVENC: `-forced-idr 1 -g <fps*6> -keyint_min <fps*6>` **plus** dieselben `-force_key_frames`, damit 29,97-fps-Quellen nicht driften.
- Bei variablem Frame-Rate zuerst normalisieren (`-vsync cfr -r 24/25/30` passend zur Quelle), sonst passt `n_forced*6` nicht auf die Zeitachse.
- `-hls_flags independent_segments+temp_file`, `#EXT-X-TARGETDURATION:6`, `#EXT-X-INDEPENDENT-SEGMENTS` in jeder Varianten-Playlist. Die Playlist auf Platte ist `VOD` (`-hls_playlist_type vod`).
- H.264-Varianten als **MPEG-TS**, HEVC/AV1-Varianten als **fMP4/CMAF** (`-hls_segment_type fmp4`): diese Codecs gehören dorthin, und nur CMAF-fähige Browser fordern sie an.

### 3.4 Automatische Bildaufbereitung

- **Schwarze Balken weg (`cropdetect`):** `cropdetect=24:2:0` an drei Stellen (10 %, 50 %, 90 % der Laufzeit), Median der Ergebnisse, beide Werte gerade gerundet. Weicht der Crop um mehr als 2 % von der vollen Fläche ab, wird geschnitten (`crop=w:h:x:y`), sonst nicht. Bei 4:3-Material Letterbox auf 16:9 statt Beschnitt.
- **HDR → SDR:** `ffprobe` liest `color_transfer`/`color_primaries`. Bei `smpte2084` (PQ) oder `arib-std-b67` (HLG):
  `zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p`
  Ergebnis wird im Log vermerkt (`hdr_tonemapped: true`); das Master-Flag bleibt SDR, kein HDR-Tag an einem SDR-Stream.
- **Lautheit (EBU R128, zweipassig):** Pass 1 misst mit `loudnorm=print_format=json`, Pass 2 normalisiert mit `loudnorm=I=-16:TP=-1.5:LRA=11:measured_*`. Ziel **−16 LUFS integriert, True Peak −1,5 dBTP** — der übliche Bereich für Web-Streaming, konsistent über alle Filme, damit niemand nachregeln muss. Messwerte stehen in `ready.json` (`loudness: {i, tp, lra}`).
- **Audio-Renditions:** AAC-LC, 48 kHz, **Stereo 160 kbit/s** als Standard. Quelle mit mehr als 2 Kanälen: Stereo-Downmix (`-ac 2`) ist Pflicht; eine **5.1-Rendition** (AAC 384k) wird zusätzlich gebaut, wenn die Quelle 6 Kanäle hat und `KINO_ALLOW_51=1` gesetzt ist. Fehlt die 5.1-Spur, zeigt der Player nur Stereo — kein leerer Ton.

### 3.5 Untertitel und Sprache

- **Erkennung:** `ffprobe -show_streams -select_streams s`; Sprache aus `tags.language`, sonst aus `tags.title`/Dateiname (`German`, `Deutsch`, `Ger`, `Eng`, `English`, `Forced`, `SDH`).
- **Standardsprache Deutsch.** `LANGUAGE="de", DEFAULT=YES` auf der deutschen Spur (Audio wie Untertitel), alle anderen `DEFAULT=NO, AUTOSELECT=YES`. Gibt es keine deutsche Spur, wird die erste als Default markiert und im Log vermerkt; der Player zeigt „keine deutsche Tonspur". Es wird nichts umbenannt, was nicht deutsch ist.
- **Text-Untertitel** (`subrip`, `ass`, `ssa`, `mov_text`, `webvtt`) → `-c:s webvtt` nach `subs/<lang>.vtt`, dazu eine Media-Playlist `subs/<lang>.m3u8` für `#EXT-X-MEDIA:TYPE=SUBTITLES` (#48 verlangt pro Sprache eine Playlist, nicht nur die `.vtt`).
- **Bild-Untertitel** (`hdmv_pgs_subtitle`, `dvd_subtitle`, `dvb_subtitle`) werden **übersprungen** und im Status gemeldet: `{"lang":"de","status":"skipped","reason":"image_based"}`. Kein OCR im ersten Wurf (später optional, nie automatisch ohne Nils).
- `forced`-Spuren laufen als eigene Rendition mit `FORCED=YES`, damit fremdsprachige Passagen deutsch bleiben.
- Alle Sprachen landen in `ready.json` und im Ingest; der Worker baut daraus die `SUBTITLES`-Gruppe.

---

## 4. Ingest zu Clouds Admin

### 4.1 Endpoint und Auth

`POST https://admin.nwebnotfound.dev/api/kino/ingest`

Zwei Schichten, beide Pflicht — der Admin-Host liegt hinter Cloudflare Access, deshalb kein Bypass-Pfad, sondern ein Service-Token:

1. **Cloudflare Access Service-Token** (`CF-Access-Client-Id`, `CF-Access-Client-Secret`) auf der Access-Application des Admin-Hosts. Der Watcher ist damit ein zugelassener Nicht-Mensch; Nils' Login bleibt unberührt.
2. **HMAC-Signatur des Bodys:**
   - `X-Kino-Timestamp: <unix-ms>`
   - `X-Kino-Signature: hex(HMAC-SHA256(KINO_INGEST_SECRET, timestamp + "." + rawBody))`
   - `KINO_INGEST_SECRET` liegt beim Worker als `wrangler secret`, beim Watcher nur in `.env`. Kein Bearer-Token im Klartext, kein Secret in der URL.
   - **Replay-Fenster 5 Minuten** (`|now − timestamp| ≤ 300000`), Prüfung gegen die **rohen Bytes** vor dem JSON-Parse, Vergleich in konstanter Zeit (`crypto.timingSafeEqual`). Ablehnung ohne Detailauskunft: `401 {"error":"invalid_signature"}`.

### 4.2 Payload

```json
{
  "file_name": "Der Film (2024).mkv",
  "file_hash": "<sha256 hex>",
  "rel_path": "filme/Der Film (2024).mkv",
  "status": "queued | transcoding | ready | failed",
  "film_id": "<ULID, nur in Antworten und bei Folge-Statusmeldungen>",
  "duration_s": 6840,
  "variants": [
    { "name": "1080p", "height": 1080, "bandwidth": 6500000, "codec": "h264", "playlist": "1080p/index.m3u8" },
    { "name": "1080p-hevc", "height": 1080, "bandwidth": 5000000, "codec": "hevc", "playlist": "1080p-hevc/index.m3u8" }
  ],
  "audio": [ { "lang": "de", "label": "Deutsch", "channels": 2, "default": true, "playlist": "audio/de/index.m3u8" } ],
  "subtitles": [ { "lang": "de", "label": "Deutsch", "path": "subs/de/index.m3u8" },
                 { "lang": "en", "status": "skipped", "reason": "image_based" } ],
  "loudness": { "i": -16.0, "tp": -1.5 },
  "hdr_tonemapped": false,
  "ready_json": "ready.json",
  "error": "optional, nur bei failed"
}
```

Antworten: `202` angenommen (queued/transcoding), `200` mit `{"film_id":"..."}` wenn `ready` verarbeitet, `409` bei Konflikt (gleicher `file_hash` mit anderem `rel_path` bzw. anderer `film_id`, oder Status rückwärts), `401` bei Signatur-/Zeitfehler. Fehlerform `{"error":"<code>"}`.

### 4.3 Idempotenz

- Serverschlüssel ist **`file_hash`**: gleiche Meldung = gleiches Ergebnis, auch nach zehn Wiederholungen. Der Status geht **nur vorwärts** (`queued → transcoding → ready`), Ausnahme `failed → queued` beim Retry.
- Der Watcher wiederholt fehlgeschlagene Meldungen mit Backoff (30 s, 2 min, 10 min, dann stündlich). `409` wird **nicht** wiederholt, sondern geloggt und als Konflikt markiert.
- `film_id` schickt der Watcher beim ersten Mal nicht mit; er merkt sie sich aus der Antwort in `state\jobs\<file_hash>.json` und nutzt sie bei Folge-Statusmeldungen. Der Worker bleibt der Vergabepunkt.
- Pfade sind immer relativ zum Kino-Ordner, nie mit Host.

### 4.4 `ready.json` im Ordner

Zusätzlich zur API-Meldung schreibt der Encoder `hls\<file_hash>\ready.json` (#48 §5.2), damit der Scan die Bereitschaft ohne API kennt:

```json
{ "file_hash": "...", "duration_s": 6840, "segments": 1140, "segment_s": 6,
  "variants": ["1080p","720p","480p"], "subtitles": ["de"],
  "built_at": 1793950000000, "encoder": "nvenc", "loudness": {"i": -16.0} }
```

---

## 5. Auslieferung Plan A: Cloudflare R2 (vorgesehen, deaktiviert)

- **Privater Bucket `kino-hls`, kein Public Bucket, keine Custom Domain ohne Signatur.** Ein öffentlicher Bucket wäre eine Video-URL, die jeder weitergeben kann — genau das schließt #48 aus. Zugriff ausschließlich über Links, die **der Worker** nach Session- und Rollencheck erzeugt: kurzlebige presigned GET-URLs (AWS SigV4) oder Worker-Streaming aus dem Binding.
- Signiert wird **pro Präfix**, nicht pro Datei: ein Token gilt für `/hls/<film_id>/` und wird in Varianten- und Segment-URLs übernommen (Query `exp/sid/room/k/sig`, identisch zur eigenen Signatur). Eine Signatur je Einzeldatei bricht HLS, weil die Playlist auf ungesignierte Segmente zeigt.
- **Kontingent (Stand der R2-Preisdoku):** 10 GB-Monat Speicher, 1 Mio. Class-A- und 10 Mio. Class-B-Operationen pro Monat, **Egress frei**. Darüber kostenpflichtig (Standard: 0,015 USD/GB-Monat, Class A 4,50 USD/Mio., Class B 0,36 USD/Mio.). Quelle: <https://developers.cloudflare.com/r2/pricing/>. Ein 8-GB-Film sprengt das Gratis-Kontingent allein — deshalb bleiben höchstens die gerade gespielten Filme im Bucket.
- **Zahlungsmethode ist ab Aktivierung nötig, nicht erst beim Überschreiten des Limits.** Video über R2 ist laut Cloudflare-Bedingungen erlaubt, aber nur mit hinterlegter Zahlungsmethode.
- **Vorteil:** Zuschauer laden von Cloudflare, der Heim-Upload ist **einmalig pro Film** (ein 8-GB-Film ≈ 30–60 min bei 40 Mbit/s) und nicht pro Zuschauer; kein Engpass bei 20 Zuschauern.
- **Nachteil:** Speicher- und Operationskosten jenseits des Freikontingents, ein zweiter Ablageort, Upload-Zeit direkt nach dem Encode.
- **Aktivierung erst nach ausdrücklichem OK von Nils** (Zahlungsmethode hinterlegt er selbst). Bis dahin wird gegen lokal/Mocks entwickelt (`KINO_DELIVERY=local`); der R2-Pfad liegt hinter einem Schalter und antwortet ohne Bucket sauber mit `503 {"error":"r2_not_configured"}`. Aufräumen im Bucket (Löschen alter Präfixe) läuft automatisch, aber **erst nach** Aktivierung und nur für generierte HLS-Objekte.

---

## 6. Auslieferung Plan B: lokaler Origin hinter cloudflared (Fallback)

### 6.1 Aufbau

- **Kleiner eigener Proxy statt nginx.** nginx taugt hier nicht als Signaturprüfer: `secure_link` kann nur MD5, gefordert ist HMAC-SHA256 mit Key-ID. Also ein kleiner Node-/Go-Dienst (`kino-origin`) auf `127.0.0.1:8090`, gebunden auf Loopback, **keine** Portfreigabe in der Fritzbox, keine `0.0.0.0`-Bindung.
- `cloudflared` veröffentlicht nur diesen Origin (`origin.kino.nwebnotfound.dev`), keine öffentliche DNS-Auflösung ohne Access. Der Hostname existiert für Zuschauer nicht, die Heim-IP bleibt verborgen.
- Der Proxy macht vier Dinge: **Signatur prüfen**, **Playlist-Fenster signiert neu erzeugen**, **Dateien mit `Cache-Control: no-store` ausliefern**, **Pfade streng validieren**. Kein Verzeichnislisting, keine CORS-Header (auch kein Wildcard), `Referer` ist kein Sicherheitsmerkmal.
- Pfad-Whitelist: nur `/hls/<file_hash>/<erlaubte Datei>`, kein `..`, keine Symlinks, kein Zugriff auf `filme\`, `logs\`, `state\`.
- Segmente gehen **direkt** zum Client, nicht durch den Worker: der Worker proxied keinen Videotraffic.

### 6.2 Rollierendes, signiertes Playlist-Fenster

Kernproblem: `-hls_playlist_type vod` plus kurze TTL heißt, dass die Segment-URLs einer 2-Stunden-Playlist längst abgelaufen sind, wenn der Saal bei Minute 90 ankommt.

Lösung: **auf Platte bleibt die Playlist VOD, signiert wird beim Ausliefern.** Der Proxy liest `1080p/index.m3u8`, nimmt die Saal-Position aus `pos=` (serverseitig gegen die Dauer geklemmt) und liefert ein **Fenster**:

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:148
#EXT-X-DISCONTINUITY-SEQUENCE:0
#EXT-X-INDEPENDENT-SEGMENTS
#EXTINF:6.000,
seg_00148.ts?exp=1794000120000&sid=<hash>&room=<id>&k=<key-id>&sig=<b64url>
... 6 Segmente (36 s)
```

- **Fenster:** 3 Segmente vor der Position, 6 ab der Position (54 s Vorlauf, mehr als der 6-s-Puffer braucht). Jede Segment-URL ist frisch signiert und läuft **120 s** nach Ausstellung ab, die Playlist selbst **30 s**.
- Der Player fragt die Playlist alle 6 s neu an, bekommt das nächste Fenster und läuft ohne Seek weiter. Das passt zu „kein Seek, nur laufende Sendung" (#48 §1.3) und hält die Signatur-Lebensdauer klein: ein kopierter Link ist nach zwei Minuten wertlos.
- Kein `#EXT-X-ENDLIST` im laufenden Fenster; `MEDIA-SEQUENCE` macht die Reihenfolge eindeutig. Erst bei Position ≥ Dauer liefert der Proxy `#EXT-X-ENDLIST`.

### 6.3 ToS- und Upload-Risiko, konkret

- **Upload-Last:** Bitrate × Zuschauer. 20 Zuschauer in 1080p × 4,5 Mbit/s ≈ **90 Mbit/s dauerhaft ausgehend** (bei 6 Mbit/s Spitze bis ~120 Mbit/s). Ein üblicher Kabel-/DSL-Anschluss liefert 40–50 Mbit/s Upload — das reicht **nicht**. Konsequenz: entweder 720p (20 × 3 ≈ 60 Mbit/s), weniger Zuschauer, oder Plan A. Das ist eine harte Grenze, keine Optimierungsfrage.
- **ToS:** Video über Tunnel/CDN im Free-Plan ist nach den Cloudflare Service-Specific Terms riskant (Video-Streaming ist dem Stream-Produkt vorbehalten). Das Risiko liegt **hier**, nicht bei R2. Es kann sich als Drosselung oder Aufforderung zum Wechsel in einen bezahlten Plan zeigen.
- **Ausweichweg:** `playit.gg` (Free) für den Origin, oder Plan A. **Entscheidung liegt bei Nils.** Der Proxy ist lieferantenneutral: er kennt nur Loopback und Signatur, jeder Tunnel davor funktioniert.

---

## 7. Signatur-Spezifikation

- Payload: `film_id | pfad | exp | sid | room` (#48 §1.5), `pfad` relativ zum Filmordner (`1080p/seg_00148.ts`), `sid` = Hash der Session, `room` = Raum-ID.
- `sig = base64url(HMAC-SHA256(KINO_SIGNING_KEY, payload))`, `k=<key-id>` im Query für Rotation: zwei Schlüssel parallel, der Worker signiert mit dem neuen, der Proxy akzeptiert beide, bis der alte abläuft (24 h Überlappung).
- TTL: **Segmente 120 s, Playlists 30 s**. Nach `ends_at` (+120 s Puffer aus #48 Open Question 9) ist auch eine formal gültige Signatur tot.
- Prüfung in konstanter Zeit, Ablehnung mit `403` ohne Angabe, welcher Teil fehlt. Ohne Signatur, Session oder Raum wird nichts ausgeliefert, auch nicht das Master.
- Signatur-Secret nur in `.env` bzw. `secrets/`, nie im Repo, nie im Log, nie in einer Fehlermeldung.

---

## 8. Secrets

| Name | Wo | Zweck |
| --- | --- | --- |
| `KINO_INGEST_SECRET` | `.env` (Watcher), `wrangler secret` (Worker) | HMAC für den Ingest |
| `KINO_SIGNING_KEY`, `KINO_SIGNING_KEY_ID` | beide Seiten | Segment-/Playlist-Signatur, rotierbar |
| `KINO_ORIGIN_CF_ID`, `KINO_ORIGIN_CF_SECRET` | `.env` | Access-Service-Token zum Admin-Host |
| `KINO_DISCORD_WEBHOOK` | `.env` | Benachrichtigung bei Fehlern und fertigen Filmen |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_ACCOUNT_ID`, `R2_BUCKET` | `.env` / Worker-Secrets | Plan A, erst nach Nils' OK |

Regeln: `.env`, `secrets/`, `data/`, `backups/` und `kino\` liegen außerhalb des Repos bzw. sind ignoriert. Secrets erscheinen nie in Logs, PR-Texten, Chats oder Fehlermeldungen.

---

## 9. Betrieb: Aufräumen, Healthcheck, Benachrichtigung

### 9.1 Speicherbudget mit automatischem Aufräumen

- Budget als Konfiguration (`KINO_HLS_MAX_GB`, Default 300). Der Watcher führt `state\storage.json` (Ordner, Bytes, `last_used_at` aus der letzten Auslieferung bzw. `built_at`).
- Aufgeräumt wird **nur generiertes HLS** ausgelaufener Filme: ein `hls\<file_hash>\` wird gelöscht, wenn es älter als `KINO_HLS_TTL_DAYS` (Default 30) ist **oder** das Budget überschritten wird — älteste zuerst, nie ein Ordner, der gerade läuft oder im Programm steht (Statusabfrage beim Worker; ist der Worker offline, wird nicht gelöscht).
- **Originale in `kino\filme` werden niemals gelöscht, verschoben oder verändert.** Auch keine fremden Ordner, keine Dateien außerhalb `hls\*.tmp` und `hls\<file_hash>\`.
- Unter 40 GB frei: erst HLS aufräumen, dann Discord-Warnung, dann Job-Pause. Die Platte läuft nicht voll.

### 9.2 Healthcheck

`GET http://127.0.0.1:8090/healthz` liefert `{ok, queue_len, current_job, last_success_at, disk_free_gb, gpu_busy}` — nur Loopback, keine Secrets. Der Host-Task prüft alle 60 s Queuedatei-Alter, Plattenplatz, NVENC-Verfügbarkeit und letzten erfolgreichen Job. Das bestehende `scripts/check-local.py` bekommt diese Werte als zusätzliche Zeile, damit alles in einer Übersicht bleibt.

### 9.3 Discord-Benachrichtigung

Webhook aus `.env` (`KINO_DISCORD_WEBHOOK`), drei Ereignisse: **fertig** (Titel, Dauer, Varianten, Encode-Zeit), **fehlgeschlagen** (Grund, Datei, Attempts), **Warnung** (Platte knapp, NVENC weg, GPU-Pause > 6 h). Kein Spam: gleicher Fehler höchstens einmal pro Stunde. Die Nachricht enthält nie Pfade mit Host und nie Signaturen.

---

## 10. Umsetzung in Schritten K1–K10

Clouds Worker-Seite hat eigene K1–K10 (#48 §8). **Diese K1–K10 sind die Origin-Seite** und hängen an Clouds K4 (HLS/Manifest) und K5 (Dateien/Admin). Jeder Schritt: eigener Branch, eigener PR, grüne Checks, kein Fremdthema.

| # | Schritt | Inhalt | Abhängig von | Fertig, wenn |
| --- | --- | --- | --- | --- |
| **K1** | Watcher-Skelett | Compose-Profil `kino` bzw. Windows-Dienst mit Autostart und Restart-Policy, Verzeichnisse, Lock, Logging, `/healthz`, Konfiguration aus `.env` | – | Dienst startet nach Reboot allein, überlebt einen Kill, `/healthz` antwortet |
| **K2** | Erkennung und Queue | Scan, Stabilitätsprüfung, ffprobe-Validierung, `file_hash`, Queue mit einem Job, `attempts`, tmp + atomares Umbenennen, Crash-Fortsetzung | K1 | Ein abgebrochener Lauf hinterlässt kein halbes `ready.json`; 10 Dateien in Folge = 10 saubere Jobs |
| **K3** | Ingest-Client | HMAC-Signatur, Service-Token, 5-min-Fenster, Retry mit Backoff, Statusmaschine, `film_id` merken, `ready.json` + `source.json` | K2, Clouds Ingest-Endpoint | Ein Film steht nach dem Encode ohne Klick in der Admin; doppelte Meldung erzeugt kein Duplikat (202 ohne Änderung) |
| **K4** | H.264-Encode-Kern | NVENC p7/cq/VBR mit Maxrate-Leiter, x264-CRF-Fallback, 6-s-GOP mit `force_key_frames`, Master mit `BANDWIDTH`/`CODECS` | K3 | Zwei Testfilme: Segmentgrenzen == Keyframes (ffprobe-Check), Playlist in hls.js und Safari fehlerfrei |
| **K5** | Bild- und Ton-Aufbereitung | `cropdetect`, HDR→SDR-Tonemapping, zweipassiges `loudnorm`, AAC 160k Stereo, optional 5.1 | K4 | 2,39:1-Quelle ohne Balken, HDR-Quelle korrekt, Lautheit −16 LUFS ±1 |
| **K6** | Untertitel und Sprache | Spracherkennung, VTT-Konvertierung, Bild-Untertitel `skipped`, `subs/<lang>/index.m3u8`, Default Deutsch, Master-Gruppen `AUDIO`/`SUBTITLES` | K4 | Deutscher Film mit SRT + PGS: SRT spielbar, PGS als `skipped` in `ready.json` und in der Admin sichtbar |
| **K7** | Ressourcenschutz | `nvidia-smi`-Abtastung (60 % über 3 Messungen → Pause), Spiel-Prozess-Erkennung, Nachtfenster 01:00–07:00 bevorzugt, Pause/Resume mitten im Job | K4 | Startet ein Spiel, pausiert der Job binnen 30 s und läuft danach weiter |
| **K8** | Origin-Proxy | HMAC-Prüfung mit Key-ID, rollierendes signiertes Fenster, `no-store`, Pfad-Whitelist, Loopback-Bindung, `cloudflared`-Ingress | K3 | 2-h-Film läuft über die volle Länge ohne abgelaufene URL; manipulierte Signatur → 403 |
| **K9** | Betrieb | Speicherbudget und Aufräumen (nur HLS), Discord-Webhook, Healthcheck in `check-local.py`, Backup von `state\`/`ready.json`, README-Abschnitt | K5–K8 | Aufräumen löscht nur generierte Daten (Test mit präpariertem Ordner), Discord meldet fertig und fehlgeschlagen |
| **K10** | Plan A (R2) | Privater Bucket, presigned Links über den Worker, Lifecycle-Aufräumen, Schalter `KINO_DELIVERY=r2` | Nils' OK + Zahlungsmethode | Umschalten ohne Proxy möglich, Rückweg auf `local` in einer Minute; **bis dahin nur Mock-Tests** |

Reihenfolge: K1 → K2 → K3 → K4 → K5 → K6 → K7 → K8 → K9 → K10. K8 kann parallel zu K5–K7 gebaut werden, sobald K3 steht.

---

## 11. Offene Fragen

1. **Plan A oder Plan B?** R2 braucht Nils' Zahlungsmethode (ab Aktivierung) — Entscheidung offen. Plan B trägt bei 1080p realistisch ~15 Zuschauer, nicht 20.
2. **Cloudflare-ToS:** Akzeptieren wir das Risiko für Video über Tunnel im Free-Plan, oder ist `playit.gg` der Vorzug? Nils entscheidet.
3. **5.1:** Standard nur Stereo, 5.1 als Opt-in — reicht das?
4. **AV1/HEVC:** hängt an der GPU. Ohne Ada bleibt HEVC optional, AV1 entfällt; H.264 ist immer da.
5. **Aufbewahrung:** 30 Tage HLS-Standard (Speicherbudget) — oder länger, wenn Plattenplatz es hergibt?
6. **Bild-Untertitel:** PGS wird übersprungen. Soll später OCR dazukommen (manuell ausgelöst), oder bleibt es bei `skipped`?
