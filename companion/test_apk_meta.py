import unittest
from pathlib import Path

from apk_meta import normalize_cert, read_apk_meta

_RELEASE = Path(__file__).resolve().parents[1] / "app/build/outputs/apk/release/app-release.apk"


class ApkMetaTest(unittest.TestCase):
    def test_normalize_cert(self):
        self.assertEqual("fac61745ab", normalize_cert("FAC6:1745 AB"))
        self.assertEqual("7994727d", normalize_cert("7994727d"))

    @unittest.skipUnless(_RELEASE.is_file(), "release APK not built")
    def test_reads_bot_link_release_apk(self):
        meta = read_apk_meta(_RELEASE)
        self.assertEqual("org.stellartimelock.botlink", meta["package"])
        self.assertEqual(7, meta["versionCode"])
        self.assertTrue(meta["certSha256"].startswith("d0c40cc1"))


if __name__ == "__main__":
    unittest.main()
