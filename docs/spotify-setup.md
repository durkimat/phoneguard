# Spotify developer app for Phone Guard

Needed once, by the person who owns the Spotify account (a Premium or Family plan account). The values below are public.

1. Go to https://developer.spotify.com/dashboard and log in with the Spotify account. Accept the developer terms if asked.
2. **Create app**.
   - App name: `Phone Guard`
   - App description: `A keypad music remote for a supervised family phone`
   - Website: `https://github.com/durkimat/phoneguard` (optional)
   - Redirect URIs: `io.github.durkimat.phoneguard://spotify-callback` (press Add). For signing in from Home Assistant also add the return address of your Home Assistant: its https address followed by `/api/phoneguard/spotify_callback` (for example `https://xxxx.ui.nabu.casa/api/phoneguard/spotify_callback`). The Phone Guard card shows the exact address when you press Sign in with Spotify; Spotify only accepts https addresses.
   - Which API/SDKs: tick **Android** and **Web API**
   - Accept the terms and save.
3. Open the app's **Settings**.
   - Under **Android packages** add package name `io.github.durkimat.phoneguard` with the SHA1 fingerprint of the debug build:
     `CB:4A:50:E0:2C:59:7D:72:AA:22:5F:DE:40:8F:77:31:52:D2:06:70`
   - Add the same package name again with the SHA1 fingerprint of the release build:
     `8B:B1:3A:46:E0:6D:62:48:29:93:EA:02:7D:BC:56:F1:AB:0C:43:4C`
4. **User management**: add the name and the Spotify email of every account that will use the phone (development mode only works for accounts added here; the number allowed is small, Spotify has said five).
5. Copy the **Client ID** (not the Client secret, which is never needed and must not be shared) and give it to Phone Guard.

Note: the player is planned around Spotify's Web API (devices, playlists, search, control of whichever device is playing), so the Web API option above matters most; the Android SDK option is only for the optional App Remote.

Why: Spotify's App Remote lets an app control the Spotify app only if the app's package name and the fingerprint of the key it is signed with are registered against a client ID.

## Signing in from Home Assistant (no typing on the phone)
1. In the Phone Guard card, Spotify section: enter the Client ID once, then press **Sign in with Spotify** for the phone.
2. Press **Continue to Spotify** (opens in a new tab in whichever browser you are using), sign in with the account the phone should use, and approve.
3. Spotify returns to a page served by Home Assistant ("Signed in to Spotify"). The refresh token is kept in Home Assistant with the phone and sent to the phone in its settings; the phone takes it up at its next check-in. There is no client secret anywhere.
4. Sign out in the card removes it from Home Assistant and signs the phone out at its next check-in.
