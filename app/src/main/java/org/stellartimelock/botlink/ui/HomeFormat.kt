package org.stellartimelock.botlink.ui

import org.stellartimelock.botlink.net.LinkStatus
import org.stellartimelock.botlink.protocol.BotNote
import org.stellartimelock.botlink.protocol.NoteKind
import org.stellartimelock.botlink.protocol.NoteStatus
import org.stellartimelock.botlink.protocol.Relay

/** Inbox rows the gold card is allowed to show: still waiting on Approve. */
internal fun pendingNotes(notes: List<BotNote>): List<BotNote> =
    notes.filter { note ->
        note.direction == BotNote.Direction.IN &&
            (note.status == NoteStatus.PENDING || note.status == NoteStatus.HELD)
    }

internal fun pendingTitle(note: BotNote): String {
    val line = note.text.trim().lineSequence().firstOrNull { it.isNotBlank() }
    return if (line.isNullOrEmpty()) note.from else line
}

internal fun pendingDetail(note: BotNote): String = when (note.status) {
    NoteStatus.HELD -> "Held · Allow is off"
    NoteStatus.PENDING -> if (note.wantsAction) "Needs your approve" else note.from
    else -> note.from
}

/** Short age, same shape as the mockup (`2m`, `14m`, `1h`). */
internal fun agoLabel(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - createdAt).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> "now"
        minutes < 60L -> "${minutes}m"
        minutes < 60L * 24 -> "${minutes / 60L}h"
        else -> "${minutes / (60L * 24)}d"
    }
}

/**
 * Twin-card subtitle. "Pipe ready" once the sidecar is listening or a peer/relay is up.
 * Package detection itself is the "Linked" line, not this string.
 */
internal fun pipeLine(link: LinkStatus): String = when {
    link.lanPeer || link.relayUp -> "Pipe ready"
    link.lanDetail.startsWith("Listening") -> "Pipe ready"
    else -> "Pipe idle"
}

/** Pending, held, and approved notes. Hidden when this list is empty. */
internal fun approvalEntries(notes: List<BotNote>): List<BotNote> =
    notes.filter { note ->
        note.direction == BotNote.Direction.IN &&
            note.kind != NoteKind.SUMMARY &&
            (note.status == NoteStatus.PENDING ||
                note.status == NoteStatus.HELD ||
                note.status == NoteStatus.APPROVED)
    }

internal fun showApprovals(notes: List<BotNote>): Boolean = approvalEntries(notes).isNotEmpty()

internal fun roomBlurbs(notes: List<BotNote>): List<BotNote> =
    notes.filter { it.kind == NoteKind.SUMMARY && it.text.isNotBlank() && !it.html.isNullOrBlank() }
        .sortedByDescending { it.createdAt }

internal fun settingsLabel(pending: Int): String =
    if (pending > 0) "Settings · $pending waiting" else "Settings"

internal fun relayCaption(link: LinkStatus): String {
    val policy = Relay.Allowance(
        tier = link.planTier.ifBlank { "free" },
        limit = link.planLimit,
        enforced = link.planEnforced,
    )
    val meter = if (link.relayTier.isBlank() && link.relayLimit == 0L) {
        null
    } else {
        Relay.Meter(
            ok = true,
            tier = link.relayTier.ifBlank { policy.tier },
            used = link.relayUsed,
            limit = link.relayLimit,
            enforced = link.relayEnforced,
        )
    }
    return Relay.planLine(policy) + " · " + Relay.usageLine(meter, policy)
}

internal fun spendCaption(): String {
    val rail = if (Relay.X402_ENABLED) "x402 on · wallet signs · Horizon submits" else "x402 off"
    return "\$1 a call · \$5 an agent each month · $rail"
}

internal fun transcriptHtml(title: String, body: String): String {
    if (body.contains("<html", ignoreCase = true)) return body
    val safe = title
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:0;background:#0B1020;color:#E8EEF9;font:16px/1.45 sans-serif;padding:20px}" +
        "h1{font-size:18px}a{color:#5EEAD4}</style></head><body><h1>$safe</h1>$body</body></html>"
}
