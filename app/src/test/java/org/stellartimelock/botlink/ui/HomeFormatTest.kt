package org.stellartimelock.botlink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellartimelock.botlink.net.LinkStatus
import org.stellartimelock.botlink.protocol.BotNote
import org.stellartimelock.botlink.protocol.NoteKind
import org.stellartimelock.botlink.protocol.NoteStatus

class HomeFormatTest {
    @Test
    fun pendingCardUsesWaitingInboxNotesOnly() {
        val notes = listOf(
            note("new", NoteStatus.PENDING, wantsAction = true),
            note("held", NoteStatus.HELD, wantsAction = true),
            note("done", NoteStatus.APPROVED, wantsAction = true),
            note("out", NoteStatus.PENDING, wantsAction = true, direction = BotNote.Direction.OUT),
            note("seed", NoteStatus.REJECTED_SEED, wantsAction = false),
        )
        val shown = pendingNotes(notes).map { it.id }
        assertEquals(listOf("new", "held"), shown)
    }

    @Test
    fun pendingCopyUsesNoteTextAndApproveState() {
        val act = note("a", NoteStatus.PENDING, wantsAction = true, text = "Draft reply to Accounts")
        assertEquals("Draft reply to Accounts", pendingTitle(act))
        assertEquals("Needs your approve", pendingDetail(act))

        val held = note("h", NoteStatus.HELD, wantsAction = true, text = "Post status update")
        assertEquals("Held · Allow is off", pendingDetail(held))

        val blank = note("b", NoteStatus.PENDING, wantsAction = false, text = "  ")
        assertEquals("grok-bot", pendingTitle(blank))
        assertEquals("grok-bot", pendingDetail(blank))
    }

    @Test
    fun agoMatchesMockupShape() {
        val now = 1_700_000_000_000L
        assertEquals("now", agoLabel(now - 20_000L, now))
        assertEquals("2m", agoLabel(now - 2 * 60_000L, now))
        assertEquals("14m", agoLabel(now - 14 * 60_000L, now))
        assertEquals("1h", agoLabel(now - 60 * 60_000L, now))
        assertEquals("2d", agoLabel(now - 2 * 24 * 60 * 60_000L, now))
    }

    @Test
    fun pipeLineFollowsRealLinkStatus() {
        assertEquals("Pipe idle", pipeLine(LinkStatus()))
        assertEquals(
            "Pipe ready",
            pipeLine(LinkStatus(lanDetail = "Listening on 10.0.0.8:18766")),
        )
        assertEquals("Pipe ready", pipeLine(LinkStatus(lanPeer = true, lanDetail = "Peer up")))
        assertEquals("Pipe ready", pipeLine(LinkStatus(relayUp = true)))
        assertEquals("Pipe idle", pipeLine(LinkStatus(lanDetail = "LAN error: refused")))
    }

    @Test
    fun approvalsHideWhenEmptyAndStayInTheWaitingSet() {
        assertEquals(false, showApprovals(emptyList()))
        assertEquals(false, showApprovals(listOf(note("denied", NoteStatus.DENIED, wantsAction = true))))
        val approved = note("ok", NoteStatus.APPROVED, wantsAction = true)
        assertEquals(listOf("ok"), approvalEntries(listOf(approved)).map { it.id })
        val summary = note("sum", NoteStatus.SENT, wantsAction = false).copy(
            kind = NoteKind.SUMMARY,
            html = "<p>full</p>",
        )
        assertEquals(false, showApprovals(listOf(summary)))
        assertEquals(listOf("sum"), roomBlurbs(listOf(summary)).map { it.id })
    }

    @Test
    fun settingsLabelAndTranscriptPage() {
        assertEquals("Settings", settingsLabel(0))
        assertEquals("Settings · 2 waiting", settingsLabel(2))
        val page = transcriptHtml("Draft <to> Alice", "<p>Hello</p>")
        assertTrue(page.contains("<h1>Draft &lt;to&gt; Alice</h1>"))
        assertTrue(page.contains("<p>Hello</p>"))
        assertEquals("<html><body>keep</body></html>", transcriptHtml("x", "<html><body>keep</body></html>"))
        assertEquals("\$1 a call · \$5 an agent each month · x402 on · wallet signs · Horizon submits", spendCaption())
    }

    private fun note(
        id: String,
        status: NoteStatus,
        wantsAction: Boolean,
        text: String = "note",
        direction: BotNote.Direction = BotNote.Direction.IN,
    ) = BotNote(
        id = id,
        from = "grok-bot",
        kind = NoteKind.ACT,
        text = text,
        wantsAction = wantsAction,
        createdAt = 0L,
        status = status,
        direction = direction,
    )
}
