package org.stellartimelock.botlink.update

/**
 * Install FlipSend only. Never downgrade. Never uninstall.
 *
 * All three must hold: package org.stellartimelock.stellarsend, signing
 * cert SHA-256 starts with 7994727d (same house FlipSend signer), and
 * versionCode higher than the copy already on the phone.
 */
object ApkGate {
    const val TARGET_PACKAGE: String = "org.stellartimelock.stellarsend"
    const val SIGNER_PREFIX: String = "7994727d"

    data class ApkInfo(
        val packageName: String,
        val certSha256: String,
        val versionCode: Long,
    )

    data class Verdict(
        val ok: Boolean,
        val status: String,
        val message: String,
    )

    fun normalizeCert(sha256: String): String =
        sha256.lowercase().replace(":", "").replace(" ", "").trim()

    fun signerMatches(sha256: String): Boolean =
        normalizeCert(sha256).startsWith(SIGNER_PREFIX)

    fun check(incoming: ApkInfo, installed: ApkInfo?): Verdict {
        if (incoming.packageName != TARGET_PACKAGE) {
            return Verdict(
                false,
                "rejected_package",
                "Refused ${incoming.packageName}. Only $TARGET_PACKAGE may be installed this way.",
            )
        }
        if (!signerMatches(incoming.certSha256)) {
            return Verdict(
                false,
                "rejected_signer",
                "Refused signer ${normalizeCert(incoming.certSha256).take(8)}…. FlipSend stays on $SIGNER_PREFIX.",
            )
        }
        if (installed == null) {
            return Verdict(
                false,
                "rejected_missing",
                "FlipSend is not installed. Bot Link will not sideload a first copy.",
            )
        }
        if (installed.packageName != TARGET_PACKAGE) {
            return Verdict(
                false,
                "rejected_package",
                "Installed package ${installed.packageName} is not FlipSend.",
            )
        }
        if (!signerMatches(installed.certSha256)) {
            return Verdict(
                false,
                "rejected_signer",
                "Installed FlipSend is not on signer $SIGNER_PREFIX. Will not replace it.",
            )
        }
        if (incoming.versionCode <= installed.versionCode) {
            return Verdict(
                false,
                "rejected_version",
                "Never downgrade. Incoming ${incoming.versionCode} is not higher than ${installed.versionCode}.",
            )
        }
        return Verdict(
            true,
            "ok",
            "FlipSend ${incoming.versionCode} is newer and on signer $SIGNER_PREFIX.",
        )
    }
}
