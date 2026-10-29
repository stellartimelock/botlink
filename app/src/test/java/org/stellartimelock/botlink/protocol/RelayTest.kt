package org.stellartimelock.botlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    @Test
    fun joinMatchesFlipSendCredentialFields() {
        val json = Relay.joinMessage(
            room = "abc",
            role = Relay.ROLE_LISTEN,
            credential = Relay.Credential(
                purchaseToken = "tok",
                productId = Relay.PLAN_PRO,
                basePlanId = "monthly",
                packageName = Relay.PACKAGE,
                entitlementKey = "k",
                email = "StellarTimeLock@gmail.com",
                elite = true,
            ),
        )
        assertEquals(
            """{"v":1,"room":"abc","role":"listen","purchaseToken":"tok","productId":"relay_pro","basePlanId":"monthly","packageName":"org.stellartimelock.botlink","entitlementKey":"k","accountProof":{"v":1,"email":"stellartimelock@gmail.com","elite":true,"productId":"relay_pro"}}""",
            json,
        )
    }

    @Test
    fun usageOmitsEmptyProof() {
        assertEquals("""{"v":1,"op":"usage"}""", Relay.usageMessage())
    }

    @Test
    fun ssndFrameRoundTrip() {
        val frame = Relay.encodeFrame(Relay.SERVICE, "hi".toByteArray())
        assertEquals(12 + 2, frame.size)
        assertEquals("SSND", String(frame.copyOfRange(0, 4)))
        assertEquals(1, frame[4].toInt())
        assertEquals(10, frame[5].toInt())
        assertEquals(0, frame[6].toInt())
        assertEquals(0, frame[7].toInt())
        assertEquals(2, frame[11].toInt())
        val decoded = Relay.decodeFrame(frame)
        assertEquals(Relay.SERVICE, decoded?.type)
        assertEquals("hi", decoded?.payload?.decodeToString())
        assertNull(Relay.decodeFrame(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun meterParsesLiveRelayShape() {
        val meter = Relay.parseMeter(
            """{"v":1,"ok":true,"tier":"free","seen":"1.2.3.4:5","used":23,"limit":100000000,"enforced":true,"resets":"2026-10"}""",
        )
        assertEquals(true, meter?.ok)
        assertEquals("free", meter?.tier)
        assertEquals(23L, meter?.used)
        assertEquals(100_000_000L, meter?.limit)
        assertTrue(meter?.enforced == true)
        assertTrue(Relay.overCap(meter?.copy(used = 100_000_000L)))
    }

    @Test
    fun plansAndFounderEmails() {
        assertEquals("unlimited", Relay.allowance("stellartimelock@gmail.com", true, Relay.PLAN_MAX).tier)
        assertFalse(Relay.allowance("stellartimelock@gmail.com", false, null).enforced)
        assertEquals(Relay.ELITE_BYTES, Relay.allowance(null, true, null).limit)
        assertEquals(Relay.PRO_BYTES, Relay.allowance(null, false, Relay.PLAN_PRO).limit)
        assertEquals(Relay.PLUS_BYTES, Relay.allowance(null, false, Relay.PLAN_PLUS).limit)
        assertEquals(Relay.MAX_BYTES, Relay.allowance(null, false, Relay.PLAN_MAX).limit)
        assertEquals(Relay.FREE_BYTES, Relay.allowance(null, false, null).limit)
        assertEquals("0.1 GB", Relay.formatGb(100_000_000L))
        assertEquals("1 GB", Relay.formatGb(Relay.ELITE_BYTES))
        assertEquals("5 GB", Relay.formatGb(Relay.PRO_BYTES))
    }

    @Test
    fun spendCapsStayFixedAndX402AsksTheWallet() {
        assertTrue(Relay.X402_ENABLED)
        val over = Relay.spendDecision("grok-bot", 101, 0)
        assertEquals("cap", over.code)
        val month = Relay.spendDecision("grok-bot", 100, 401)
        assertEquals("cap", month.code)
        val ok = Relay.spendDecision("grok-bot", 40, 0, X402.EXAMPLE_NOTE_ID, X402.EXAMPLE_PAY_TO)
        assertTrue(ok.ok)
        assertEquals("x402_sign", ok.code)
        assertEquals(40, ok.allowedCents)
        assertEquals(X402.example().sep7, ok.request?.sep7)
    }
}
