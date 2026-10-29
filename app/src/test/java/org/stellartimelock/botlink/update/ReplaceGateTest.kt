package org.stellartimelock.botlink.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceGateTest {
    private val market = ApkGate.ApkInfo(
        packageName = "org.stellartimelock.market",
        certSha256 = "fac61745" + "ab".repeat(28),
        versionCode = 12,
    )
    private val wrongKey = ApkGate.ApkInfo(
        packageName = "org.stellartimelock.market",
        certSha256 = "deadbeef" + "00".repeat(28),
        versionCode = 9,
    )

    @Test
    fun allowlistIsDocumentedPrefixes() {
        assertEquals(
            listOf("org.stellartimelock.", "com.stellartimelock.", "app.stellar."),
            ReplaceGate.STELLAR_PREFIXES,
        )
        assertTrue(ReplaceGate.allowedPackage("org.stellartimelock.market"))
        assertTrue(ReplaceGate.allowedPackage("org.stellartimelock.stellarsend"))
        assertTrue(ReplaceGate.allowedPackage("com.stellartimelock.wallet"))
        assertTrue(ReplaceGate.allowedPackage("com.stellartimelock"))
        assertTrue(ReplaceGate.allowedPackage("app.stellar.one"))
        assertFalse(ReplaceGate.allowedPackage("com.android.vending"))
        assertFalse(ReplaceGate.allowedPackage("org.mozilla.firefox"))
        assertFalse(ReplaceGate.allowedPackage(ReplaceGate.SELF_PACKAGE))
        assertFalse(ReplaceGate.allowedPackage(""))
    }

    @Test
    fun incomingCertMustBeHouseOrFlipSend() {
        assertTrue(ReplaceGate.allowedSigner("FAC61745" + "ff".repeat(28)))
        assertTrue(ReplaceGate.allowedSigner("79:94:72:7D:aa"))
        assertFalse(ReplaceGate.allowedSigner("deadbeef" + "00".repeat(28)))
        assertFalse(ReplaceGate.allowedSigner("d0c40cc1" + "11".repeat(28)))
        assertEquals(listOf("fac61745", "7994727d"), ReplaceGate.ALLOWED_SIGNERS)
    }

    @Test
    fun differentCertIsReinstall() {
        val verdict = ReplaceGate.check(market, wrongKey)
        assertTrue(verdict.ok)
        assertEquals(ReplaceGate.Action.REINSTALL, verdict.action)
        assertEquals("failed:rejected_package", ReplaceGate.Verdict(false, "rejected_package", "x").wireStatus())
        assertEquals("ok", verdict.wireStatus())
    }

    @Test
    fun sameCertUpgradeRequiresHigherVersionCode() {
        val installed = market.copy(versionCode = 10)
        val ok = ReplaceGate.check(market, installed)
        assertTrue(ok.ok)
        assertEquals(ReplaceGate.Action.UPGRADE, ok.action)

        val same = ReplaceGate.check(market, market.copy(certSha256 = "FAC61745" + "ab".repeat(28)))
        assertFalse(same.ok)
        assertEquals("rejected_version", same.status)
        assertEquals("failed:rejected_version", same.wireStatus())

        val older = ReplaceGate.check(market.copy(versionCode = 3), installed)
        assertFalse(older.ok)
        assertEquals("rejected_version", older.status)
    }

    @Test
    fun missingAppIsFreshInstallExceptFlipSend() {
        val verdict = ReplaceGate.check(market, null)
        assertTrue(verdict.ok)
        assertEquals(ReplaceGate.Action.INSTALL, verdict.action)
        val flip = market.copy(packageName = ApkGate.TARGET_PACKAGE, certSha256 = "7994727d" + "aa".repeat(28))
        val missing = ReplaceGate.check(flip, null)
        assertFalse(missing.ok)
        assertEquals("rejected_missing", missing.status)
    }

    @Test
    fun refusesWrongPackageAndWrongIncomingKey() {
        val alien = market.copy(packageName = "com.example.store")
        val pkg = ReplaceGate.check(alien, null)
        assertFalse(pkg.ok)
        assertEquals("rejected_package", pkg.status)
        assertEquals("failed:rejected_package", pkg.wireStatus())

        val self = market.copy(packageName = ReplaceGate.SELF_PACKAGE)
        val selfV = ReplaceGate.check(self, null)
        assertFalse(selfV.ok)
        assertEquals("rejected_self", selfV.status)

        val badKey = ReplaceGate.check(wrongKey, wrongKey.copy(versionCode = 1))
        assertFalse(badKey.ok)
        assertEquals("rejected_signer", badKey.status)
        assertEquals("failed:rejected_signer", badKey.wireStatus())
        assertNull(badKey.action)
    }

    @Test
    fun keySwitchIgnoresVersionCode() {
        val incomingOlder = market.copy(versionCode = 1)
        val verdict = ReplaceGate.check(incomingOlder, wrongKey.copy(versionCode = 99))
        assertTrue(verdict.ok)
        assertEquals(ReplaceGate.Action.REINSTALL, verdict.action)
    }

    @Test
    fun flipSendOnHouseKeyIsReplaceNotFlipSendGate() {
        val incoming = ApkGate.ApkInfo(
            packageName = ApkGate.TARGET_PACKAGE,
            certSha256 = "7994727d" + "aa".repeat(28),
            versionCode = 50,
        )
        val installedWrong = incoming.copy(certSha256 = "cafebabe" + "00".repeat(28), versionCode = 40)
        val verdict = ReplaceGate.check(incoming, installedWrong)
        assertTrue(verdict.ok)
        assertEquals(ReplaceGate.Action.REINSTALL, verdict.action)
    }
}
