"""FlipSend relay join, SSND frames, quota, and accountProof.

Mirrors the phone client: text join on wss://stellar-send-relay.onrender.com/v1,
then binary SSND frames. Payload is ciphertext only.
"""

from __future__ import annotations

import hashlib
import json
import os
import struct
from typing import Any

VERSION = 1
HEADER = 12
MAX_FRAME = 262144
SERVICE = 10
URL = "wss://stellar-send-relay.onrender.com/v1"
ROLE_LISTEN = "listen"
ROLE_DIAL = "dial"
PACKAGE = "org.stellartimelock.botlink"

PLAN_PRO = "relay_pro"
PLAN_PLUS = "relay_plus"
PLAN_MAX = "relay_max"
PLAN_ELITE = "elite"

BYTES_PER_GB = 1_000_000_000
FREE_BYTES = 100_000_000
ELITE_BYTES = BYTES_PER_GB
PRO_BYTES = 5 * BYTES_PER_GB
PLUS_BYTES = 10 * BYTES_PER_GB
MAX_BYTES = 15 * BYTES_PER_GB
FAIR_USE_BYTES = 50_000_000_000

QUOTA_MESSAGE = (
    "This month's relay data is used up. Use the same Wi-Fi. Local transfers stay unlimited."
)

X402_ENABLED = True
SPEND_ACTION_CENTS = 100
SPEND_MONTH_CENTS = 500

UNLIMITED_EMAILS = {
    "stellartimelock@gmail.com",
}

DELAYS = (0, 2000, 5000, 10000, 15000, 20000)


def quota_key(secret: bytes) -> str:
    return hashlib.sha256(b"botlink.quota.v1|" + secret).hexdigest()


def allowance(email: str | None, elite: bool, product_id: str | None) -> dict[str, Any]:
    normalized = (email or "").strip().lower()
    if normalized in UNLIMITED_EMAILS:
        return {"tier": "unlimited", "limit": 0, "enforced": False}
    if product_id == PLAN_MAX:
        return {"tier": "max", "limit": MAX_BYTES, "enforced": True}
    if product_id == PLAN_PLUS:
        return {"tier": "plus", "limit": PLUS_BYTES, "enforced": True}
    if product_id == PLAN_PRO:
        return {"tier": "pro", "limit": PRO_BYTES, "enforced": True}
    if elite:
        return {"tier": "elite", "limit": ELITE_BYTES, "enforced": True}
    return {"tier": "free", "limit": FREE_BYTES, "enforced": True}


def _proof(credential: dict[str, Any] | None) -> dict[str, Any] | None:
    if not credential:
        return None
    proof: dict[str, Any] = {"v": VERSION}
    email = str(credential.get("email") or "").strip().lower()
    any_field = False
    if email:
        proof["email"] = email
        any_field = True
    if credential.get("elite"):
        proof["elite"] = True
        any_field = True
    product = credential.get("productId")
    if product:
        proof["productId"] = product
        any_field = True
    return proof if any_field else None


def _write_credential(body: dict[str, Any], credential: dict[str, Any] | None) -> None:
    if not credential:
        return
    for key in ("purchaseToken", "productId", "basePlanId", "packageName", "entitlementKey"):
        value = credential.get(key)
        if value:
            body[key] = value
    proof = _proof(credential)
    if proof:
        body["accountProof"] = proof


def companion_credential() -> dict[str, Any] | None:
    """Optional dial-side proof from the PC. Phone Play Billing is the real source."""
    email = os.environ.get("BOTLINK_ACCOUNT_EMAIL", "").strip().lower()
    elite = os.environ.get("BOTLINK_ELITE", "").strip().lower() in {"1", "true", "yes"}
    product = os.environ.get("BOTLINK_PRODUCT_ID", "").strip()
    if not email and not elite and not product:
        return None
    cred: dict[str, Any] = {}
    if email:
        cred["email"] = email
    if elite:
        cred["elite"] = True
    if product:
        cred["productId"] = product
    return cred


def join_message(room: str, role: str, credential: dict[str, Any] | None = None) -> str:
    body: dict[str, Any] = {"v": VERSION, "room": room, "role": role}
    _write_credential(body, credential)
    return json.dumps(body, separators=(",", ":"))


def usage_message(credential: dict[str, Any] | None = None) -> str:
    body: dict[str, Any] = {"v": VERSION, "op": "usage"}
    _write_credential(body, credential)
    return json.dumps(body, separators=(",", ":"))


def encode_frame(msg_type: int, payload: bytes, flags: int = 0) -> bytes:
    if msg_type < 1 or msg_type > 12:
        raise ValueError("Unknown message type.")
    if len(payload) > MAX_FRAME:
        raise ValueError("Frame payload exceeds the limit.")
    return b"SSND" + bytes((VERSION, msg_type)) + struct.pack(">HI", flags, len(payload)) + payload


def decode_frame(data: bytes) -> tuple[int, bytes] | None:
    if len(data) < HEADER or data[:4] != b"SSND" or data[4] != VERSION:
        return None
    msg_type = data[5]
    if msg_type < 1 or msg_type > 12:
        return None
    flags, length = struct.unpack(">HI", data[6:12])
    if flags != 0 or length > MAX_FRAME or len(data) != HEADER + length:
        return None
    return msg_type, data[HEADER:]


def parse_meter(text: str) -> dict[str, Any] | None:
    try:
        msg = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(msg, dict) or "ok" not in msg:
        return None
    return {
        "ok": msg.get("ok") is True,
        "error": msg.get("error"),
        "message": msg.get("message"),
        "tier": msg.get("tier") or "free",
        "used": int(msg.get("used") or 0),
        "limit": int(msg.get("limit") or 0),
        "enforced": msg.get("enforced") is True,
        "resets": msg.get("resets"),
    }


def over_cap(meter: dict[str, Any] | None) -> bool:
    return bool(meter and meter.get("enforced") and meter.get("limit", 0) > 0 and meter.get("used", 0) >= meter["limit"])


def blocked(meter: dict[str, Any] | None, policy: dict[str, Any]) -> bool:
    if meter and meter.get("error") == "quota":
        return True
    if over_cap(meter):
        return True
    used = int(meter.get("used") or 0) if meter else 0
    return bool(policy.get("enforced") and policy.get("limit", 0) > 0 and used >= policy["limit"])


def format_gb(num: int) -> str:
    n = 0 if num < 0 else num
    gb = round((n / BYTES_PER_GB) * 100) / 100
    text = f"{gb:.2f}".rstrip("0").rstrip(".")
    return f"{text} GB"


def usage_label(used: int, limit: int) -> str:
    if limit <= 0:
        return "Usage appears after the relay answers."
    return f"{format_gb(used)} of {format_gb(limit)} relay this month"
