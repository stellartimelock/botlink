"""Agent spend caps plus an x402 / SEP-7 signing request. No keys here."""

from __future__ import annotations

from typing import Any

from relay import SPEND_ACTION_CENTS, SPEND_MONTH_CENTS
from x402 import is_g_address, sign_request

X402_ENABLED = True


def decide(
    agent: str,
    cents: int,
    spent_this_month: int,
    note_id: str = "pending",
    pay_to: str = "",
    network: str | None = None,
) -> dict[str, Any]:
    if not agent.strip():
        return {"ok": False, "code": "cap", "message": "Name the agent.", "allowedCents": 0}
    if cents <= 0:
        return {"ok": False, "code": "cap", "message": "Enter an amount.", "allowedCents": 0}
    if cents > SPEND_ACTION_CENTS:
        return {"ok": False, "code": "cap", "message": "Cap is $1 a call.", "allowedCents": 0}
    if spent_this_month + cents > SPEND_MONTH_CENTS:
        return {"ok": False, "code": "cap", "message": "Cap is $5 an agent each month.", "allowedCents": 0}
    if not is_g_address(pay_to):
        return {
            "ok": False,
            "code": "pay_to",
            "message": "Set the house Stellar G-address (--pay-to / BOTLINK_X402_PAY_TO). x402 will not pay a blank destination.",
            "allowedCents": 0,
        }
    request = sign_request(note_id or "pending", cents, pay_to, network, agent)
    return {
        "ok": True,
        "code": "x402_sign",
        "message": "Within the cap. Approve on the phone, then StellarTimeLock signs. Bot Link never holds keys.",
        "allowedCents": cents,
        "request": request,
    }
