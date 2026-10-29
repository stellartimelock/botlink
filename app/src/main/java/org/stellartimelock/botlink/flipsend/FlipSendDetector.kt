package org.stellartimelock.botlink.flipsend

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * FlipSend is a separate app. Bot Link only looks it up — it does not embed FlipSend UI.
 *
 * Handshake: [FlipSendPipe] fires QUERY_PIPE when FlipSend exports it.
 * Bot Link still uses a *sidecar* room hash (`botlink.room.v1|…`), never FlipSend's live file room.
 */
class FlipSendDetector(private val context: Context) {
    fun isInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(FLIPSEND_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchIntent(): Intent? {
        return context.packageManager.getLaunchIntentForPackage(FLIPSEND_PACKAGE)
    }

    fun pipeExport(): PipeExport {
        val probe = FlipSendPipe.queryIntent()
        val resolved = context.packageManager.resolveActivity(probe, 0)
        return if (resolved == null) PipeExport.Missing else PipeExport.Advertised
    }

    fun pipeStatus(): FlipSendPipe.Status =
        FlipSendPipe.initial(isInstalled(), pipeExport() == PipeExport.Advertised)

    companion object {
        const val FLIPSEND_PACKAGE = "org.stellartimelock.stellarsend"
        const val ACTION_QUERY_PIPE = "org.stellartimelock.stellarsend.action.QUERY_PIPE"
    }
}

enum class PipeExport {
    Missing,
    Advertised,
}
