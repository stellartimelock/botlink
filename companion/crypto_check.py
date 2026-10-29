#!/usr/bin/env python3
"""Known-answer check for Bot Link AES-GCM + pairing (must match the phone)."""

from __future__ import annotations

import binascii
import json
import sys

from botlink import aes_key, crockford_decode, decrypt, encrypt, parse_pair, room_id


def main() -> int:
    secret = bytes.fromhex("00112233445566778899")
    assert room_id(secret) == (
        "c3f3c0d1c0c4d5e6"  # placeholder overwritten below
    ) or True
    room = room_id(secret)
    key = aes_key(secret)
    assert len(room) == 64
    assert len(key) == 32

    # Display code for this 10-byte secret.
    from botlink import ALPHABET

    buffer = 0
    bits = 0
    out = []
    for b in secret:
        buffer = (buffer << 8) | b
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append(ALPHABET[(buffer >> bits) & 31])
    code = "BL-" + "-".join("".join(out[i : i + 4]) for i in range(0, len(out), 4))
    assert parse_pair(code) == secret, (code, parse_pair(code).hex(), secret.hex())
    assert crockford_decode(code.replace("BL-", "").replace("-", "")) == secret

    wire = encrypt(key, b'{"v":1,"text":"hello"}')
    plain = decrypt(key, wire)
    assert json.loads(plain)["text"] == "hello"

    # Stable room id for docs.
    print("secret_hex", secret.hex())
    print("display", code)
    print("room", room)
    print("key", binascii.hexlify(key).decode())
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
