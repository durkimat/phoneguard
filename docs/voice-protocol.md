# Voice to text: what the phone sends

The phone sends a short recording to the family Worker and gets text back. The Worker uses Cloudflare Workers AI (Whisper) and keeps nothing.

`POST /api/voice/transcribe`

- Body: the recording as a WAV file, 16 kHz, mono, 16-bit, up to 15 seconds (about 480 KB; the limit is 500,000 bytes).
- Headers:
  - `X-Voice-Mode`: `dictation` (typing into a text box) or `command` (a spoken command).
  - `X-Pg-V`: the protocol version, `1`.
  - `X-Pg-Did`: the phone's device id.
  - `X-Pg-Ctr`: the phone's counter. It is the same counter as sync, so it must be higher than the last one used.
  - `X-Pg-N`: a fresh random nonce.
  - `X-Pg-S`: the phone's signature over `V|did|ctr|n|voice:<mode>:<sha256 of the body in lowercase hex>`.

Answers:

| Status | Body | Meaning |
|---|---|---|
| 200 | `{"text": "...", "quality": "ok" or "low", "seconds": 3.2}` | `quality` is `low` when the clip was mostly silence or unclear; `text` may then be empty. With `X-Voice-Mode: command` the answer also has `command` (see below). |
| 403 | `{"error": "voice_off"}` | The phone's current mode has voice typing or voice commands switched off. |
| 403 | `{"error": "rejected"}` or `{"error": "bad signature"}` | Unknown phone or a signature that does not check out. |
| 409 | `{"error": "replay"}` | The counter was not higher than the last one. Skip it forward (as sync does) and try again. |
| 413 | `{"error": "too_long"}` | The recording is over 500,000 bytes. |
| 429 | `{"error": "used_up"}` | The phone has used its 200 voice requests for today (the day changes at 00:00 UTC). |
| 503 | `{"error": "unavailable"}` | Cloudflare could not do it, or its free daily allowance is used up. |

## Spoken commands (mode `command`)

The answer also carries `command`: one of a short fixed list, already checked by the Worker. It is never more than a suggestion; the phone must still follow the current mode's rules, and always asks before calling someone.

```
"command": { "intent": "navigate" | "play" | "call" | "timer" | "unknown",
             "args": { ... }, "say": "text to show", "confirm": true | false }
```
- `navigate`: `{ "destination": "Hertfordshire Sports Village" }` (open "Navigate" with the words filled in; the child still picks the place).
- `play`: `{ "query": "my study playlist", "kind": "playlist" | "artist" | "song" | "any" }`.
- `call`: `{ "name": "Granny" }`, with `confirm: true`. The phone finds the contact itself; the Worker never sees the contact list.
- `timer`: `{ "minutes": 10 }` (1 to 600).
- `unknown`: nothing to do; show `say` ("Sorry, I didn't catch that"). Anything outside the list, any request to change modes, settings, apps or locks, and any recording the speech service doubted come back as `unknown`.

Settings: each mode has `voice_typing` and `voice_cmds` (true or false). They are off in School/work and on in other modes. The phone's config carries them as `voiceTyping` and `voiceCmds` inside each mode.

Privacy: the audio and the words are not stored or logged by the Worker. Only the number of requests per phone per day is counted.
