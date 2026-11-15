# Built-in Spotify Connect (librespot): feasibility notes

> **REJECTED (2026-11-15): Spotify audio-key restriction.** The hands-on spike (PC session) showed librespot 0.8.0 builds for aarch64-linux-android in about 50 s,
> runs on the J36 (14 MB stripped, about 8 MB RSS idle, near 0% CPU), and the phone appears over mDNS and answers zeroconf `getInfo`. But librespot's own README (latest
> commit, 2026-11-07) says Spotify changed audio key delivery: accounts created after an unknown cutoff can no longer get audio keys (`audio key error 0 1`), for all
> librespot versions, with no workaround (issue #1649, locked). Whether it plays would depend on the family's account age, and Spotify can widen the restriction at any time,
> so it is not a sound foundation. **Do not build the service or the AudioTrack sink.** Direction stays: the official Spotify app on the phone, installed once
> (docs/install/spotify-app.md). Possible later spikes, owner to decide: make that install easier or automatic; whether Spotify's Web Playback SDK works in the phone's WebView (needs Widevine).
> The rest of this note is kept as the record of the study.

Status: paper study only (2026-11-15). Nothing was built. A hands-on build on the NAS (Rust toolchain plus the Android NDK, both downloaded) was started and then
held back, because it needed a downloaded installer run on the build machine; it can be run when the owner says so (script outline at the end).

## The idea
Today Phone Guard drives the Spotify *app* (media session) and the Spotify Web API (needs the family's own developer app, Client ID). The owner would rather have no
Spotify app, no app store, and no developer app: the phone itself appears in Spotify as a Connect speaker ("Phone Guard J36"), the way a smart speaker does.
[librespot](https://github.com/librespot-org/librespot) is the open-source Connect client that does this.

## What looks fine
- **Builds for Android.** librespot is pure Rust (plus a few C-free crates with `rustls`); `cargo ndk` / the NDK's clang target `aarch64-linux-android`. Many people run it on Android phones
  (Termux). With `--no-default-features` and the `pipe` or `rodio`-less set, there are no native audio dependencies to port.
- **Plays through the phone's audio.** Use librespot's `pipe` backend (raw 44.1 kHz stereo S16 on stdout) and let Phone Guard play it with an `AudioTrack` (same path the radio uses),
  so volume keys, headphones, Bluetooth, the sleep timer and the existing audio-focus rules all keep working. No ALSA/Oboe needed.
- **Control.** librespot's library exposes the Connect "Spirc" controller (play, pause, next, previous, volume, seek, and the current track metadata). A tiny Rust wrapper
  binary (`pg-spotify`, about 200 lines on top of the `librespot-*` crates) can take commands on stdin and print events as JSON lines. Phone Guard starts it as a child
  process from its native-library directory (an executable named `libpgspotify.so`, which Android lets an app run) or from `/system/bin` in the image.
- **No developer app.** Playback and transport come from the Connect protocol itself; the Web API is not needed for them. Search and playlist browsing are the one thing that would
  still want it (see risks).

## Login (the hard part)
- **Zeroconf (Spotify's "Connect to a device" login):** the phone advertises itself with mDNS on Wi-Fi; the owner opens Spotify on *their* phone, taps the device list, taps
  "Phone Guard J36", and Spotify hands the account credentials to the phone, which keeps a cached login blob. It needs Wi-Fi on the same network at setup, and the phone must be
  allowed multicast. One-time, done by the parent at home. This is the best fit.
- **OAuth:** newer librespot can log in with an OAuth link (PKCE with a redirect to `127.0.0.1`). The redirect has to be completed in a browser, which the keypad phone does not
  have; it would have to go through the parent's page. Possible later, more work.
- The cached login would be stored sealed on the phone (Keystore, like the email password) and could be refreshed by the parent through the companion.

## Risks and costs
1. **Terms of service.** librespot is an unofficial client; Spotify tolerates it but does not endorse it, and it requires a **Premium** account. The family should know that.
2. **It breaks now and then.** Spotify changes its protocol; librespot follows within days to weeks. The phone would need a way to update the binary (the image or the app update path).
3. **Browsing.** Choosing a playlist on the phone itself needs the Web API (developer app) or Spotify's private "spclient" endpoints that librespot's metadata crate uses; the latter work but are
   undocumented. Without either, the keypad phone is a pure remote speaker: the parent or a tablet picks what plays, and the phone's keys do play, pause, next, previous and volume.
4. **Mobile data.** Streaming over mobile data is heavy on a metered SIM; the existing radio/data policy would need a "Spotify only on Wi-Fi" rule.
5. **Battery.** A Connect speaker keeps a connection open; use it only while a session exists, as the WhatsApp bridge does with its idle disconnect.
6. **Size and licence.** About 5 to 8 MB per ABI, MIT licence; fine for the image and for the public repo as source/build script (no Spotify bytes).

## Estimate
Build + wrapper + pipe-to-AudioTrack + a Spotify screen in the launcher that does not need the app: roughly a week of work including the login flow and live testing,
plus ongoing upkeep for protocol changes. Recommend a proof first: build the binary on the NAS, run it from `adb shell` on the J36 and log in by zeroconf. That settles the
two unknowns (does the Go-free Rust runtime run on this Android 14 image; does mDNS work on this phone's Wi-Fi) in about a day.

## Proof script outline (not run)
Install `rustup` (minimal profile) and `aarch64-linux-android`, unpack NDK r26d, `git clone` librespot, then
`cargo build --release --target aarch64-linux-android --no-default-features --features "rustls-tls-webpki-roots with-libmdns"` with the NDK clang as linker,
`adb push` to `/data/local/tmp`, run `./librespot --name "PG test" --backend pipe --cache /data/local/tmp/c | head -c 1 | xxd` and look for the device in Spotify.

## App-side design (paper, 2026-11-15)
**Where the binary lives.** Two options. (a) In the Phone Guard APK as `lib/arm64-v8a/libpgspotify.so` (a real executable named like a library): Android extracts it to the app's native-library directory, which the app may execute from, with the normal `app_exec` SELinux rules; it updates with the app, and the update path already exists. (b) In the image at `/system/bin/pg-spotify` with a `pg_spotify_exec` label and a domain transition: more work (sepolicy, the PC session's flash cycle) and it only updates with the image. Recommend (a); no sepolicy change, no image needed.
**Supervision.** A foreground service (media-playback type, already declared) starts the binary only while a Spotify session is wanted (the Music screen's Spotify entry, or a parent "play" command), restarts it with back-off when it exits, and stops it after a few idle minutes, as the WhatsApp bridge does. stdout carries raw PCM, stdin carries commands, stderr is the log (masked, never the credentials).
**Audio.** A reader thread feeds an `AudioTrack` (44.1 kHz, stereo, 16-bit, USAGE_MEDIA), the same sink the radio uses, so the volume keys, Bluetooth, the sleep timer and audio focus behave as they do today. Buffer about 200 ms to ride out Wi-Fi jitter; on underrun, pause quietly rather than click.
**Control.** The wrapper (`pg-spotify`, thin Rust over librespot's `Spirc`) accepts one JSON line per command (`play`, `pause`, `next`, `prev`, `volume`, `seek`) and prints events (`track`, `state`, `volume`, `login`). Nothing else is needed for transport and "now playing", so the Music screen's existing play/pause/next/previous/volume wiring only changes its back end (from MediaController and the Web API to the wrapper). Search and playlist browsing are the part librespot has no HTTP API for: keep them on the Web API with the owner's Client ID (current behaviour), or later use the wrapper's `spclient` metadata calls. So v1 = transport and now-playing without a developer app; browsing stays as today if the family has set one up.
**Login.** `login` events tell the launcher to show "Open Spotify on your phone, pick Phone Guard J36" (zeroconf; needs Wi-Fi and the parent nearby). The cached credentials file lives in the app's private directory; the parent page can say "Spotify: logged in / needs login".
**Radio policy.** Spotify only while Wi-Fi is up (a setting later); the RadioManager hold applies while a session plays; the process is killed on idle so nothing keeps the radio awake.
**Open questions for the PC proof.** Does the binary run under this image's seccomp/SELinux when launched from the app's native-library directory; does mDNS (zeroconf) work on the J36's Wi-Fi; CPU use of decoding on the MT6761 (Vorbis decode at 160 kbps should be modest); and whether the PCM pipe plus AudioTrack stays glitch-free with the screen off.
