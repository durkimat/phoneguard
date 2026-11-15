# "Simple Spotify": Spotify without the developer account (spike result, 2026-11-15)

Tested by the PC session on the owner's J36 (ims42, Spotify 9.2.0 signed in on the phone), using `adb shell`. Not built into Phone Guard yet; the owner decides.

## Works
- **Start a playlist or track by link:** `am start -a android.intent.action.VIEW -d "spotify:playlist:<id>:play" -p com.spotify.music` (same for `spotify:track:<id>:play`). The `:play` suffix starts playback; without it Spotify only opens the page.
  About 12 s from a freshly restarted Spotify, about 5 s warm. Spotify's screen comes to the front briefly; sending Home afterwards leaves the music playing.
- **Control through the media session:** `cmd media_session dispatch pause|play|next|previous` all behaved. Volume through the music stream (`cmd media_session volume --stream 3 --set N`, 0 to 15).
- **Now playing:** the media session metadata (title, artist, album), state and position. Spotify has a session once it has played.

## Does not work
- `spotify:album:<id>:play` and `spotify:artist:<id>:play` only open the page. The parent supplies **playlist or track** links (open.spotify.com/playlist/<id> converted to the URI).
- Starting by name (`MEDIA_PLAY_FROM_SEARCH`) only opens the search results.
- `am force-stop com.spotify.music` is followed by an immediate restart; a truly dead app was not tested further.

## What it would take (phone side)
1. A list of "Spotify favourites" (name + URI) from the parent page; names from the public `https://open.spotify.com/oembed?url=...` (no login).
2. Launch the VIEW intent (Spotify must be allowed in the mode), wait up to about 15 s for the session to be PLAYING, send play once if it is PAUSED or missing, then go Home.
3. Controls, volume and now playing from `MediaSessionManager`: `MusicController` already does this through the notification listener.
4. No Web API, no Client ID, no refresh token, no "Premium owner of the developer app" rule: the account is simply signed in on the phone.

## Lost compared with the Web API mode
Browsing the library and search on the keypad, choosing a Connect device, and starting playback remotely from the parent page.

## Proposal (PC session)
"Simple Spotify" as the default, the existing Web API mode kept as an optional "Full Spotify". **Owner decision pending.**
