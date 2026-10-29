"""APK chunk transfer over Bot Link's encrypted sidecar.

Uses the same AES-GCM SSND notes as everything else. Never FlipSend's file room.
`--send-apk` is FlipSend same-cert upgrades. `--replace-apk` uses kind=replace
for Stellar suite key-switch reinstalls. `--auto` asks the phone for the
installed cert + versionCode and picks between them.
"""

from __future__ import annotations

import hashlib
import math
import time
import uuid
from pathlib import Path
from typing import Any, Callable

VERSION = 1
CHUNK_SIZE = 96 * 1024
KIND_OFFER = "apk-offer"
KIND_REPLACE = "replace"
KIND_CHUNK = "apk-chunk"
KIND_ACK = "apk-ack"
KIND_DONE = "apk-done"
KIND_RESULT = "apk-result"
KIND_QUERY = "apk-query"
KIND_INFO = "apk-info"
KINDS = {
    KIND_OFFER,
    KIND_REPLACE,
    KIND_CHUNK,
    KIND_ACK,
    KIND_DONE,
    KIND_RESULT,
    KIND_QUERY,
    KIND_INFO,
}

MODE_UPGRADE = "upgrade"
MODE_REPLACE = "replace"

TARGET_PACKAGE = "org.stellartimelock.stellarsend"
SELF_PACKAGE = "org.stellartimelock.botlink"
SIGNER_PREFIX = "7994727d"
HOUSE_SIGNER = "fac61745"
ALLOWED_SIGNERS = (HOUSE_SIGNER, SIGNER_PREFIX)
# Stellar suite packages Bot Link may uninstall/reinstall. Documented constant.
STELLAR_PREFIXES = ("org.stellartimelock.", "com.stellartimelock.", "app.stellar.")

INTERMEDIATE_STATUSES = {"install_pending", "replace_pending", "uninstalled"}
REFUSE_STATUSES = {
    "rejected_package",
    "rejected_signer",
    "rejected_version",
    "rejected_missing",
    "rejected_hash",
    "rejected_offer",
    "rejected_self",
    "install_failed",
    "timeout",
    "no_pipe",
    "send_failed",
    "missing",
    "apk_missing",
}

WaitFn = Callable[[Callable[[dict[str, Any]], bool], float], dict[str, Any] | None]
SendFn = Callable[[dict[str, Any]], bool]
PipeFn = Callable[[], bool]
LogFn = Callable[[str], None]


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def chunk_count(size: int, chunk_size: int = CHUNK_SIZE) -> int:
    if size < 0:
        raise ValueError("size must be >= 0")
    if chunk_size <= 0:
        raise ValueError("chunk_size must be > 0")
    if size == 0:
        return 0
    return math.ceil(size / chunk_size)


def chunk_slice(size: int, index: int, chunk_size: int = CHUNK_SIZE) -> tuple[int, int]:
    if index < 0:
        raise ValueError("index must be >= 0")
    offset = index * chunk_size
    if offset >= size:
        raise ValueError("index past end of file")
    length = min(chunk_size, size - offset)
    return offset, length


def next_index(have: set[int], chunks: int) -> int:
    """First missing chunk index, or `chunks` when the file is complete."""
    if chunks < 0:
        raise ValueError("chunks must be >= 0")
    idx = 0
    while idx < chunks and idx in have:
        idx += 1
    return idx


def normalize_cert(sha256: str) -> str:
    return sha256.lower().replace(":", "").replace(" ", "").strip()


def allowed_package(package_name: str) -> bool:
    if not package_name or package_name == SELF_PACKAGE:
        return False
    return any(
        package_name.startswith(prefix) or package_name == prefix.rstrip(".")
        for prefix in STELLAR_PREFIXES
    )


def allowed_signer(sha256: str) -> bool:
    n = normalize_cert(sha256)
    return any(n.startswith(prefix) for prefix in ALLOWED_SIGNERS)


def same_cert(a: str, b: str) -> bool:
    return normalize_cert(a) == normalize_cert(b)


