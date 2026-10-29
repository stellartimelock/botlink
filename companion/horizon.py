"""Submit a signed Stellar transaction. Bot Link never holds keys."""

from __future__ import annotations

import json
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from x402 import NETWORK_TESTNET, network as x402_network

HORIZON_PUBNET = "https://horizon.stellar.org"
HORIZON_TESTNET = "https://horizon-testnet.stellar.org"

PostFn = Callable[[str, bytes, dict[str, str]], tuple[int, bytes]]


def horizon_base(network: str | None = None) -> str:
    chosen = network or x402_network()
    return HORIZON_TESTNET if chosen == NETWORK_TESTNET else HORIZON_PUBNET


def submit_transaction(
    xdr: str,
    network: str | None = None,
    *,
    post: PostFn | None = None,
) -> dict[str, Any]:
    """POST signed XDR to Horizon. Returns {ok, hash?, message?}."""
    tx = (xdr or "").strip()
    if not tx:
        return {"ok": False, "code": "xdr", "message": "signedXdr required"}
    url = horizon_base(network) + "/transactions"
    body = urlencode({"tx": tx}).encode("utf-8")
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json",
    }
    try:
        status, raw = (post or _http_post)(url, body, headers)
    except URLError as exc:
        return {"ok": False, "code": "horizon", "message": f"Horizon unreachable: {exc}"}
    try:
        parsed = json.loads(raw.decode("utf-8") or "{}")
    except json.JSONDecodeError:
        parsed = {}
    if status in {200, 201} and parsed.get("hash"):
        return {
            "ok": True,
            "code": "submitted",
            "hash": str(parsed["hash"]),
            "message": "Submitted to Horizon.",
            "successful": parsed.get("successful", True),
        }
    extras = parsed.get("extras") if isinstance(parsed, dict) else None
    codes = ""
    if isinstance(extras, dict):
        result_codes = extras.get("result_codes")
        if isinstance(result_codes, dict):
            codes = str(result_codes.get("transaction") or result_codes)
        elif extras.get("envelope_xdr"):
            codes = str(extras.get("reason") or extras.get("result_code") or "")
    title = str(parsed.get("detail") or parsed.get("title") or "")
    if title and codes:
        message = f"{title} ({codes})"
    else:
        message = title or codes or f"Horizon HTTP {status}"
    already = "already" in message.lower() or codes in {"tx_too_late"} and parsed.get("hash")
    if parsed.get("hash") and (status == 400) and "already included" in json.dumps(parsed).lower():
        return {
            "ok": True,
            "code": "submitted",
            "hash": str(parsed["hash"]),
            "message": "Already on the ledger.",
        }
    if already and parsed.get("hash"):
        return {"ok": True, "code": "submitted", "hash": str(parsed["hash"]), "message": message}
    return {"ok": False, "code": "horizon", "message": message, "status": status}


def _http_post(url: str, body: bytes, headers: dict[str, str]) -> tuple[int, bytes]:
    req = Request(url, data=body, headers=headers, method="POST")
    try:
        with urlopen(req, timeout=30) as resp:
            return resp.getcode(), resp.read()
    except HTTPError as exc:
        return exc.code, exc.read()
