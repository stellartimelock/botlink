package org.stellartimelock.botlink.flipsend

import android.content.Intent

/**
 * Client for FlipSend's exported QUERY_PIPE handshake.
 *
 * Bot Link asks “is FlipSend paired?” so the user does not retype a FlipSend code.
 * The answer never becomes Bot Link's room or key. Sidecar stays
 * `botlink.room.v1|…` even when FlipSend says paired.
 *
 * Contract FlipSend should export:
 *   action [FlipSendDetector.ACTION_QUERY_PIPE]
 *   package [FlipSendDetector.FLIPSEND_PACKAGE]
 *   result extra [EXTRA_PAIRED] boolean
 */
object FlipSendPipe {
    const val EXTRA_PAIRED: String = "paired"

    enum class Status {
        Missing,
        NotExported,
        Advertised,
        Paired,
        Unpaired,
        Unknown,
        ;

        fun settingsLine(): String = when (this) {
            Missing -> "FlipSend missing"
            NotExported -> "not exported — Bot Link room on the same relay"
            Advertised -> "exported — waiting for FlipSend"
            Paired -> "FlipSend says paired (Bot Link still uses its own room)"
            Unpaired -> "FlipSend says unpaired — Bot Link sidecar still works"
            Unknown -> "QUERY_PIPE present but FlipSend did not answer"
        }
    }

    fun queryIntent(): Intent =
        Intent(FlipSendDetector.ACTION_QUERY_PIPE).setPackage(FlipSendDetector.FLIPSEND_PACKAGE)

    fun initial(installed: Boolean, advertised: Boolean): Status = when {
        !installed -> Status.Missing
        !advertised -> Status.NotExported
        else -> Status.Advertised
    }

    fun parsePairedExtra(hasExtra: Boolean, paired: Boolean): Status {
        if (!hasExtra) return Status.Unknown
        return if (paired) Status.Paired else Status.Unpaired
    }

    fun parseActivityResult(resultCode: Int, extras: Map<String, Any?>?): Status {
        if (extras == null) {
            return if (resultCode == 0) Status.Unknown else Status.Unknown
        }
        if (!extras.containsKey(EXTRA_PAIRED)) return Status.Unknown
        val raw = extras[EXTRA_PAIRED]
        val paired = when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> return Status.Unknown
        }
        return parsePairedExtra(true, paired)
    }
}