def replace_check(incoming: dict[str, Any], installed: dict[str, Any] | None) -> dict[str, Any]:
    """Phone-side replace gate, mirrored so `--auto` can decide before sending."""
    pkg = str(incoming.get("package") or "")
    cert = str(incoming.get("certSha256") or "")
    vc = int(incoming.get("versionCode") or 0)
    if not allowed_package(pkg):
        status = "rejected_self" if pkg == SELF_PACKAGE else "rejected_package"
        return {"ok": False, "status": f"failed:{status}", "action": None, "message": f"Refused {pkg}."}
    if not allowed_signer(cert):
        return {
            "ok": False,
            "status": "failed:rejected_signer",
            "action": None,
            "message": f"Refused signer {normalize_cert(cert)[:8]}…",
        }
    if installed is None:
        if pkg == TARGET_PACKAGE:
            return {
                "ok": False,
                "status": "failed:rejected_missing",
                "action": None,
                "message": "FlipSend is not installed. Bot Link will not sideload a first copy.",
            }
        return {
            "ok": True,
            "status": "ok",
            "action": "install",
            "message": f"{pkg} is not installed. Will sideload.",
        }
    if str(installed.get("package") or pkg) != pkg:
        return {"ok": False, "status": "failed:rejected_package", "action": None, "message": "Package mismatch."}
    if same_cert(cert, str(installed.get("certSha256") or "")):
        inst_vc = int(installed.get("versionCode") or 0)
        if vc <= inst_vc:
            return {
                "ok": False,
                "status": "failed:rejected_version",
                "action": None,
                "message": f"Never downgrade. Incoming {vc} is not higher than {inst_vc}.",
            }
        return {
            "ok": True,
            "status": "ok",
            "action": "upgrade",
            "message": f"{pkg} {vc} is newer on the same cert.",
        }
    return {
        "ok": True,
        "status": "ok",
        "action": "reinstall",
        "message": f"{pkg} is on a different cert. Uninstall, then install.",
    }


def decide_mode(incoming: dict[str, Any], installed: dict[str, Any] | None) -> tuple[str, str]:
    """Pick send vs replace from APK meta + the phone's installed cert/versionCode."""
    pkg = str(incoming.get("package") or "")
    cert = normalize_cert(str(incoming.get("certSha256") or ""))
    vc = int(incoming.get("versionCode") or 0)
    if pkg == TARGET_PACKAGE and installed is not None:
        inst_cert = normalize_cert(str(installed.get("certSha256") or ""))
        inst_vc = int(installed.get("versionCode") or 0)
        if cert.startswith(SIGNER_PREFIX) and inst_cert == cert and vc > inst_vc:
            return MODE_UPGRADE, "FlipSend same-cert upgrade"
    verdict = replace_check(incoming, installed)
    if verdict["ok"]:
        return MODE_REPLACE, str(verdict["message"])
    return "refuse", str(verdict["status"])


def query_message(package: str, query_id: str | None = None) -> dict[str, Any]:
    return {
        "v": VERSION,
        "kind": KIND_QUERY,
        "id": query_id or str(uuid.uuid4()),
        "package": package,
        "createdAt": int(time.time() * 1000),
    }


def info_message(
    query_id: str,
    package: str,
    *,
    installed: bool,
    cert_sha256: str | None = None,
    version_code: int | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "v": VERSION,
        "kind": KIND_INFO,
        "id": query_id,
        "package": package,
        "installed": installed,
    }
    if cert_sha256:
        body["certSha256"] = cert_sha256
    if version_code is not None:
        body["versionCode"] = version_code
    return body


def is_terminal_status(status: str, ok: bool | None = None) -> bool:
    if ok is True:
        return True
    text = str(status or "")
    if text in INTERMEDIATE_STATUSES:
        return False
    if text.startswith("failed:"):
        return True
    if text in REFUSE_STATUSES:
        return True
    if text == "installed":
        return True
    return False


def offer_message(
    path: str | Path,
    *,
    transfer_id: str | None = None,
    chunk_size: int = CHUNK_SIZE,
    mode: str = MODE_UPGRADE,
    package: str | None = None,
) -> dict[str, Any]:
    file_path = Path(path)
    size = file_path.stat().st_size
    replace = mode == MODE_REPLACE
    return {
        "v": VERSION,
        "kind": KIND_REPLACE if replace else KIND_OFFER,
        "mode": MODE_REPLACE if replace else MODE_UPGRADE,
        "id": transfer_id or str(uuid.uuid4()),
        "name": file_path.name,
        "size": size,
        "sha256": sha256_file(file_path),
        "chunkSize": chunk_size,
        "chunks": chunk_count(size, chunk_size),
        "package": package or ("" if replace else TARGET_PACKAGE),
    }


def chunk_message(transfer_id: str, index: int, data: bytes) -> dict[str, Any]:
    return {
        "v": VERSION,
        "kind": KIND_CHUNK,
        "id": transfer_id,
        "index": index,
        "offset": index,  # phone uses index * chunkSize; offset kept for logs
        "sha256": sha256_bytes(data),
        "data": _b64(data),
    }


