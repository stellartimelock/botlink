#!/usr/bin/env python3
"""Bot Link PC companion — one process for Grok Bot and the other suite bots.

Double-click companion/BotLink.bat or companion/BotLink.sh.
Loopback only: http://127.0.0.1:18765
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import queue
import socket
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from apk_xfer import KINDS as APK_KINDS
from apk_xfer import MODE_REPLACE, MODE_UPGRADE
from apk_xfer import run_auto as apk_run_auto
from apk_xfer import run_send as apk_run_send
from horizon import submit_transaction
from relay import (
    QUOTA_MESSAGE,
    ROLE_DIAL,
    SERVICE,
    URL as RELAY_URL,
    allowance,
    blocked,
    companion_credential,
    decode_frame,
    encode_frame,
    join_message,
    parse_meter,
    usage_label,
)
from paths import config_path, spend_path
from spend import X402_ENABLED, decide as spend_decide
from x402 import EXAMPLE_PAY_TO, allow_example_pay_to, is_g_address, network as x402_network

APP = "botlink"
LAN_PORT = 18766
HTTP_PORT = 18765
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
SEED_KINDS = {"seed", "mnemonic", "secret", "secret-seed", "recovery"}
HERE = Path(__file__).resolve().parent
CONFIG_PATH = config_path()
SPEND_PATH = spend_path()
LEGACY_CONFIG = HERE / "botlink.local.json"
LEGACY_SPEND = HERE / "botlink-spend.json"
MAX_BLURB = 280
MAX_HTML = 100_000

notes: dict[str, dict[str, Any]] = {}
notes_lock = threading.Lock()
send_lock = threading.Lock()
apk_q: queue.Queue[dict[str, Any]] = queue.Queue()
lan_sock = None
relay_ws = None
relay_meter: dict[str, Any] | None = None
state = {
    "lan": "down",
    "relay": "down",
    "room": "",
    "pair": "",
}


def room_id(secret: bytes) -> str:
    return hashlib.sha256(b"botlink.room.v1|" + secret).hexdigest()


def aes_key(secret: bytes) -> bytes:
    return hashlib.sha256(b"botlink.key.v1|" + secret).digest()


def encrypt(key: bytes, plaintext: bytes) -> str:
    return base64.b64encode(encrypt_raw(key, plaintext)).decode("ascii")


def decrypt(key: bytes, wire: str) -> bytes:
    return decrypt_raw(key, base64.b64decode(wire))


def encrypt_raw(key: bytes, plaintext: bytes) -> bytes:
    iv = os.urandom(12)
    boxed = AESGCM(key).encrypt(iv, plaintext, None)
    return iv + boxed


def decrypt_raw(key: bytes, raw: bytes) -> bytes:
    if len(raw) <= 28:
        raise ValueError("ciphertext too short")
    return AESGCM(key).decrypt(raw[:12], raw[12:], None)


def crockford_decode(text: str) -> bytes:
    buffer = 0
    bits = 0
    out = bytearray()
    for ch in text:
        idx = ALPHABET.find(ch)
        if idx < 0:
            raise ValueError(f"bad pairing character: {ch}")
        buffer = (buffer << 5) | idx
        bits += 5
        if bits >= 8:
            bits -= 8
            out.append((buffer >> bits) & 0xFF)
    return bytes(out)


def parse_pair(raw: str) -> bytes:
    text = raw.strip().upper().replace(" ", "")
    if text.startswith("BL"):
        body = text.removeprefix("BL-").removeprefix("BL").replace("-", "")
        return crockford_decode(body)
    return bytes.fromhex(text)


def looks_like_seed(kind: str, text: str) -> bool:
    if kind.lower() in SEED_KINDS:
        return True
    words = [w for w in "".join(ch if ch.isalpha() else " " for ch in text.lower()).split() if w]
    return len(words) in {12, 15, 18, 21, 24} and all(3 <= len(w) <= 8 for w in words)


def store(note: dict[str, Any]) -> dict[str, Any]:
    with notes_lock:
        notes[note["id"]] = note
    return note


def month_key() -> str:
    return time.strftime("%Y-%m", time.gmtime())


def spend_ledger() -> dict[str, Any]:
    if not SPEND_PATH.exists() and LEGACY_SPEND.exists():
        try:
            SPEND_PATH.write_text(LEGACY_SPEND.read_text(encoding="utf-8"), encoding="utf-8")
        except OSError:
            pass
    if not SPEND_PATH.exists():
        return {"month": month_key(), "agents": {}}
    try:
        data = json.loads(SPEND_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        data = {"month": month_key(), "agents": {}}
    if data.get("month") != month_key():
        data = {"month": month_key(), "agents": {}}
    data.setdefault("agents", {})
    return data


def spent_for(agent: str) -> int:
    return int(spend_ledger()["agents"].get(agent, 0))


def add_spend(agent: str, cents: int) -> None:
    if cents <= 0:
        return
    data = spend_ledger()
    agents = data["agents"]
    agents[agent] = int(agents.get(agent, 0)) + cents
    SPEND_PATH.write_text(json.dumps(data), encoding="utf-8")


def html_page(title: str, body: str) -> str:
    if "<html" in body.lower():
        return body
    safe = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return (
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        "<style>body{margin:0;background:#0B1020;color:#E8EEF9;font:16px/1.45 sans-serif;padding:20px}"
        "h1{font-size:18px}a{color:#5EEAD4}</style></head>"
        f"<body><h1>{safe}</h1>{body}</body></html>"
    )


def apply_horizon(note: dict[str, Any], xdr: str, submitted: dict[str, Any]) -> None:
    note["signedXdr"] = xdr
    if submitted.get("ok"):
        if note.get("cents") and not note.get("paid"):
            add_spend(str(note.get("from") or "agent"), int(note["cents"]))
        note["paid"] = True
        note["txHash"] = submitted.get("hash")
        note["status"] = "approved"
        print(f"[x402] horizon {submitted.get('hash')}", flush=True)
    else:
        note["paid"] = False
        note["paymentError"] = submitted.get("message")
        print(f"[x402] horizon refused: {submitted.get('message')}", flush=True)


def handle_plain(obj: dict[str, Any]) -> None:
    kind = str(obj.get("kind") or "info")
    if kind in APK_KINDS:
        apk_q.put(obj)
        extra = obj.get("status") if obj.get("status") is not None else obj.get("next")
        print(f"[apk] {kind}" + (f" {extra}" if extra is not None else ""), flush=True)
        return
    text = str(obj.get("text") or "")
    if kind == "decision" and str(obj.get("status") or "").lower() == "approved":
        ref = str(obj.get("ref") or "")
        xdr = str(obj.get("signedXdr") or obj.get("xdr") or "")
        with notes_lock:
            original = notes.get(ref)
            already = bool(original and original.get("paid"))
        if original and original.get("cents") and xdr and not already:
            submitted = submit_transaction(xdr, x402_network())
            with notes_lock:
                apply_horizon(original, xdr, submitted)
        elif original and xdr:
            with notes_lock:
                original["signedXdr"] = xdr
    note = {
        "id": obj.get("id") or str(uuid.uuid4()),
        "from": obj.get("from") or "phone",
        "kind": kind,
        "text": text,
        "wantsAction": bool(obj.get("wantsAction")),
        "createdAt": obj.get("createdAt") or int(time.time() * 1000),
        "status": obj.get("status") or "pending",
        "ref": obj.get("ref"),
        "direction": "in",
    }
    if obj.get("html"):
        note["html"] = str(obj.get("html"))[:MAX_HTML]
    if obj.get("cents"):
        note["cents"] = int(obj["cents"])
    if obj.get("x402"):
        note["x402"] = obj["x402"]
    if obj.get("sep7"):
        note["sep7"] = str(obj["sep7"])
    if obj.get("signedXdr") or obj.get("xdr"):
        note["signedXdr"] = str(obj.get("signedXdr") or obj.get("xdr"))
    if obj.get("paid"):
        note["paid"] = True
    if looks_like_seed(kind, text):
        note["status"] = "rejected_seed"
        note["text"] = "Blocked: this channel does not carry seeds."
    store(note)
    print(f"[inbox] {note['from']} {note['status']}: {note['text'][:120]}", flush=True)


def transmit(key: bytes, plaintext: bytes) -> bool:
    payload = encrypt_raw(key, plaintext)
    frame = encode_frame(SERVICE, payload)
    sent = False
    with send_lock:
        global lan_sock
        if lan_sock is not None:
            try:
                lan_sock.sendall(frame)
                sent = True
            except OSError as exc:
                print(f"[lan] send failed: {exc}", flush=True)
                lan_sock = None
                state["lan"] = "down"
        ws = relay_ws
    if ws is not None and not relay_blocked():
        try:
            import asyncio

            asyncio.run_coroutine_threadsafe(ws.send(frame), ws_loop)
            sent = True
        except Exception as exc:  # noqa: BLE001
            print(f"[relay] send failed: {exc}", flush=True)
    return sent


def push(key: bytes, room: str, note: dict[str, Any]) -> None:
    sent = transmit(key, json.dumps(note).encode("utf-8"))
    if not sent:
        print("[pipe] stored locally; phone is not on the pipe yet", flush=True)


def pipe_up() -> bool:
    if lan_sock is not None:
        return True
    return relay_ws is not None and not relay_blocked()


def wait_apk(pred, timeout: float) -> dict[str, Any] | None:
    deadline = time.time() + timeout
    held: list[dict[str, Any]] = []
    found = None
    while time.time() < deadline:
        try:
            item = apk_q.get(timeout=0.2)
        except queue.Empty:
            continue
        if pred(item):
            found = item
            break
        held.append(item)
    for extra in held:
        apk_q.put(extra)
    return found


def relay_blocked() -> bool:
    policy = allowance(None, False, None)
    return blocked(relay_meter, policy)


def recvn(sock: socket.socket, count: int) -> bytes:
    buf = b""
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            raise RuntimeError("disconnected")
        buf += chunk
    return buf


def read_text_line(sock: socket.socket) -> str:
    buf = b""
    while True:
        char = recvn(sock, 1)
        if char == b"\n":
            return buf.decode("utf-8").rstrip("\r")
        buf += char


def read_frame(sock: socket.socket) -> tuple[int, bytes]:
    header = recvn(sock, 12)
    length = int.from_bytes(header[8:12], "big")
    rest = recvn(sock, length) if length else b""
    decoded = decode_frame(header + rest)
    if decoded is None:
        raise RuntimeError("bad frame")
    return decoded


def lan_loop(host: str, room: str, key: bytes) -> None:
    global lan_sock
    while True:
        try:
            sock = socket.create_connection((host, LAN_PORT), timeout=8)
            sock.settimeout(None)
            hello = json.dumps({"v": 1, "op": "hello", "app": APP, "role": "pc", "room": room}) + "\n"
            sock.sendall(hello.encode("utf-8"))
            ack = read_text_line(sock)
            obj = json.loads(ack or "{}")
            if obj.get("op") not in {"hello-ok", "hello"}:
                raise RuntimeError(f"unexpected hello: {ack!r}")
            lan_sock = sock
            state["lan"] = f"up {host}:{LAN_PORT}"
            print(f"[lan] connected {host}:{LAN_PORT}", flush=True)
            while True:
                msg_type, payload = read_frame(sock)
                if msg_type == SERVICE and payload:
                    plain = decrypt_raw(key, payload)
                    handle_plain(json.loads(plain.decode("utf-8")))
        except Exception as exc:  # noqa: BLE001
            lan_sock = None
            state["lan"] = f"retry ({exc})"
            time.sleep(2.5)


ws_loop = None


def start_relay(room: str, key: bytes) -> None:
    import asyncio
    import websockets

    async def run() -> None:
        global relay_ws, ws_loop, relay_meter
        ws_loop = asyncio.get_running_loop()
        while True:
            try:
                async with websockets.connect(RELAY_URL, open_timeout=20, ping_interval=20) as ws:
                    await ws.send(join_message(room, ROLE_DIAL, companion_credential()))
                    first = await asyncio.wait_for(ws.recv(), 20)
                    if not isinstance(first, str):
                        raise RuntimeError("relay did not send a meter")
                    meter = parse_meter(first)
                    if not meter or not meter["ok"] or meter.get("error") == "quota":
                        message = (meter or {}).get("message") or QUOTA_MESSAGE
                        relay_meter = meter
                        state["relay"] = message
                        print(f"[relay] {message}", flush=True)
                        await asyncio.sleep(30)
                        continue
                    relay_ws = ws
                    relay_meter = meter
                    policy = allowance(None, False, None)
                    state["relay"] = usage_label(int(meter["used"]), int(meter["limit"] or policy["limit"]))
                    print(f"[relay] {state['relay']}", flush=True)
                    async for raw in ws:
                        if isinstance(raw, str):
                            update = parse_meter(raw)
                            if update and update.get("error") == "quota":
                                relay_meter = update
                                state["relay"] = update.get("message") or QUOTA_MESSAGE
                                break
                            if update and update["ok"]:
                                relay_meter = update
                            continue
                        decoded = decode_frame(raw)
                        if not decoded or decoded[0] != SERVICE:
                            continue
                        try:
                            plain = decrypt_raw(key, decoded[1])
                            handle_plain(json.loads(plain.decode("utf-8")))
                        except Exception as exc:  # noqa: BLE001
                            print(f"[relay] dropped frame: {exc}", flush=True)
            except Exception as exc:  # noqa: BLE001
                relay_ws = None
                state["relay"] = f"down ({exc})"
                await asyncio.sleep(4)

    threading.Thread(target=lambda: asyncio.run(run()), name="botlink-relay", daemon=True).start()


class Handler(BaseHTTPRequestHandler):
    key: bytes = b""
    room: str = ""
    http_port: int = HTTP_PORT

    def log_message(self, fmt: str, *args: Any) -> None:
        print("[http] " + (fmt % args), flush=True)

    def _json(self, code: int, payload: Any) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, code: int, page: str) -> None:
        body = page.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any] | None:
        length = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._json(400, {"error": "invalid json"})
            return None
        if not isinstance(body, dict):
            self._json(400, {"error": "invalid json"})
            return None
        return body

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/v1/status":
            with notes_lock:
                pending = sum(1 for n in notes.values() if n.get("status") in {"pending", "held"})
            self._json(200, {**state, "pending": pending, "notes": len(notes), "x402": X402_ENABLED})
            return
        if path == "/v1/notes":
            with notes_lock:
                self._json(200, list(notes.values()))
            return
        if path == "/v1/room":
            with notes_lock:
                blurbs = [n for n in notes.values() if n.get("kind") == "summary"]
            self._json(200, blurbs)
            return
        if path.startswith("/v1/room/"):
            note_id = path.rsplit("/", 1)[-1]
            with notes_lock:
                note = notes.get(note_id)
            if not note or note.get("kind") != "summary":
                self._json(404, {"error": "not found"})
                return
            self._html(200, html_page(str(note.get("text") or "AI room"), str(note.get("html") or "")))
            return
        if path.startswith("/v1/notes/"):
            note_id = path.rsplit("/", 1)[-1]
            with notes_lock:
                note = notes.get(note_id)
            if not note:
                self._json(404, {"error": "not found"})
                return
            self._json(200, note)
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/v1/room":
            self.post_room()
            return
        if path == "/v1/spend":
            self.post_spend()
            return
        if path == "/v1/x402/signed":
            self.post_signed()
            return
        if path != "/v1/notes":
            self._json(404, {"error": "not found"})
            return
        body = self._read_json()
        if body is None:
            return
        kind = str(body.get("kind") or "ask")
        text = str(body.get("text") or "").strip()
        if not text:
            self._json(400, {"error": "text required"})
            return
        if looks_like_seed(kind, text):
            self._json(400, {"error": "no seeds on this channel"})
            return
        note = {
            "id": str(uuid.uuid4()),
            "from": str(body.get("from") or "grok-bot"),
            "kind": kind,
            "text": text[:2000],
            "wantsAction": bool(body.get("wantsAction", kind in {"act", "ask"})),
            "createdAt": int(time.time() * 1000),
            "status": "pending_approval",
            "direction": "out",
        }
        store(note)
        push(self.key, self.room, {**note, "status": "pending"})
        self._json(202, note)

    def post_room(self) -> None:
        body = self._read_json()
        if body is None:
            return
        summary = str(body.get("summary") or body.get("text") or "").strip()
        html = str(body.get("html") or "").strip()
        if not summary or not html:
            self._json(400, {"error": "summary and html required"})
            return
        if looks_like_seed("summary", summary) or looks_like_seed("summary", html):
            self._json(400, {"error": "no seeds on this channel"})
            return
        note_id = str(uuid.uuid4())
        note = {
            "id": note_id,
            "from": str(body.get("from") or "grok-bot"),
            "kind": "summary",
            "text": summary[:MAX_BLURB],
            "html": html[:MAX_HTML],
            "wantsAction": False,
            "createdAt": int(time.time() * 1000),
            "status": "sent",
            "direction": "out",
        }
        store(note)
        push(self.key, self.room, {**note, "status": "sent"})
        url = f"http://127.0.0.1:{self.http_port}/v1/room/{note_id}"
        self._json(202, {**note, "url": url})

    def post_spend(self) -> None:
        body = self._read_json()
        if body is None:
            return
        agent = str(body.get("from") or "grok-bot")
        try:
            cents = int(body.get("cents"))
        except (TypeError, ValueError):
            self._json(400, {"error": "cents required"})
            return
        reason = str(body.get("reason") or "").strip()
        if looks_like_seed("act", reason):
            self._json(400, {"error": "no seeds on this channel"})
            return
        pay_to = str(body.get("payTo") or saved_pay_to() or "").strip()
        if not is_g_address(pay_to):
            self._json(
                400,
                {
                    "error": "payTo required",
                    "message": "Set the house Stellar G-address (--pay-to / BOTLINK_X402_PAY_TO). x402 will not pay a blank destination.",
                },
            )
            return
        if pay_to == EXAMPLE_PAY_TO and not allow_example_pay_to():
            self._json(
                400,
                {
                    "error": "example payTo",
                    "message": "That is the spec example address, not the house account. Pass --pay-to.",
                },
            )
            return
        note_id = str(uuid.uuid4())
        verdict = spend_decide(agent, cents, spent_for(agent), note_id, pay_to, x402_network())
        if not verdict["ok"]:
            self._json(403, verdict)
            return
        request = verdict.get("request") or {}
        dollars = f"${cents / 100:.2f}"
        text = f"Spend {dollars}" + (f" · {reason}" if reason else "")
        note = {
            "id": note_id,
            "from": agent,
            "kind": "act",
            "text": text[:2000],
            "wantsAction": True,
            "cents": cents,
            "createdAt": int(time.time() * 1000),
            "status": "pending_approval",
            "direction": "out",
            "x402": request,
            "sep7": request.get("sep7"),
            "paid": False,
        }
        store(note)
        push(self.key, self.room, {**note, "status": "pending"})
        self._json(202, {**note, **verdict, "paid": False})

    def post_signed(self) -> None:
        body = self._read_json()
        if body is None:
            return
        xdr = str(body.get("signedXdr") or body.get("xdr") or "").strip()
        note_id = str(body.get("id") or body.get("ref") or "").strip()
        if not xdr:
            self._json(400, {"error": "signedXdr required"})
            return
        with notes_lock:
            note = notes.get(note_id) if note_id else None
            if note is None:
                for item in notes.values():
                    if item.get("cents") and not item.get("paid"):
                        note = item
                        break
            if note is None:
                self._json(404, {"error": "not found"})
                return
            already = bool(note.get("paid"))
            target = note
        if already:
            target["signedXdr"] = xdr
            self._json(200, target)
            return
        submitted = submit_transaction(xdr, x402_network())
        with notes_lock:
            apply_horizon(target, xdr, submitted)
        self._json(200 if submitted.get("ok") else 502, target)


def _read_json_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    if not isinstance(data, dict):
        return {}
    return {str(k): str(v) for k, v in data.items() if v is not None}


def load_config() -> dict[str, str]:
    data = _read_json_file(CONFIG_PATH)
    if data:
        return data
    legacy = _read_json_file(LEGACY_CONFIG)
    if legacy:
        save_config(legacy.get("pair", ""), legacy.get("phone", ""), legacy.get("payTo", ""))
        return _read_json_file(CONFIG_PATH)
    return {}


def saved_pay_to() -> str:
    env = os.environ.get("BOTLINK_X402_PAY_TO", "").strip()
    if env:
        return env
    return load_config().get("payTo", "").strip()


def save_config(pair: str, phone: str, pay_to: str | None = None) -> None:
    payload = {"pair": pair, "phone": phone}
    dest = (pay_to if pay_to is not None else saved_pay_to()).strip()
    if dest:
        payload["payTo"] = dest
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(payload), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Bot Link PC companion")
    parser.add_argument("--pair", default="", help="BL- code. Saved after the first run.")
    parser.add_argument("--phone", default="", help="Phone IPv4 on the same Wi-Fi")
    parser.add_argument("--pay-to", default="", help="House Stellar G-address for x402. Not a secret.")
    parser.add_argument("--no-relay", action="store_true", help="LAN only")
    parser.add_argument("--http-port", type=int, default=HTTP_PORT)
    apk = parser.add_mutually_exclusive_group()
    apk.add_argument(
        "--send-apk",
        default="",
        metavar="PATH",
        help="Send a FlipSend APK (same cert 7994727d, higher versionCode). One Install tap. Never uninstall.",
    )
    apk.add_argument(
        "--replace-apk",
        default="",
        metavar="PATH",
        help="Send a Stellar suite APK with kind=replace. Wrong-key installs are uninstalled then installed (two Android confirms).",
    )
    apk.add_argument(
        "--auto",
        default="",
        metavar="PATH",
        help="Read the APK, ask the phone for installed cert + versionCode, then send-apk or replace-apk.",
    )
    args = parser.parse_args()

    saved = load_config()
    pair = args.pair.strip() or saved.get("pair", "")
    phone = args.phone.strip() or saved.get("phone", "")
    pay_to = args.pay_to.strip() or saved.get("payTo", "") or os.environ.get("BOTLINK_X402_PAY_TO", "").strip()
    if not pair:
        if not sys.stdin.isatty():
            print("Run companion/BotLink.bat or companion/BotLink.sh and enter the code from the phone.", flush=True)
            return 2
        pair = input("Pairing code from the phone: ").strip()
    if not pair:
        print("A pairing code is required.", flush=True)
        return 2
    if not phone and sys.stdin.isatty() and not args.phone:
        phone = input("Phone IP on this Wi-Fi (blank to use the relay only): ").strip()
    save_config(pair, phone, pay_to)

    secret = parse_pair(pair)
    key = aes_key(secret)
    room = room_id(secret)
    state["room"] = room
    state["pair"] = pair.strip().upper()
    Handler.key = key
    Handler.room = room
    Handler.http_port = args.http_port

    if phone:
        threading.Thread(target=lan_loop, args=(phone, room, key), daemon=True).start()
    if not args.no_relay:
        start_relay(room, key)

    apk_path = args.send_apk or args.replace_apk or args.auto
    if apk_path:
        send = lambda obj: transmit(key, json.dumps(obj).encode("utf-8"))
        if args.auto:
            result = apk_run_auto(apk_path, send=send, wait=wait_apk, pipe_up=pipe_up)
        else:
            result = apk_run_send(
                apk_path,
                send=send,
                wait=wait_apk,
                pipe_up=pipe_up,
                mode=MODE_REPLACE if args.replace_apk else MODE_UPGRADE,
            )
        print(f"[apk] {result.get('status')}: {result.get('message')}", flush=True)
        return 0 if result.get("ok") else 1

    httpd = ThreadingHTTPServer(("127.0.0.1", args.http_port), Handler)
    print(
        f"Bot Link companion on http://127.0.0.1:{args.http_port}\n"
        f"  pair {state['pair']}\n"
        f"  room {room[:16]}…\n"
        "  POST /v1/notes   POST /v1/room   POST /v1/spend   POST /v1/x402/signed\n"
        "  Spend cap: $1 a call, $5 an agent each month.\n"
        "  x402 on. StellarTimeLock signs via SEP-7. Horizon submit after sign. Bot Link never holds keys.\n"
        "  FlipSend APK: BotLink.bat --send-apk path\\FlipSend-x.y.z-main.apk\n"
        "  Key switch:   BotLink.bat --replace-apk path\\Market.apk\n"
        "  Auto pick:    BotLink.bat --auto path\\app.apk\n"
        f"  Saves: {CONFIG_PATH.parent}\n"
        "  Nothing acts until the phone taps Approve.",
        flush=True,
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
