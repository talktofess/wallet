# Wallet — encrypted vault + in-app media downloader (Android / Java)

[![CI](https://github.com/talktofess/wallet/actions/workflows/ci.yml/badge.svg)](https://github.com/talktofess/wallet/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-007396.svg)](https://openjdk.org/)
![Tests](https://img.shields.io/badge/core%20tests-28%20passing-brightgreen.svg)

A native Android app, in Java: an **on-device encrypted vault** with an **in-app
browser that downloads media as you browse** and stores it sealed. It's the
Java/Android sibling of the React Native [vault app](https://github.com/talktofess)
— same AES-GCM design, rebuilt around a robust download pipeline.

The interesting, breakable logic — crypto, HLS manifest parsing, the download
engine — lives in a **pure-JDK `core` module with no Android dependency**, so it's
unit-tested on a plain JVM (and in CI) with no device or emulator. The Android
`app` module is a thin shell: a `WebView`, request interception, and vault UI.

```
core/   (pure JDK — unit-tested, no Android)
  crypto/VaultCrypto      AES-256-GCM sealing + PBKDF2 key derivation
  crypto/SecretStream     chunked, authenticated streaming encryption (large files)
  net/HttpClient          the network seam (+ JdkHttpClient, + a fake for tests)
  net/RetryHttpClient     retry + exponential backoff over transient 5xx / IO errors
  download/MediaSniffer   classify a request as HLS / DASH / progressive / none
  download/M3u8Parser     parse HLS master & media playlists (segments, keys, byteranges)
  download/DashParser     parse MPEG-DASH MPDs (SegmentTemplate/Timeline -> segment URLs)
  download/FileDownloader progressive download with Range/If-Range resume
  download/HlsDownloader  fetch + AES-128-decrypt + concatenate HLS segments
  download/ContentDisposition  pick a safe filename (RFC 5987 filename*)
  media/MpegTs            MPEG-TS packet demuxer (PIDs / PES / payloads)
  vault/VaultIndex        encrypted metadata catalogue (name, MIME, size, source)
  util/UniqueNames        collision-free filenames
app/    (Android — Java)
  BrowserActivity   WebView + shouldInterceptRequest + DownloadListener -> MediaDetector
  DownloadJob       runs the core pipeline (HLS / DASH / progressive) off-thread
  TsRemuxer         lossless .ts -> .mp4 via MediaExtractor + MediaMuxer (no re-encode)
  VaultStore        SecretStream-encrypts files + keeps the encrypted VaultIndex
  VaultActivity     unlock / list screen
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

**3. You have to *find* the media while browsing.** A `WebViewClient`'s
`shouldInterceptRequest` sees every sub-request the page makes, and a
`DownloadListener` catches `Content-Disposition: attachment` responses
([technique](https://gist.github.com/kibotu/32313b957cd01258cf67)). Both feed
`MediaSniffer`, which flags `.m3u8` / `video/*` / `.mp4` etc., so the app can offer
a download even when the page never exposed one.

```
 browse ─▶ WebView.shouldInterceptRequest ─▶ MediaSniffer ─▶ "N downloads found"
                                                   │
 tap ──────────────────────────────────────────────┘
   │
   ├─ HLS:  GET .m3u8 ─▶ parse ─▶ (master? pick best) ─▶ GET segments ─▶ [decrypt] ─▶ concat ─▶ remux .ts→.mp4
   ├─ DASH: GET .mpd  ─▶ parse ─▶ pick best representation ─▶ GET init+segments ─▶ concat
   └─ file: GET with Range/If-Range ─▶ (206 append | 200 restart)
        (all HTTP via RetryHttpClient: backoff over transient 5xx / IO errors)
                                                   │
                                   SecretStream.encrypt ─▶ private storage + encrypted VaultIndex
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
  The passphrase and derived key are never written to disk.

## Build & test

**Core (no Android needed) — this is what CI runs:**
```bash
mkdir -p out && find core/src -name '*.java' > sources.txt
javac -d out @sources.txt
java -cp out com.wallet.core.CoreCheck     # 28/28 tests pass
```

**The app:** open the project in Android Studio (it provides Gradle + the Android
SDK) and run the `app` configuration, or `gradle :app:assembleDebug` with the SDK
installed. The app module is built in the IDE; CI verifies the pure core.

## Scope & boundaries

This downloads **media you have the right to download** — your own content, public
or Creative-Commons media, or sites whose terms allow it. It handles ordinary,
unprotected HTTP / HLS / DASH only. It **does not** implement DRM circumvention
(Widevine / PlayReady / CENC) and won't decrypt protected streams. Respect
copyright and each site's terms of service.

## Status

- `core`: tested (**28/28**), verified locally and in CI — crypto, streaming
  encryption, HLS + DASH parsing, range/resume, retry, TS demux, vault index.
- `app`: WebView browser + interception, HLS/DASH/progressive download,
  `.ts → .mp4` remux, and the encrypted vault; built in Android Studio.
- Follow-ups: DASH `SegmentList`/`SegmentBase` manifests, biometric unlock, a
  download queue + progress UI, and resumable HLS/DASH across app restarts.