def ack_message(transfer_id: str, nxt: int, got: int | None = None) -> dict[str, Any]:
    body: dict[str, Any] = {"v": VERSION, "kind": KIND_ACK, "id": transfer_id, "next": nxt}
    if got is not None:
        body["got"] = got
    return body


def done_message(transfer_id: str, digest: str) -> dict[str, Any]:
    return {"v": VERSION, "kind": KIND_DONE, "id": transfer_id, "sha256": digest}


def result_message(transfer_id: str, ok: bool, status: str, message: str) -> dict[str, Any]:
    return {
        "v": VERSION,
        "kind": KIND_RESULT,
        "id": transfer_id,
        "ok": ok,
        "status": status,
        "message": message,
    }


def read_chunk(path: str | Path, index: int, chunk_size: int = CHUNK_SIZE) -> bytes:
    offset, length = chunk_slice(Path(path).stat().st_size, index, chunk_size)
    with Path(path).open("rb") as handle:
        handle.seek(offset)
        data = handle.read(length)
    if len(data) != length:
        raise RuntimeError("short APK read")
    return data


class Assembler:
    """In-memory receiver used by tests and as the resume model."""

    def __init__(self, size: int, digest: str, chunk_size: int = CHUNK_SIZE) -> None:
        self.size = size
        self.digest = digest.lower()
        self.chunk_size = chunk_size
        self.chunks = chunk_count(size, chunk_size)
        self.buf = bytearray(size)
        self.have: set[int] = set()

    def accept(self, index: int, data: bytes) -> int:
        offset, length = chunk_slice(self.size, index, self.chunk_size)
        if len(data) != length:
            raise ValueError(f"chunk {index} length {len(data)} != {length}")
        self.buf[offset : offset + length] = data
        self.have.add(index)
        return self.next_index()

    def next_index(self) -> int:
        return next_index(self.have, self.chunks)

    def complete(self) -> bool:
        return self.next_index() == self.chunks

    def digest_ok(self) -> bool:
        return sha256_bytes(bytes(self.buf)) == self.digest


def encoded_chunk_fits(data: bytes, max_plain: int = 200_000) -> bool:
    """True when the JSON+base64 chunk stays well under the SSND 256 KiB cap."""
    import json

    packed = json.dumps(chunk_message("id", 0, data), separators=(",", ":"))
    return len(packed.encode("utf-8")) <= max_plain


def run_query(
    package: str,
    *,
    send: SendFn,
    wait: WaitFn,
    pipe_up: PipeFn,
    log: LogFn = print,
    timeout: float = 20.0,
    connect_timeout: float = 45.0,
) -> dict[str, Any] | None:
    if not _wait_pipe(pipe_up, connect_timeout):
        return None
    msg = query_message(package)
    query_id = str(msg["id"])
    log(f"[apk] query installed {package}")
    if not send(msg):
        return None
    return wait(lambda m: _same(m, KIND_INFO, query_id), timeout)


def run_auto(
    path: str | Path,
    *,
    send: SendFn,
    wait: WaitFn,
    pipe_up: PipeFn,
    log: LogFn = print,
    chunk_size: int = CHUNK_SIZE,
    ack_timeout: float = 20.0,
    result_timeout: float = 900.0,
    connect_timeout: float = 45.0,
) -> dict[str, Any]:
    from apk_meta import ApkMetaError, read_apk_meta

    file_path = Path(path)
    if not file_path.is_file():
        return result_message("", False, "missing", f"APK not found: {file_path}")
    try:
        incoming = read_apk_meta(file_path)
    except ApkMetaError as exc:
        log(f"[apk] could not read APK meta ({exc}); sending as replace and letting the phone gate")
        return run_send(
            file_path,
            send=send,
            wait=wait,
            pipe_up=pipe_up,
            log=log,
            chunk_size=chunk_size,
            ack_timeout=ack_timeout,
            result_timeout=result_timeout,
            connect_timeout=connect_timeout,
            mode=MODE_REPLACE,
        )
    log(
        f"[apk] {incoming['package']} versionCode {incoming['versionCode']} "
        f"cert {incoming['certSha256'][:8]}…",
    )
    info = run_query(
        str(incoming["package"]),
        send=send,
        wait=wait,
        pipe_up=pipe_up,
        log=log,
        connect_timeout=connect_timeout,
    )
    installed = None
    if info and info.get("installed"):
        installed = {
            "package": str(info.get("package") or incoming["package"]),
            "certSha256": str(info.get("certSha256") or ""),
            "versionCode": int(info.get("versionCode") or 0),
        }
        log(f"[apk] phone has cert {installed['certSha256'][:8]}… versionCode {installed['versionCode']}")
    elif info is None:
        return result_message("", False, "timeout", "Phone did not report the installed cert.")
    else:
        log("[apk] phone does not have this package")
    mode, reason = decide_mode(incoming, installed)
    log(f"[apk] auto → {mode}: {reason}")
    if mode == "refuse":
        return result_message("", False, reason, reason)
    return run_send(
        file_path,
        send=send,
        wait=wait,
        pipe_up=pipe_up,
        log=log,
        chunk_size=chunk_size,
        ack_timeout=ack_timeout,
        result_timeout=result_timeout,
        connect_timeout=connect_timeout,
        mode=mode,
        package=str(incoming["package"]),
    )


