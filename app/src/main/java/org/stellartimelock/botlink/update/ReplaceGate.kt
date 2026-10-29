package org.stellartimelock.botlink.update

/**
 * Key-switch / suite reinstall gate for `--replace-apk`.
 *
 * Incoming APKs must be a Stellar suite package (prefix allowlist) and
 * signed with the house key (`fac61745`) or FlipSend (`7994727d`). Never
 * another cert. Same cert: upgrade only (higher versionCode). Different
 * cert: uninstall then install. Bot Link itself is never a target.
 *
 * Keep in sync with companion/apk_xfer.py.
 */
object ReplaceGate {
    const val SELF_PACKAGE: String = "org.stellartimelock.botlink"
    const val HOUSE_SIGNER: String = "fac61745"
    const val FLIPSEND_SIGNER: String = "7994727d"

    /** Stellar suite packages this path may touch. Documented constant. */
    val STELLAR_PREFIXES: List<String> = listOf(
        "org.stellartimelock.",
        "com.stellartimelock.",
        "app.stellar.",
    )

    val ALLOWED_SIGNERS: List<String> = listOf(HOUSE_SIGNER, FLIPSEND_SIGNER)

    enum class Action {
        INSTALL,
        UPGRADE,
        REINSTALL,
    }

    data class Verdict(
        val ok: Boolean,
        val status: String,
        val message: String,
        val action: Action? = null,
    ) {
        fun wireStatus(): String = if (ok) status else "failed:$status"
    }

    fun allowedPackage(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == SELF_PACKAGE) return false
        return STELLAR_PREFIXES.any { prefix ->
            packageName.startsWith(prefix) || packageName == prefix.removeSuffix(".")
        }
    }

    fun allowedSigner(sha256: String): Boolean {
        val n = ApkGate.normalizeCert(sha256)
        return ALLOWED_SIGNERS.any { n.startsWith(it) }
    }

    fun sameCert(a: String, b: String): Boolean =
        ApkGate.normalizeCert(a) == ApkGate.normalizeCert(b)

    fun check(incoming: ApkGate.ApkInfo, installed: ApkGate.ApkInfo?): Verdict {
        if (!allowedPackage(incoming.packageName)) {
            val why = if (incoming.packageName == SELF_PACKAGE) {
                "Bot Link will not replace itself."
            } else {
                "Refused ${incoming.packageName}. Only Stellar suite packages may be replaced this way."
            }
            val status = if (incoming.packageName == SELF_PACKAGE) "rejected_self" else "rejected_package"
            return Verdict(false, status, why)
        }
        if (!allowedSigner(incoming.certSha256)) {
            return Verdict(
                false,
                "rejected_signer",
                "Refused signer ${ApkGate.normalizeCert(incoming.certSha256).take(8)}…. " +
                    "Replace stays on $HOUSE_SIGNER or $FLIPSEND_SIGNER.",
            )
        }
        if (installed == null) {
            if (incoming.packageName == ApkGate.TARGET_PACKAGE) {
                return Verdict(
                    false,
                    "rejected_missing",
                    "FlipSend is not installed. Bot Link will not sideload a first copy.",
                )
            }
            return Verdict(
                true,
                "ok",
                "${incoming.packageName} is not installed. Will sideload on house/FlipSend signer.",
                Action.INSTALL,
            )
        }
        if (installed.packageName != incoming.packageName) {
            return Verdict(
                false,
                "rejected_package",
                "Installed package ${installed.packageName} does not match ${incoming.packageName}.",
            )
        }
        if (sameCert(incoming.certSha256, installed.certSha256)) {
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
                "${incoming.packageName} ${incoming.versionCode} is newer on the same cert. Upgrade, no uninstall.",
                Action.UPGRADE,
            )
        }
        return Verdict(
            true,
            "ok",
            "${incoming.packageName} is on a different cert. Uninstall, then install the staged APK.",
            Action.REINSTALL,
        )
    }
}
