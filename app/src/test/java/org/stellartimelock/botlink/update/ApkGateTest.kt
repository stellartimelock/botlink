package org.stellartimelock.botlink.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkGateTest {
    private val goodIncoming = ApkGate.ApkInfo(
        packageName = ApkGate.TARGET_PACKAGE,
        certSha256 = "7994727d" + "ab".repeat(28),
        versionCode = 42,
    )
    private val installed = ApkGate.ApkInfo(
        packageName = ApkGate.TARGET_PACKAGE,
        certSha256 = "7994727D" + "cc".repeat(28),
        versionCode = 41,
    )

    @Test
    fun acceptsNewerFlipSendOnHouseSigner() {
        val verdict = ApkGate.check(goodIncoming, installed)
        assertTrue(verdict.ok)
        assertEquals("ok", verdict.status)
    }

    @Test
    fun rejectsWrongPackage() {
        val other = goodIncoming.copy(packageName = "org.stellartimelock.botlink")
        val verdict = ApkGate.check(other, installed)
        assertFalse(verdict.ok)
        assertEquals("rejected_package", verdict.status)
    }

    @Test
    fun rejectsOtherSignerPrefix() {
        val other = goodIncoming.copy(certSha256 = "fac61745" + "ab".repeat(28))
        val verdict = ApkGate.check(other, installed)
        assertFalse(verdict.ok)
        assertEquals("rejected_signer", verdict.status)
    }

    @Test
    fun neverDowngradesOrReinstallsSameCode() {
        val same = goodIncoming.copy(versionCode = 41)
        assertEquals("rejected_version", ApkGate.check(same, installed).status)
        val older = goodIncoming.copy(versionCode = 10)
        assertEquals("rejected_version", ApkGate.check(older, installed).status)
        assertFalse(ApkGate.check(same, installed).ok)
    }

    @Test
    fun refusesWhenFlipSendIsMissing() {
        val verdict = ApkGate.check(goodIncoming, null)
        assertFalse(verdict.ok)
        assertEquals("rejected_missing", verdict.status)
    }

    @Test
    fun refusesIfInstalledFlipSendIsNotHouseSigner() {
        val alien = installed.copy(certSha256 = "deadbeef" + "00".repeat(28))
        val verdict = ApkGate.check(goodIncoming, alien)
        assertFalse(verdict.ok)
        assertEquals("rejected_signer", verdict.status)
    }

    @Test
    fun signerMatchIgnoresColonsAndCase() {
        assertTrue(ApkGate.signerMatches("79:94:72:7D:aa"))
        assertFalse(ApkGate.signerMatches("7994727cffff"))
        assertEquals(ApkGate.TARGET_PACKAGE, "org.stellartimelock.stellarsend")
        assertEquals(ApkGate.SIGNER_PREFIX, "7994727d")
    }
}