def run_send(
    path: str | Path,
    *,
    send: SendFn,
    wait: WaitFn,
    pipe_up: PipeFn,
    log: LogFn = print,
    chunk_size: int = CHUNK_SIZE,
    ack_timeout: float = 20.0,
    result_timeout: float = 600.0,
    connect_timeout: float = 45.0,
    mode: str = MODE_UPGRADE,
    package: str | None = None,
) -> dict[str, Any]:
    file_path = Path(path)
    if not file_path.is_file():
        return result_message("", False, "missing", f"APK not found: {file_path}")
    offer = offer_message(file_path, chunk_size=chunk_size, mode=mode, package=package)
    transfer_id = str(offer["id"])
    chunks = int(offer["chunks"])
    digest = str(offer["sha256"])
    log(
        f"[apk] {offer['kind']} {file_path.name} {offer['size']} bytes "
        f"{chunks} chunks sha256={digest[:16]}…",
    )
    if not _wait_pipe(pipe_up, connect_timeout):
        return result_message(transfer_id, False, "no_pipe", "Phone is not on the Bot Link pipe.")

    next_needed = 0
    tries = 0
    while next_needed < chunks:
        if not pipe_up():
            log("[apk] pipe down; waiting to resume")
            if not _wait_pipe(pipe_up, connect_timeout):
                return result_message(transfer_id, False, "no_pipe", "Pipe dropped and did not come back.")
        if not send(offer):
            time.sleep(1.0)
            tries += 1
            if tries > 8:
                return result_message(transfer_id, False, "send_failed", "Could not send the offer.")
            continue
        ack = wait(lambda m: _same(m, KIND_ACK, transfer_id), ack_timeout)
        if ack is None:
            log("[apk] no ack; retrying offer")
            tries += 1
            if tries > 12:
                return result_message(transfer_id, False, "timeout", "Phone did not ack the offer.")
            continue
        next_needed = int(ack.get("next") or 0)
        tries = 0
        while next_needed < chunks:
            if not pipe_up():
                break
            data = read_chunk(file_path, next_needed, chunk_size)
            if not send(chunk_message(transfer_id, next_needed, data)):
                log(f"[apk] chunk {next_needed} send failed; will resume")
                break
            ack = wait(lambda m: _same(m, KIND_ACK, transfer_id), ack_timeout)
            if ack is None:
                log(f"[apk] no ack after chunk {next_needed}; will resume")
                break
            nxt = int(ack.get("next") or 0)
            log(f"[apk] {nxt}/{chunks}")
            next_needed = nxt
            tries = 0

    if not send(done_message(transfer_id, digest)):
        return result_message(transfer_id, False, "send_failed", "Could not send the done mark.")
    deadline = time.time() + result_timeout
    last: dict[str, Any] | None = None
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            return last or result_message(
                transfer_id,
                False,
                "timeout",
                "Phone received the APK but did not report a final result.",
            )
        result = wait(lambda m: _same(m, KIND_RESULT, transfer_id), remaining)
        if result is None:
            return last or result_message(
                transfer_id,
                False,
                "timeout",
                "Phone received the APK but did not report a final result.",
            )
        last = result
        status = str(result.get("status") or "")
        log(f"[apk] {status}: {result.get('message')}")
        if is_terminal_status(status, bool(result.get("ok"))):
            return result


def _same(msg: dict[str, Any], kind: str, transfer_id: str) -> bool:
    return str(msg.get("kind") or "") == kind and str(msg.get("id") or "") == transfer_id


def _wait_pipe(pipe_up: PipeFn, timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pipe_up():
            return True
        time.sleep(0.25)
    return False


def _b64(data: bytes) -> str:
    import base64

    return base64.b64encode(data).decode("ascii")
