package org.stellartimelock.botlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X402Test {
    @Test
    fun amountsMatchUsdcAtomicUnits() {
        assertEquals("4000000", X402.atomicAmount(40))
        assertEquals("10000000", X402.atomicAmount(100))
        assertEquals("0.4000000", X402.displayAmount(40))
    }

    @Test
    fun exampleSigningRequestMatchesCompanion() {
        val req = X402.example()
        assertEquals(1, req.v)
        assertEquals("botlink.x402.sep7", req.protocol)
        assertEquals(2, req.x402Version)
        val offer = (req.paymentRequired["accepts"] as List<*>).first() as Map<*, *>
        assertEquals("exact", offer["scheme"])
        assertEquals("stellar:pubnet", offer["network"])
        assertEquals(X402.USDC_SAC_PUBNET, offer["asset"])
        assertEquals("4000000", offer["amount"])
        assertEquals(X402.EXAMPLE_PAY_TO, offer["payTo"])
        assertEquals(
            "web+stellar:pay?destination=GBHEGW3KWOY2OFH767EDALFGCUTBOEVBDQMCKU4APMDLQNBW5QV3W3KO" +
                "&amount=0.4000000&asset_code=USDC" +
                "&asset_issuer=GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN" +
                "&memo=bl%3A11111111&msg=Bot%20Link%20spend%20%240.40" +
                "&origin_domain=stellartimelock.com" +
                "&callback=url%3Abotlink%3A%2F%2Fx402%2Fsigned",
            req.sep7,
        )
        val json = req.encode()
        assertFalse(json.contains("secret", ignoreCase = true))
        assertTrue(json.contains("\"x402Version\":2"))
        assertTrue(json.contains("\"protocol\":\"botlink.x402.sep7\""))
    }

    @Test
    fun spendDecisionBuildsSignRequestInsideTheCap() {
        assertTrue(Relay.X402_ENABLED)
        val over = Relay.spendDecision("grok-bot", 101, 0)
        assertEquals("cap", over.code)
        val month = Relay.spendDecision("grok-bot", 100, 401)
        assertEquals("cap", month.code)
        val ok = Relay.spendDecision(
            agent = "grok-bot",
            cents = 40,
            spentThisMonth = 0,
            noteId = X402.EXAMPLE_NOTE_ID,
            payTo = X402.EXAMPLE_PAY_TO,
        )
        assertTrue(ok.ok)
        assertEquals("x402_sign", ok.code)
        assertEquals(X402.example().encode(), ok.request?.encode())
        val blank = Relay.spendDecision("grok-bot", 40, 0)
        assertEquals("pay_to", blank.code)
        assertTrue(X402.isGAddress(X402.EXAMPLE_PAY_TO))
        assertFalse(X402.isGAddress(""))
        assertFalse(X402.isGAddress("GSHORT"))
    }
}
