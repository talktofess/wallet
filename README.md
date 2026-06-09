# Wallet — encrypted vault + in-app media downloader (Android / Java)

[![CI](https://github.com/talktofess/wallet/actions/workflows/ci.yml/badge.svg)](https://github.com/talktofess/wallet/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-007396.svg)](https://openjdk.org/)
![Tests](https://img.shields.io/badge/core%20tests-50%20passing-brightgreen.svg)

A native Android app, in Java: an **on-device encrypted vault** with an **in-app
browser that downloads the video you're watching** and stores it sealed. It's the
Java/Android sibling of the React Native [vault app](https://github.com/talktofess)
— same AES-GCM design and the same **chess-disguise unlock**, rebuilt around a
robust download pipeline.

**The whole app pretends to be a chess game.** Open it and it's just "Offline
Chess" — no password box, nothing that says *vault*. The vault opens only when you
play your **secret sequence of moves** from the opening position; those moves
*are* the key (PBKDF2 over the move list). A wrong line just looks like a game.
Once in, you browse to a private video, **watch it**, tap **Download**, and it's
saved — encrypted — for later, playable inside the app.

The interesting, breakable logic — crypto, HLS manifest parsing, the download
engine — lives in a **pure-JDK `core` module with no Android dependency**, so it's
unit-tested on a plain JVM (and in CI) with no device or emulator. The Android
`app` module is a thin shell: a `WebView`, request interception, and vault UI.

```
core/   (pure JDK — unit-tested, no Android)
  crypto/VaultCrypto      AES-256-GCM sealing + PBKDF2 key derivation
  crypto/SecretStream     chunked, authenticated streaming encryption (large files)
  chess/ChessEngine       dependency-free chess (legal moves) — the disguise board
  chess/MoveKey           a played move sequence -> the unlock secret (domain-separated)
  net/HttpClient          the network seam (+ JdkHttpClient, + a fake for tests)
  net/RetryHttpClient     retry + exponential backoff over transient 5xx / IO errors
  download/MediaSniffer   classify a request as HLS / DASH / progressive / none
  download/M3u8Parser     parse HLS master & media playlists (segments, keys, byteranges)
  download/DashParser     parse MPEG-DASH MPDs (SegmentTemplate/Timeline -> segment URLs)
  download/FileDownloader progressive download with Range/If-Range resume
  download/HlsDownloader  fetch + AES-128-decrypt + concatenate HLS segments
                          (cancellable; EXT-X-MAP fMP4 init segment)
  download/ContentDisposition  pick a safe filename (RFC 5987 filename*)
  download/DownloadQueue  ordered queue state machine (+ task model, persistence)
  download/Cancellation   cooperative cancel token for in-flight downloads
  download/ProgressMeter  download speed + ETA over a sliding window
  media/MpegTs            MPEG-TS packet demuxer (PIDs / PES / payloads)
  vault/VaultIndex        encrypted metadata catalogue (name, MIME, size, source)
  util/UniqueNames        collision-free filenames
  util/ByteFormat         human-readable sizes / rates
app/    (Android — Java)
  ChessActivity     the launcher disguise: play the secret moves to set up / unlock
  VaultActivity     home (only once unlocked): browse, list saved videos, lock
  BrowserActivity   WebView + shouldInterceptRequest + DownloadListener + a JS bridge
                    that reports the <video> actually playing -> MediaDetector
  PlayerActivity    plays a saved video in-app (decrypt to private cache, VideoView)
  DownloadJob       runs the core pipeline (HLS / DASH / progressive) off-thread
  DownloadService   foreground worker draining the queue + progress notification
  DownloadsActivity queue UI: per-item progress, pause/resume/cancel/retry
  TsRemuxer         lossless .ts -> .mp4 via MediaExtractor + MediaMuxer (no re-encode)
  VaultStore        SecretStream-encrypts files + keeps the encrypted VaultIndex
```

## How browser downloading actually works (and why a naive pipeline breaks)

A "download the URL" approach works for a plain file but fails on most modern
video. Three things have to be handled:

**1. Streamed media isn't a file — it's a manifest + segments (HLS).**
Most sites serve [HLS](https://www.videosdk.live/developer-hub/hls/hls-stream-m3u8):
a `.m3u8` playlist that points at dozens of short `.ts` segments. GETting the page,
or even the `.m3u8`, yields no video. You must parse the manifest, follow a
*master* playlist to the best-quality *media* playlist, then download **every
segment and concatenate** them. `M3u8Parser` + `HlsDownloader` do exactly this
([reference](https://github.com/TheUndo/m3u8)). Standard AES-128 segments (key URI
in the manifest) are decrypted in stride.

**2. Big downloads must resume.** Per the
[HTTP range spec](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests),
`FileDownloader` sends `Range: bytes=N-` with `If-Range: <ETag|Last-Modified>`;
a `206 Partial Content` means append, a `200` means the server ignored the range
so we restart. The true size comes from `Content-Range: …/total`.

**3. You have to *find* the media while browsing — including the URL with no file
extension.** A `WebViewClient`'s `shouldInterceptRequest` sees every sub-request
the page makes, and a `DownloadListener` catches `Content-Disposition: attachment`
responses ([technique](https://gist.github.com/kibotu/32313b957cd01258cf67)). But a
private video is often served from a tokenized, extensionless URL like
`/stream/9f3?token=…` that a URL sniff can't recognise. So the browser also injects
a tiny script that finds the `<video>` the page is **actually playing** and reports
its real `currentSrc` back over a JS bridge — *that* is "the video I'm watching".
All three feed `MediaSniffer`/`MediaDetector`, so the **Download** bar lights up
even when the page never offered a download.

```
 browse + watch ─▶ shouldInterceptRequest  ┐
                 ▶ injected <video> probe   ├▶ MediaDetector ─▶ "⬇ Download this video"
                 ▶ DownloadListener         ┘
                                                   │
 tap ──────────────────────────────────────────────┘
   │
   ├─ HLS:  GET .m3u8 ─▶ parse ─▶ (master? pick best) ─▶ GET segments ─▶ [decrypt] ─▶ concat ─▶ remux .ts→.mp4
   ├─ DASH: GET .mpd  ─▶ parse ─▶ pick best representation ─▶ GET init+segments ─▶ concat
   └─ file: GET with Range/If-Range ─▶ (206 append | 200 restart)
        (all HTTP via RetryHttpClient: backoff over transient 5xx / IO errors)
                                                   │
                                   SecretStream.encrypt ─▶ private storage + encrypted VaultIndex
                                                   │
                          watch later ─▶ decrypt to private cache ─▶ in-app VideoView
```

## Security model

- **AES-256-GCM** per item, fresh 96-bit nonce each seal; the GCM tag means a
  tampered or wrong-key blob fails to open (both are tested).
- Large files use **`SecretStream`** — chunked AEAD with the chunk counter in the
  nonce and the final-chunk flag authenticated, so **reordering and truncation are
  detected**, and a multi-GB video never has to be buffered whole.
- The **catalogue itself is encrypted** (`VaultIndex`): the list of what you saved
  is private, not just the file bytes.
- Key is **PBKDF2-HMAC-SHA256**, 210k iterations, over a persisted random salt.
  The unlock secret and derived key are never written to disk.
- The unlock secret is your **sequence of chess moves**, not a passphrase: the moves
  are joined, domain-separated (`MoveKey`), and fed to that same PBKDF2. Only the
  salt and the move *count* are stored in the clear; the moves themselves never are.
  A brand-new vault writes an encrypted verifier on first unlock, so a **wrong move
  sequence is rejected** instead of silently opening an empty vault.

## Build & test

**Core (no Android needed) — this is what CI runs:**
```bash
mkdir -p out && find core/src -name '*.java' > sources.txt
javac -d out @sources.txt
java -cp out com.wallet.core.CoreCheck     # 50/50 tests pass
```

**The app:** open the `wallet` folder in Android Studio (Koala / 2024.1+), let it
sync, install **Android SDK Platform 34** via the SDK Manager, pick a device or
emulator on **Android 8.0+** (`minSdk 26`), and Run. The Gradle wrapper (8.7) is
included, so `./gradlew :app:assembleDebug` also works from the CLI with the SDK
installed. The app module is built in the IDE; CI verifies the pure core.

> `minSdk` is 26 (Android 8.0) because the crypto and storage use APIs introduced
> there: `java.util.Base64`, `java.nio.file.Files`, and the
> `PBKDF2WithHmacSHA256` `SecretKeyFactory`.

## Scope & boundaries

This downloads **media you have the right to download** — your own content, public
or Creative-Commons media, or sites whose terms allow it. It handles ordinary,
unprotected HTTP / HLS / DASH only. It **does not** implement DRM circumvention
(Widevine / PlayReady / CENC) and won't decrypt protected streams. Respect
copyright and each site's terms of service.

## Status

- `core`: tested (**50/50**), verified locally and in CI — crypto, streaming
  encryption, the **chess engine + move→key derivation**, HLS (incl. fMP4
  `EXT-X-MAP`) + DASH parsing, range/resume, retry, **cancellable downloads**,
  **speed/ETA metering**, TS demux, vault index, and the **download-queue state
  machine** (transitions + persistence).
- `app`: the **chess-disguise unlock** (set up a secret opening, then play it to
  open — wrong lines just look like a game); a home screen that lists your saved
  videos and **plays them in-app**; a WebView browser that surfaces the video
  you're watching three ways (request interception, an injected `<video>` probe,
  and `DownloadListener`) behind one big **Download** bar; a **download queue**
  drained by a foreground service with a **progress + speed/ETA notification**; a
  **downloads screen** (pause / resume / cancel / retry) where cancelling a
  *running* task stops its IO promptly; HLS/DASH/progressive download; `.ts → .mp4`
  remux; the encrypted vault; and **queue persistence** (sealed, restored on
  unlock). Built in Android Studio.
- Follow-ups: DASH `SegmentList`/`SegmentBase` manifests, biometric unlock, and
  parallel/segment-level resume.
