"""Signed message format shared with the Android controller (spec section 4d, 4f).

Request  {"v":1,"did":..,"ctr":N,"n":nonce,"p":"<payload json string>","s":sig}
Reply    {"v":1,"did":..,"n":nonce,"t":server_ms,"p":"<payload json string>","s":sig}

Keys are ECDSA P-256. Public keys travel as base64 SubjectPublicKeyInfo DER, signatures as base64
DER (what Java's SHA256withECDSA produces and `cryptography` verifies). Only the body is trusted:
no headers, because header forwarding through Nabu Casa is not guaranteed.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

V = 1


def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode()


def b64d(s: str) -> bytes:
    return base64.b64decode(s)


def generate_key() -> tuple[str, str]:
    """Returns (private key PEM, public key base64 SPKI DER)."""
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    return pem, public_b64(priv)


def load_private(pem: str) -> ec.EllipticCurvePrivateKey:
    return serialization.load_pem_private_key(pem.encode(), password=None)  # type: ignore[return-value]


def public_b64(priv: ec.EllipticCurvePrivateKey) -> str:
    return b64e(
        priv.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )


def load_public(b64: str) -> ec.EllipticCurvePublicKey:
    return serialization.load_der_public_key(b64d(b64))  # type: ignore[return-value]


def sign(priv: ec.EllipticCurvePrivateKey, data: bytes) -> str:
    return b64e(priv.sign(data, ec.ECDSA(hashes.SHA256())))


def verify(pub: ec.EllipticCurvePublicKey, data: bytes, sig_b64: str) -> bool:
    try:
        pub.verify(b64d(sig_b64), data, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


def request_bytes(did: str, ctr: int, n: str, p: str) -> bytes:
    return f"PG1\n{did}\n{ctr}\n{n}\n{p}".encode()


def reply_bytes(did: str, n: str, t: int, p: str) -> bytes:
    return f"PG1R\n{did}\n{n}\n{t}\n{p}".encode()


def sas(device_pk: str, ha_pk: str, code: str) -> str:
    """Four digits both sides show so the parent can confirm they paired the right phone."""
    h = hashlib.sha256(f"{device_pk}|{ha_pk}|{code}".encode()).hexdigest()
    return f"{int(h[:8], 16) % 10000:04d}"


def new_nonce() -> str:
    return b64e(os.urandom(12))


def compact(obj: dict) -> str:
    return json.dumps(obj, separators=(",", ":"), sort_keys=True)


def make_reply(priv: ec.EllipticCurvePrivateKey, did: str, n: str, payload: dict, t_ms: int) -> dict:
    p = compact(payload)
    return {"v": V, "did": did, "n": n, "t": t_ms, "p": p, "s": sign(priv, reply_bytes(did, n, t_ms, p))}
