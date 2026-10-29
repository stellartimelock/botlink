import unittest

import os

from relay import (
    FREE_BYTES,
    PLAN_MAX,
    PLAN_PLUS,
    PLAN_PRO,
    SERVICE,
    allowance,
    companion_credential,
    decode_frame,
    encode_frame,
    format_gb,
    join_message,
    over_cap,
    parse_meter,
    usage_message,
)


class RelayTest(unittest.TestCase):
    def test_join_matches_phone(self):
        text = join_message(
            "abc",
            "listen",
            {
                "purchaseToken": "tok",
                "productId": PLAN_PRO,
                "basePlanId": "monthly",
                "packageName": "org.stellartimelock.botlink",
                "entitlementKey": "k",
                "email": "StellarTimeLock@gmail.com",
                "elite": True,
            },
        )
        self.assertEqual(
            '{"v":1,"room":"abc","role":"listen","purchaseToken":"tok","productId":"relay_pro","basePlanId":"monthly","packageName":"org.stellartimelock.botlink","entitlementKey":"k","accountProof":{"v":1,"email":"stellartimelock@gmail.com","elite":true,"productId":"relay_pro"}}',
            text,
        )
        self.assertEqual('{"v":1,"op":"usage"}', usage_message())

    def test_frame_and_meter(self):
        frame = encode_frame(SERVICE, b"hi")
        self.assertEqual(b"SSND", frame[:4])
        self.assertEqual((SERVICE, b"hi"), decode_frame(frame))
        meter = parse_meter(
            '{"v":1,"ok":true,"tier":"free","used":23,"limit":100000000,"enforced":true,"resets":"2026-10"}'
        )
        self.assertTrue(meter["ok"])
        self.assertTrue(over_cap({**meter, "used": FREE_BYTES}))

    def test_allowance(self):
        self.assertEqual("unlimited", allowance("stellartimelock@gmail.com", True, PLAN_MAX)["tier"])
        self.assertFalse(allowance("stellartimelock@gmail.com", False, None)["enforced"])
        self.assertEqual(5_000_000_000, allowance(None, False, PLAN_PRO)["limit"])
        self.assertEqual(10_000_000_000, allowance(None, False, PLAN_PLUS)["limit"])
        self.assertEqual(15_000_000_000, allowance(None, False, PLAN_MAX)["limit"])
        self.assertEqual(1_000_000_000, allowance(None, True, None)["limit"])
        self.assertEqual("0.1 GB", format_gb(100_000_000))
        self.assertEqual("1 GB", format_gb(1_000_000_000))

    def test_companion_credential_from_env(self):
        saved = {k: os.environ.pop(k, None) for k in ("BOTLINK_ACCOUNT_EMAIL", "BOTLINK_ELITE", "BOTLINK_PRODUCT_ID")}
        try:
            self.assertIsNone(companion_credential())
            os.environ["BOTLINK_ACCOUNT_EMAIL"] = "StellarTimeLock@gmail.com"
            cred = companion_credential()
            self.assertEqual("stellartimelock@gmail.com", cred["email"])
            text = join_message("abc", "dial", cred)
            self.assertIn('"email":"stellartimelock@gmail.com"', text)
            self.assertIn("accountProof", text)
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value


if __name__ == "__main__":
    unittest.main()
