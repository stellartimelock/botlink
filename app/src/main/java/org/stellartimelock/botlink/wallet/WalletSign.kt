package org.stellartimelock.botlink.wallet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.stellartimelock.botlink.protocol.X402

/**
 * Hands a SEP-7 URI to StellarTimeLock (then Scopuly). Bot Link never holds keys
 * and never creates a Stellar account.
 */
object WalletSign {
    fun launch(context: Context, sep7: String): String {
        if (sep7.isBlank() || !sep7.startsWith("web+stellar:")) {
            return "No SEP-7 signing request on this note."
        }
        val uri = Uri.parse(sep7)
        val packages = listOf(X402.WALLET_PACKAGE, X402.SCOPULY_PACKAGE, null)
        for (pkg in packages) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) setPackage(pkg)
            }
            try {
                context.startActivity(intent)
                return when (pkg) {
                    X402.WALLET_PACKAGE -> "Opened StellarTimeLock to sign."
                    X402.SCOPULY_PACKAGE -> "Opened Scopuly to sign."
                    else -> "Opened a SEP-7 wallet to sign."
                }
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        return "Install StellarTimeLock to sign. Bot Link does not hold keys."
    }

    fun xdrFromCallback(uri: Uri?): String? {
        if (uri == null) return null
        if (uri.scheme != "botlink") return null
        if (uri.host != "x402") return null
        val path = uri.path ?: ""
        if (path != "/signed" && path != "signed") return null
        val xdr = uri.getQueryParameter("xdr")
            ?: uri.getQueryParameter("tx")
            ?: uri.getQueryParameter("signed_xdr")
        return xdr?.takeIf { it.isNotBlank() }
    }
}
