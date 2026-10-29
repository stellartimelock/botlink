package org.stellartimelock.botlink.update

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.stellartimelock.botlink.ui.theme.BotLinkTheme
import java.io.File

/**
 * FlipSend upgrade: one Install tap.
 * Key-switch replace: auto-raise Uninstall confirm, then auto-raise Install.
 * Android 15 needs this activity (or its notification tap) as the user action.
 */
class InstallUpdateActivity : ComponentActivity() {
    private var apkPath: String = ""
    private var transferId: String = ""
    private var versionLabel: String = ""
    private var targetPackage: String = ApkGate.TARGET_PACKAGE
    private var appLabel: String = "FlipSend"
    private var replace: Boolean = false
    private var phase: String = PHASE_INSTALL
    private var auto: Boolean = true
    private var waitingPermission = false
    private var message by mutableStateOf("Update is ready.")
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InstallBridge.activity = this
        loadExtras(intent)
        setContent {
            BotLinkTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(titleText(), style = MaterialTheme.typography.titleLarge)
                    CardBlock {
                        Text(bodyText(), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(message, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
                    Button(
                        onClick = { go() },
                        enabled = !busy && (apkPath.isNotBlank() || phase == PHASE_UNINSTALL),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(buttonText()) }
                }
            }
        }
        if (auto) go()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadExtras(intent)
        busy = false
        if (auto) go()
    }

    override fun onResume() {
        super.onResume()
        InstallBridge.activity = this
        if (waitingPermission && canInstall()) {
            waitingPermission = false
            startInstall()
            return
        }
        if (phase == PHASE_UNINSTALL && !isInstalled(targetPackage)) {
            onUninstalledLocal()
        }
    }

    override fun onDestroy() {
        if (InstallBridge.activity === this) InstallBridge.activity = null
        super.onDestroy()
    }

    fun startConfirmation(confirm: Intent) {
        try {
            startActivity(confirm)
        } catch (e: Exception) {
            message = e.message ?: "Could not open the system prompt."
            val status = if (phase == PHASE_UNINSTALL) "failed:uninstall_failed" else "install_failed"
            InstallBridge.report(this, transferId, false, status, message)
        }
    }

    private fun loadExtras(intent: Intent) {
        apkPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        transferId = intent.getStringExtra(EXTRA_ID).orEmpty()
        versionLabel = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty().ifBlank { ApkGate.TARGET_PACKAGE }
        appLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "FlipSend" }
        replace = intent.getBooleanExtra(EXTRA_REPLACE, false)
        phase = intent.getStringExtra(EXTRA_PHASE).orEmpty().ifBlank { PHASE_INSTALL }
        auto = intent.getBooleanExtra(EXTRA_AUTO, true)
        message = if (phase == PHASE_UNINSTALL) {
            "Opening Uninstall…"
        } else {
            "$appLabel is ready to install."
        }
    }

    private fun titleText(): String =
        if (phase == PHASE_UNINSTALL) "Replace $appLabel" else "Install $appLabel"

    private fun bodyText(): String {
        val versionBit = if (versionLabel.isBlank()) "" else " ($versionLabel)"
        return if (replace && phase == PHASE_UNINSTALL) {
            "This app's phone data clears, but StellarTimeLock backups/$appLabel is kept."
        } else if (replace) {
            "Install $appLabel$versionBit. StellarTimeLock backups/$appLabel is kept."
        } else if (versionLabel.isBlank()) {
            "Replace the FlipSend already on this phone. Same package, same signer. No uninstall."
        } else {
            "Replace the FlipSend already on this phone with $versionLabel. Same package, same signer. No uninstall."
        }
    }

    private fun buttonText(): String = if (phase == PHASE_UNINSTALL) "Uninstall" else "Install"

    private fun go() {
        if (phase == PHASE_UNINSTALL) startUninstall() else startInstall()
    }

    private fun onUninstalledLocal() {
        if (phase != PHASE_UNINSTALL) return
        phase = PHASE_INSTALL
        busy = false
        message = "Uninstalled. Opening Install…"
        InstallBridge.store?.onUninstalled(targetPackage)
    }

    private fun startUninstall() {
        if (busy) return
        if (targetPackage.isBlank() || targetPackage == ReplaceGate.SELF_PACKAGE) {
            message = "Will not uninstall Bot Link."
            return
        }
        if (!isInstalled(targetPackage)) {
            onUninstalledLocal()
            return
        }
        busy = true
        message = "Opening the system uninstall prompt…"
        try {
            val installer = packageManager.packageInstaller
            val status = Intent(this, InstallResultReceiver::class.java).apply {
                putExtra(EXTRA_PATH, apkPath)
                putExtra(EXTRA_ID, transferId)
                putExtra(EXTRA_PACKAGE, targetPackage)
                putExtra(EXTRA_OP, OP_UNINSTALL)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(this, UNINSTALL_REQ, status, flags)
            installer.uninstall(targetPackage, pending.intentSender)
        } catch (e: Exception) {
            try {
                val del = Intent(Intent.ACTION_DELETE, Uri.parse("package:$targetPackage"))
                startActivity(del)
                busy = false
                message = "Confirm Uninstall. This app's phone data clears, but StellarTimeLock backups/$appLabel is kept."
            } catch (e2: Exception) {
                busy = false
                message = e2.message ?: e.message ?: "Uninstall failed."
                InstallBridge.report(this, transferId, false, "failed:uninstall_failed", message)
            }
        }
    }

    private fun startInstall() {
        if (busy) return
        if (!canInstall()) {
            waitingPermission = true
            val uri = Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri))
            message = "Allow installs from Bot Link, then come back. One more tap."
            return
        }
        busy = true
        message = "Opening the system installer…"
        try {
            commitSession()
        } catch (e: Exception) {
            busy = false
            message = e.message ?: "Install failed."
            val status = if (replace) "failed:install_failed" else "install_failed"
            InstallBridge.report(this, transferId, false, status, message)
        }
    }

    private fun commitSession() {
        val apk = File(apkPath)
        if (!apk.isFile) throw IllegalStateException("APK missing from app storage.")
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(targetPackage)
        if (Build.VERSION.SDK_INT >= 34) {
            params.setDontKillApp(true)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(this, InstallResultReceiver::class.java).apply {
                putExtra(EXTRA_PATH, apkPath)
                putExtra(EXTRA_ID, transferId)
                putExtra(EXTRA_PACKAGE, targetPackage)
                putExtra(EXTRA_OP, OP_INSTALL)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(this, sessionId, status, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun isInstalled(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        const val EXTRA_PATH: String = "apk_path"
        const val EXTRA_ID: String = "transfer_id"
        const val EXTRA_VERSION: String = "version_label"
        const val EXTRA_AUTO: String = "auto"
        const val EXTRA_PACKAGE: String = "apk_package"
        const val EXTRA_LABEL: String = "apk_label"
        const val EXTRA_REPLACE: String = "apk_replace"
        const val EXTRA_PHASE: String = "apk_phase"
        const val EXTRA_OP: String = "apk_op"
        const val PHASE_INSTALL: String = "install"
        const val PHASE_UNINSTALL: String = "uninstall"
        const val OP_INSTALL: String = "install"
        const val OP_UNINSTALL: String = "uninstall"
        private const val UNINSTALL_REQ: Int = 73
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        content = content,
    )
}
