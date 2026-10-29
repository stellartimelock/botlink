import os
import tempfile
import unittest
from pathlib import Path

from apk_xfer import (
    CHUNK_SIZE,
    HOUSE_SIGNER,
    KIND_ACK,
    KIND_OFFER,
    KIND_REPLACE,
    KIND_RESULT,
    MODE_REPLACE,
    MODE_UPGRADE,
    SIGNER_PREFIX,
    STELLAR_PREFIXES,
    TARGET_PACKAGE,
    Assembler,
    ack_message,
    allowed_package,
    allowed_signer,
    chunk_count,
    chunk_message,
    chunk_slice,
    decide_mode,
    encoded_chunk_fits,
    is_terminal_status,
    next_index,
    offer_message,
    read_chunk,
    replace_check,
    result_message,
    run_send,
    sha256_file,
)


class ApkXferTest(unittest.TestCase):
    def test_chunk_math_and_resume(self):
        self.assertEqual(0, chunk_count(0))
        self.assertEqual(1, chunk_count(1))
        self.assertEqual(3, chunk_count(250_000, 100_000))
        self.assertEqual((200_000, 50_000), chunk_slice(250_000, 2, 100_000))
        self.assertEqual(2, next_index({0, 1}, 5))
        self.assertEqual(5, next_index({0, 1, 2, 3, 4}, 5))
        self.assertEqual(0, next_index(set(), 4))

    def test_assembler_round_trip_and_hash(self):
        payload = bytes(range(256)) * 400  # 102400 bytes
        digest = __import__("hashlib").sha256(payload).hexdigest()
        size = len(payload)
        chunk = 30_000
        asm = Assembler(size, digest, chunk)
        # Deliver out of order-ish by skipping 1, then filling it (resume).
        for idx in (0, 2, 1, 3):
            offset, length = chunk_slice(size, idx, chunk)
            nxt = asm.accept(idx, payload[offset : offset + length])
        self.assertTrue(asm.complete())
        self.assertEqual(asm.chunks, nxt)
        self.assertTrue(asm.digest_ok())

    def test_chunk_json_fits_ssnd_budget(self):
        data = b"x" * CHUNK_SIZE
        self.assertTrue(encoded_chunk_fits(data))

    def test_offer_stays_on_botlink_sidecar(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "FlipSend-9.9.9-main.apk"
            path.write_bytes(os.urandom(1500))
            offer = offer_message(path, transfer_id="t1", chunk_size=1024)
            self.assertEqual(KIND_OFFER, offer["kind"])
            self.assertEqual("t1", offer["id"])
            self.assertEqual(TARGET_PACKAGE, offer["package"])
            self.assertEqual(sha256_file(path), offer["sha256"])
            self.assertNotIn("file.room", str(offer).lower())
            self.assertNotIn("stellarsend.room", str(offer).lower())
            packed = chunk_message("t1", 0, read_chunk(path, 0, 1024))
            self.assertEqual("t1", packed["id"])
            self.assertEqual(0, packed["index"])

    def test_run_send_resumes_after_drop(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "FlipSend-1.2.3-main.apk"
            payload = os.urandom(8_000)
            path.write_bytes(payload)
            inbox = []
            sent = []
            drops_left = {"n": 1}

            def send(msg):
                sent.append(msg)
                if msg["kind"] == "apk-chunk" and msg["index"] == 1 and drops_left["n"]:
                    drops_left["n"] -= 1
                    return False
                if msg["kind"] == "apk-offer":
                    have = {m["index"] for m in sent if m["kind"] == "apk-chunk" and m is not msg}
                    # Reconstruct from successful chunks already accepted on "phone"
                    nxt = next_index(phone.have, phone.chunks)
                    inbox.append(ack_message(msg["id"], nxt))
                    return True
                if msg["kind"] == "apk-chunk":
                    raw = __import__("base64").b64decode(msg["data"])
                    nxt = phone.accept(msg["index"], raw)
                    inbox.append(ack_message(msg["id"], nxt, msg["index"]))
                    return True
                if msg["kind"] == "apk-done":
                    inbox.append(
                        result_message(msg["id"], True, "installed", "FlipSend updated."),
                    )
                    return True
                return True

            phone = Assembler(len(payload), sha256_file(path), 3000)
            pipe = {"up": True}

            def wait(pred, timeout):
                for _ in range(20):
                    if not inbox:
                        return None
                    item = inbox.pop(0)
                    if pred(item):
                        return item
                    inbox.append(item)
                return None

            result = run_send(
                path,
                send=send,
                wait=wait,
                pipe_up=lambda: pipe["up"],
                chunk_size=3000,
                ack_timeout=1.0,
                result_timeout=2.0,
                connect_timeout=1.0,
            )
            self.assertTrue(result["ok"])
            self.assertEqual("installed", result["status"])
            self.assertTrue(phone.complete())
            self.assertTrue(phone.digest_ok())
            self.assertEqual(KIND_RESULT, result["kind"])

    def test_gate_constants_match_phone(self):
        self.assertEqual("org.stellartimelock.stellarsend", TARGET_PACKAGE)
        self.assertEqual("7994727d", SIGNER_PREFIX)
        self.assertEqual("fac61745", HOUSE_SIGNER)
        self.assertEqual(
            ("org.stellartimelock.", "com.stellartimelock.", "app.stellar."),
            STELLAR_PREFIXES,
        )

    def test_replace_offer_uses_kind_replace(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Market-1.apk"
            path.write_bytes(os.urandom(800))
            offer = offer_message(
                path,
                transfer_id="r1",
                chunk_size=1024,
                mode=MODE_REPLACE,
                package="org.stellartimelock.market",
            )
            self.assertEqual(KIND_REPLACE, offer["kind"])
            self.assertEqual(MODE_REPLACE, offer["mode"])
            self.assertEqual("org.stellartimelock.market", offer["package"])
            upgrade = offer_message(path, transfer_id="u1", mode=MODE_UPGRADE)
            self.assertEqual(KIND_OFFER, upgrade["kind"])
            self.assertEqual(MODE_UPGRADE, upgrade["mode"])

    def test_allowlist_and_signer(self):
        self.assertTrue(allowed_package("org.stellartimelock.market"))
        self.assertTrue(allowed_package("com.stellartimelock"))
        self.assertTrue(allowed_package("app.stellar.one"))
        self.assertFalse(allowed_package("org.stellartimelock.botlink"))
        self.assertFalse(allowed_package("com.android.vending"))
        self.assertTrue(allowed_signer("FAC61745aabb"))
        self.assertTrue(allowed_signer("7994727dffff"))
        self.assertFalse(allowed_signer("d0c40cc1aaaa"))

    def test_decide_mode_send_vs_replace(self):
        flipsend = {
            "package": TARGET_PACKAGE,
            "certSha256": SIGNER_PREFIX + "ab" * 28,
            "versionCode": 42,
        }
        installed_same = {
            "package": TARGET_PACKAGE,
            "certSha256": SIGNER_PREFIX + "ab" * 28,
            "versionCode": 41,
        }
        mode, _reason = decide_mode(flipsend, installed_same)
        self.assertEqual(MODE_UPGRADE, mode)

        installed_wrong = {
            "package": TARGET_PACKAGE,
            "certSha256": "deadbeef" + "00" * 28,
            "versionCode": 41,
        }
        mode, _reason = decide_mode(flipsend, installed_wrong)
        self.assertEqual(MODE_REPLACE, mode)

        market = {
            "package": "org.stellartimelock.market",
            "certSha256": HOUSE_SIGNER + "cc" * 28,
            "versionCode": 3,
        }
        phone_wrong = {
            "package": "org.stellartimelock.market",
            "certSha256": "cafebabe" + "11" * 28,
            "versionCode": 9,
        }
        mode, _reason = decide_mode(market, phone_wrong)
        self.assertEqual(MODE_REPLACE, mode)
        self.assertEqual("reinstall", replace_check(market, phone_wrong)["action"])

        downgrade = dict(flipsend)
        downgrade["versionCode"] = 10
        mode, status = decide_mode(downgrade, installed_same)
        self.assertEqual("refuse", mode)
        self.assertTrue(status.startswith("failed:"))

        alien = {"package": "com.example.app", "certSha256": HOUSE_SIGNER + "00" * 28, "versionCode": 2}
        mode, status = decide_mode(alien, None)
        self.assertEqual("refuse", mode)

        mode, status = decide_mode(flipsend, None)
        self.assertEqual("refuse", mode)

        mode, _reason = decide_mode(market, None)
        self.assertEqual(MODE_REPLACE, mode)

        self.assertFalse(is_terminal_status("replace_pending"))
        self.assertFalse(is_terminal_status("uninstalled"))
        self.assertFalse(is_terminal_status("install_pending"))
        self.assertTrue(is_terminal_status("installed", True))
        self.assertTrue(is_terminal_status("failed:rejected_signer"))

    def test_run_send_waits_past_replace_pending(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Market-2.apk"
            payload = os.urandom(4_000)
            path.write_bytes(payload)
            inbox = []
            sent = []

            def send(msg):
                sent.append(msg)
                if msg["kind"] in {KIND_OFFER, KIND_REPLACE}:
                    inbox.append(ack_message(msg["id"], 0))
                    return True
                if msg["kind"] == "apk-chunk":
                    raw = __import__("base64").b64decode(msg["data"])
                    nxt = phone.accept(msg["index"], raw)
                    inbox.append(ack_message(msg["id"], nxt, msg["index"]))
                    return True
                if msg["kind"] == "apk-done":
                    inbox.append(result_message(msg["id"], False, "replace_pending", "Confirm Uninstall."))
                    inbox.append(result_message(msg["id"], False, "uninstalled", "Market is off."))
                    inbox.append(result_message(msg["id"], True, "installed", "Market updated."))
                    return True
                return True

            phone = Assembler(len(payload), sha256_file(path), 2000)

            def wait(pred, timeout):
                for _ in range(20):
                    if not inbox:
                        return None
                    item = inbox.pop(0)
                    if pred(item):
                        return item
                    inbox.append(item)
                return None

            result = run_send(
                path,
                send=send,
                wait=wait,
                pipe_up=lambda: True,
                chunk_size=2000,
                ack_timeout=1.0,
                result_timeout=2.0,
                connect_timeout=1.0,
                mode=MODE_REPLACE,
                package="org.stellartimelock.market",
            )
            self.assertTrue(result["ok"])
            self.assertEqual("installed", result["status"])
            self.assertEqual(KIND_REPLACE, sent[0]["kind"])


if __name__ == "__main__":
    unittest.main()
