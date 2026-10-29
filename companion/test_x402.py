import json
import unittest

from spend import decide
from x402 import (
    CALLBACK,
    EXAMPLE_NOTE_ID,
    EXAMPLE_PAY_TO,
    NETWORK_PUBNET,
    PROTOCOL,
    USDC_SAC_PUBNET,
    X402_VERSION,
    atomic_amount,
    display_amount,
    dumps,
    example,
    is_g_address,
    sep7_uri,
)


class X402Test(unittest.TestCase):
    def test_amounts(self):
        self.assertEqual("4000000", atomic_amount(40))
        self.assertEqual("10000000", atomic_amount(100))
        self.assertEqual("0.4000000", display_amount(40))

    def test_example_payload_is_x402_v2_exact(self):
        req = example()
        self.assertEqual(1, req["v"])
        self.assertEqual(PROTOCOL, req["protocol"])
        self.assertEqual(X402_VERSION, req["x402Version"])
        challenge = req["paymentRequired"]
        self.assertEqual(2, challenge["x402Version"])
        self.assertEqual("Bot Link", challenge["resource"]["serviceName"])
        offer = challenge["accepts"][0]
        self.assertEqual("exact", offer["scheme"])
        self.assertEqual(NETWORK_PUBNET, offer["network"])
        self.assertEqual(USDC_SAC_PUBNET, offer["asset"])
        self.assertEqual("4000000", offer["amount"])
        self.assertEqual(EXAMPLE_PAY_TO, offer["payTo"])
        self.assertTrue(offer["extra"]["areFeesSponsored"])
        self.assertTrue(req["sep7"].startswith("web+stellar:pay?"))
        self.assertIn(EXAMPLE_PAY_TO, req["sep7"])
        self.assertIn("callback=url%3Abotlink%3A%2F%2Fx402%2Fsigned", req["sep7"])
        self.assertEqual(CALLBACK, req["callback"])
        self.assertNotIn("secret", dumps(req).lower())
        self.assertNotIn("seed", dumps(req).lower())

    def test_sep7_is_deterministic(self):
        uri = sep7_uri(40, EXAMPLE_PAY_TO, EXAMPLE_NOTE_ID, NETWORK_PUBNET)
        self.assertEqual(
            "web+stellar:pay?destination=GBHEGW3KWOY2OFH767EDALFGCUTBOEVBDQMCKU4APMDLQNBW5QV3W3KO"
            "&amount=0.4000000&asset_code=USDC"
            "&asset_issuer=GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
            "&memo=bl%3A11111111&msg=Bot%20Link%20spend%20%240.40"
            "&origin_domain=stellartimelock.com"
            "&callback=url%3Abotlink%3A%2F%2Fx402%2Fsigned",
            uri,
        )

    def test_spend_returns_sign_request(self):
        over = decide("grok-bot", 101, 0)
        self.assertEqual("cap", over["code"])
        month = decide("grok-bot", 100, 401)
        self.assertEqual("cap", month["code"])
        ok = decide("grok-bot", 40, 0, EXAMPLE_NOTE_ID, EXAMPLE_PAY_TO)
        self.assertTrue(ok["ok"])
        self.assertEqual("x402_sign", ok["code"])
        self.assertEqual(example(), ok["request"])

    def test_example_json_is_stable(self):
        encoded = dumps(example())
        parsed = json.loads(encoded)
        self.assertEqual(example(), parsed)

    def test_pay_to_must_be_a_g_address(self):
        self.assertTrue(is_g_address(EXAMPLE_PAY_TO))
        self.assertFalse(is_g_address(""))
        self.assertFalse(is_g_address("GSHORT"))
        self.assertEqual("pay_to", decide("grok-bot", 40, 0)["code"])


if __name__ == "__main__":
    unittest.main()
