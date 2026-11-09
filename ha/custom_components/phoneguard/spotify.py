"""Spotify sign-in done from Home Assistant, so nobody has to type a password on the keypad phone.

The parent presses "Sign in" on the card; the browser goes to Spotify and comes back to a page served by Home Assistant
(PKCE, so there is no client secret anywhere). HA keeps the refresh token with the device and sends it to the phone in
its settings; the phone then asks Spotify for its own short-lived tokens. Pure helpers here, the web parts in __init__.py.
"""
from __future__ import annotations

import base64
import hashlib
import secrets
import time
from urllib.parse import urlencode, urlparse

AUTH_URL = "https://accounts.spotify.com/authorize"
TOKEN_URL = "https://accounts.spotify.com/api/token"
CALLBACK_PATH = "/api/phoneguard/spotify_callback"
SCOPES = ("user-read-playback-state user-modify-playback-state user-read-currently-playing "
          "playlist-read-private playlist-read-collaborative user-library-read")
PENDING_SECONDS = 600


def new_pkce() -> tuple[str, str]:
    """(verifier, challenge) for the S256 method."""
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).decode().rstrip("=")
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return verifier, challenge


# Spotify accepts only https return addresses, plus the loopback address http://127.0.0.1:PORT. When Home Assistant has no https address, the
# loopback address is used instead: the browser ends on a "cannot connect" page whose address holds the code, and that address is pasted into the card.
MANUAL_REDIRECT = "http://127.0.0.1:8888/callback"


def redirect_uri(base: str) -> str:
    """The address Spotify sends the browser back to: a secure address of this Home Assistant."""
    base = base.strip().rstrip("/")
    if not base.startswith("https://"):
        raise ValueError("Spotify only accepts a secure (https) return address")
    return base + CALLBACK_PATH


def parse_pasted(text: str) -> tuple[str, str, str]:
    """(state, code, error) from the address the browser ended on (or just its query part)."""
    from urllib.parse import parse_qs
    q = parse_qs(urlparse((text or "").strip()).query or (text or "").strip().lstrip("?"))
    return (q.get("state", [""])[0], q.get("code", [""])[0], q.get("error", [""])[0])


def authorize_url(client_id: str, redirect: str, challenge: str, state: str) -> str:
    return AUTH_URL + "?" + urlencode({
        "client_id": client_id, "response_type": "code", "redirect_uri": redirect, "code_challenge_method": "S256",
        "code_challenge": challenge, "scope": SCOPES, "state": state, "show_dialog": "true",
    })


class Pending:
    """Sign-ins that have started and not yet come back (in memory only; a restart just means pressing Sign in again)."""

    def __init__(self) -> None:
        self._p: dict[str, dict] = {}

    def start(self, child: str, client_id: str, redirect: str, now: float | None = None) -> str:
        """Begin a sign-in that will come back to [redirect] (see redirect_uri, or MANUAL_REDIRECT)."""
        now = time.time() if now is None else now
        self._p = {k: v for k, v in self._p.items() if v["exp"] > now}  # drop stale ones
        verifier, challenge = new_pkce()
        state = secrets.token_urlsafe(24)
        self._p[state] = {"child": child, "client_id": client_id, "verifier": verifier, "redirect": redirect, "exp": now + PENDING_SECONDS}
        return authorize_url(client_id, redirect, challenge, state)

    def take(self, state: str, now: float | None = None) -> dict | None:
        """The details for a returning state, once only."""
        now = time.time() if now is None else now
        p = self._p.pop(state, None)
        return p if p and p["exp"] > now else None


def token_form(p: dict, code: str) -> dict:
    return {"grant_type": "authorization_code", "code": code, "redirect_uri": p["redirect"],
            "client_id": p["client_id"], "code_verifier": p["verifier"]}
