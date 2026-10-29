"""x402 v2 exact + SEP-7 signing request. Bot Link never holds Stellar keys."""

from __future__ import annotations

import json
import os
import re
from typing import Any
from urllib.parse import quote

PROTOCOL = "botlink.x402.sep7"
REQUEST_VERSION = 1
X402_VERSION = 2
SCHEME = "exact"
NETWORK_PUBNET = "stellar:pubnet"
NETWORK_TESTNET = "stellar:testnet"
USDC_SAC_PUBNET = "CCW67TSZV3SSS2HXMBQ5JFGCKJNXKZM7UQUWUZPUTHXSTZLEO7SJMI75"
USDC_SAC_TESTNET = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
USDC_ISSUER_PUBNET = "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
ASSET_CODE = "USDC"
TIMEOUT_SECONDS = 300
CALLBACK = "botlink://x402/signed"
WALLET_PACKAGE = "com.stellartimelock"
ORIGIN_DOMAIN = "stellartimelock.com"
EXAMPLE_PAY_TO = "GBHEGW3KWOY2OFH767EDALFGCUTBOEVBDQMCKU4APMDLQNBW5QV3W3KO"
EXAMPLE_NOTE_ID = "11111111-1111-1111-1111-111111111111"
G_ADDRESS = re.compile(r"^G[A-Z2-7]{55}$")


def is_g_address(value: str) -> bool:
    return bool(G_ADDRESS.fullmatch((value or "").strip()))


def allow_example_pay_to() -> bool:
    return os.environ.get("BOTLINK_X402_ALLOW_EXAMPLE", "").strip().lower() in {"1", "true", "yes"}


def atomic_amount(cents: int) -> str:
    return str(int(cents) * 100_000)


def display_amount(cents: int) -> str:
    return f"{cents // 100}.{cents % 100:02d}00000"


def asset(network: str) -> str:
    return USDC_SAC_TESTNET if network == NETWORK_TESTNET else USDC_SAC_PUBNET


def network() -> str:
    raw = (os.environ.get("BOTLINK_X402_NETWORK") or NETWORK_PUBNET).strip()
    return NETWORK_TESTNET if raw == NETWORK_TESTNET else NETWORK_PUBNET


def _enc(value: str) -> str:
    return quote(value, safe="")


def payment_required(
    note_id: str,
    cents: int,
    pay_to: str,
    net: str | None = None,
    agent: str = "grok-bot",
) -> dict[str, Any]:
    chosen = net or network()
    return {
        "x402Version": X402_VERSION,
        "resource": {
            "url": f"botlink://spend/{note_id}",
            "description": "Bot Link agent spend",
            "mimeType": "application/json",
            "serviceName": "Bot Link",
            "tags": ["stellar", "x402", "usdc"],
        },
        "accepts": [
            {
                "scheme": SCHEME,
                "network": chosen,
                "asset": asset(chosen),
                "amount": atomic_amount(cents),
                "payTo": pay_to,
                "maxTimeoutSeconds": TIMEOUT_SECONDS,
                "extra": {"areFeesSponsored": True, "cents": cents, "agent": agent},
            }
        ],
        "error": "Payment required",
    }


def sep7_uri(cents: int, pay_to: str, note_id: str, net: str | None = None) -> str:
    chosen = net or network()
    memo = "bl:" + note_id.replace("-", "")[:8]
    msg = f"Bot Link spend ${cents // 100}.{cents % 100:02d}"
    params = [
        ("destination", pay_to),
        ("amount", display_amount(cents)),
        ("asset_code", ASSET_CODE),
    ]
    if chosen != NETWORK_TESTNET:
        params.append(("asset_issuer", USDC_ISSUER_PUBNET))
    params.extend(
        [
            ("memo", memo),
            ("msg", msg),
            ("origin_domain", ORIGIN_DOMAIN),
            ("callback", f"url:{CALLBACK}"),
        ]
    )
    query = "&".join(f"{k}={_enc(v)}" for k, v in params)
    return f"web+stellar:pay?{query}"


def sign_request(
    note_id: str,
    cents: int,
    pay_to: str,
    net: str | None = None,
    agent: str = "grok-bot",
) -> dict[str, Any]:
    chosen = net or network()
    return {
        "v": REQUEST_VERSION,
        "protocol": PROTOCOL,
        "x402Version": X402_VERSION,
        "paymentRequired": payment_required(note_id, cents, pay_to, chosen, agent),
        "sep7": sep7_uri(cents, pay_to, note_id, chosen),
        "callback": CALLBACK,
        "walletPackage": WALLET_PACKAGE,
    }


def example() -> dict[str, Any]:
    return sign_request(EXAMPLE_NOTE_ID, 40, EXAMPLE_PAY_TO, NETWORK_PUBNET, "grok-bot")


def dumps(obj: Any) -> str:
    return json.dumps(obj, separators=(",", ":"))
