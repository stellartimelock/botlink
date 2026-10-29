package org.stellartimelock.botlink.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Read package name, versionCode, and signing cert SHA-256 from an APK or the installed app. */
object ApkMeta {
    fun fromInstalled(pm: PackageManager, packageName: String): ApkGate.ApkInfo? {
        return try {
            fromPackageInfo(pm.getPackageInfo(packageName, signingFlags()))
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun fromFile(pm: PackageManager, file: File): ApkGate.ApkInfo? {
        val path = file.absolutePath
        val info = pm.getPackageArchiveInfo(path, signingFlags()) ?: return null
        info.applicationInfo?.sourceDir = path
        info.applicationInfo?.publicSourceDir = path
        return fromPackageInfo(info)
    }

    fun fromPackageInfo(info: PackageInfo): ApkGate.ApkInfo? {
        val pkg = info.packageName ?: return null
        val cert = firstCertSha256(info) ?: return null
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return ApkGate.ApkInfo(packageName = pkg, certSha256 = cert, versionCode = version)
    }

    fun firstCertSha256(info: PackageInfo): String? {
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            val signers = info.signingInfo?.apkContentsSigners
            signers?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        return sha256Hex(bytes)
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun label(pm: PackageManager, packageName: String, apk: File? = null): String {
        try {
            val app = pm.getApplicationInfo(packageName, 0)
            val text = pm.getApplicationLabel(app)?.toString().orEmpty()
            if (text.isNotBlank()) return text
        } catch (_: PackageManager.NameNotFoundException) {
            Unit
        }
        if (apk != null) {
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            val app = info?.applicationInfo
            if (app != null) {
                app.sourceDir = apk.absolutePath
                app.publicSourceDir = apk.absolutePath
                val text = pm.getApplicationLabel(app)?.toString().orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        return packageName.substringAfterLast('.').ifBlank { packageName }
    }

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }
}
