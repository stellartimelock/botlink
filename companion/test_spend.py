import unittest

from spend import decide
from x402 import EXAMPLE_PAY_TO


class SpendTest(unittest.TestCase):
    def test_caps(self):
        self.assertEqual("cap", decide("grok-bot", 101, 0)["code"])
        self.assertEqual("cap", decide("grok-bot", 100, 401)["code"])
        ok = decide("grok-bot", 40, 0, "pending", EXAMPLE_PAY_TO)
        self.assertTrue(ok["ok"])
        self.assertEqual("x402_sign", ok["code"])
        self.assertIn("sep7", ok["request"])
        self.assertEqual("cap", decide("", 10, 0)["code"])
        self.assertEqual("pay_to", decide("grok-bot", 40, 0)["code"])


if __name__ == "__main__":
    unittest.main()
