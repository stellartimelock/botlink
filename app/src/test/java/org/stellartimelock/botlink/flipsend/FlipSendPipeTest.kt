package org.stellartimelock.botlink.flipsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlipSendPipeTest {
    @Test
    fun initialStatusFromPackageAndExport() {
        assertEquals(FlipSendPipe.Status.Missing, FlipSendPipe.initial(installed = false, advertised = false))
        assertEquals(FlipSendPipe.Status.NotExported, FlipSendPipe.initial(installed = true, advertised = false))
        assertEquals(FlipSendPipe.Status.Advertised, FlipSendPipe.initial(installed = true, advertised = true))
    }

    @Test
    fun parsePairedExtra() {
        assertEquals(FlipSendPipe.Status.Paired, FlipSendPipe.parsePairedExtra(true, true))
        assertEquals(FlipSendPipe.Status.Unpaired, FlipSendPipe.parsePairedExtra(true, false))
        assertEquals(FlipSendPipe.Status.Unknown, FlipSendPipe.parsePairedExtra(false, true))
    }

    @Test
    fun parseActivityResultReadsOnlyPairedFlag() {
        assertEquals(FlipSendPipe.Status.Unknown, FlipSendPipe.parseActivityResult(0, null))
        assertEquals(FlipSendPipe.Status.Unknown, FlipSendPipe.parseActivityResult(-1, emptyMap()))
        assertEquals(
            FlipSendPipe.Status.Paired,
            FlipSendPipe.parseActivityResult(-1, mapOf(FlipSendPipe.EXTRA_PAIRED to true)),
        )
        assertEquals(
            FlipSendPipe.Status.Unpaired,
            FlipSendPipe.parseActivityResult(-1, mapOf(FlipSendPipe.EXTRA_PAIRED to "false")),
        )
        val extras = mapOf(
            FlipSendPipe.EXTRA_PAIRED to true,
            "room" to "should-be-ignored",
            "key" to "should-be-ignored",
        )
        assertEquals(FlipSendPipe.Status.Paired, FlipSendPipe.parseActivityResult(-1, extras))
        assertFalse(FlipSendPipe.Status.Paired.settingsLine().contains("file room"))
    }

    @Test
    fun queryActionStaysOnFlipSendPackage() {
        assertEquals(
            "org.stellartimelock.stellarsend.action.QUERY_PIPE",
            FlipSendDetector.ACTION_QUERY_PIPE,
        )
        assertEquals("org.stellartimelock.stellarsend", FlipSendDetector.FLIPSEND_PACKAGE)
        assertEquals("paired", FlipSendPipe.EXTRA_PAIRED)
    }
}
