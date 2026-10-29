import json
import unittest

from horizon import HORIZON_PUBNET, HORIZON_TESTNET, horizon_base, submit_transaction
from x402 import NETWORK_PUBNET, NETWORK_TESTNET


class HorizonTest(unittest.TestCase):
    def test_base_urls(self):
        self.assertEqual(HORIZON_PUBNET, horizon_base(NETWORK_PUBNET))
        self.assertEqual(HORIZON_TESTNET, horizon_base(NETWORK_TESTNET))

    def test_empty_xdr_refused(self):
        self.assertEqual("xdr", submit_transaction("")["code"])

    def test_submit_success_and_failure(self):
        def ok(url, body, headers):
            self.assertTrue(url.endswith("/transactions"))
            self.assertIn(b"tx=", body)
            self.assertEqual("application/x-www-form-urlencoded", headers["Content-Type"])
            return 200, json.dumps({"hash": "abc123", "successful": True}).encode()

        submitted = submit_transaction("AAAA", NETWORK_PUBNET, post=ok)
        self.assertTrue(submitted["ok"])
        self.assertEqual("abc123", submitted["hash"])

        def bad(url, body, headers):
            return 400, json.dumps({"title": "Transaction Failed", "extras": {"result_codes": {"transaction": "tx_bad_auth"}}}).encode()

        refused = submit_transaction("AAAA", NETWORK_PUBNET, post=bad)
        self.assertFalse(refused["ok"])
        self.assertIn("tx_bad_auth", refused["message"])


if __name__ == "__main__":
    unittest.main()
