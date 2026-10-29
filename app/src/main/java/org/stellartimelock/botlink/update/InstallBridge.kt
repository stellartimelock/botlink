package org.stellartimelock.botlink.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import org.stellartimelock.botlink.BotLinkApp
import org.stellartimelock.botlink.MainActivity
import org.stellartimelock.botlink.R
import java.io.File

/**
 * Foreground activity + high-priority notification so Android 15 will let the
 * user open the system installer (and the uninstall confirm on a key switch).
 * The two taps Android forces: Uninstall confirm, Install confirm.
 */
object InstallBridge {
    @Volatile
    var activity: InstallUpdateActivity? = null

    @Volatile
    var reporter: ((String, Boolean, String, String) -> Unit)? = null

    @Volatile
    var store: ApkStore? = null

    const val NOTICE_ID: Int = 71
    const val UNINSTALL_NOTICE_ID: Int = 72

    fun prompt(context: Context, file: File, info: ApkGate.ApkInfo, transferId: String) {
        promptInstall(context, file, info, transferId, null)
    }

    fun promptInstall(
        context: Context,
        file: File,
        info: ApkGate.ApkInfo,
        transferId: String,
        pending: ReplaceStore.Pending?,
    ) {
        val app = context.applicationContext
        val replace = pending != null
        val label = pending?.label?.ifBlank { info.packageName } ?: "FlipSend"
        val version = "versionCode ${info.versionCode}"
        val open = Intent(app, InstallUpdateActivity::class.java)
            .putExtra(InstallUpdateActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(InstallUpdateActivity.EXTRA_ID, transferId)
            .putExtra(InstallUpdateActivity.EXTRA_VERSION, version)
            .putExtra(InstallUpdateActivity.EXTRA_AUTO, true)
            .putExtra(InstallUpdateActivity.EXTRA_PACKAGE, info.packageName)
            .putExtra(InstallUpdateActivity.EXTRA_LABEL, label)
            .putExtra(InstallUpdateActivity.EXTRA_REPLACE, replace)
            .putExtra(InstallUpdateActivity.EXTRA_PHASE, InstallUpdateActivity.PHASE_INSTALL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val act = activity
        if (act != null) {
            act.startActivity(open)
        } else if (MainActivity.isInForeground) {
            app.startActivity(open)
        }
        val title = if (replace) "Install $label" else "Install FlipSend update"
        val text = if (replace) {
            "Tap Install. This app's phone data already cleared if it was uninstalled."
        } else {
            "Tap Install. Same package, same signer, newer only."
        }
        notify(app, open, title, text, NOTICE_ID)
    }

    fun promptUninstall(context: Context, pending: ReplaceStore.Pending) {
        val app = context.applicationContext
        val open = Intent(app, InstallUpdateActivity::class.java)
            .putExtra(InstallUpdateActivity.EXTRA_PATH, pending.path)
            .putExtra(InstallUpdateActivity.EXTRA_ID, pending.id)
            .putExtra(InstallUpdateActivity.EXTRA_VERSION, "versionCode ${pending.versionCode}")
            .putExtra(InstallUpdateActivity.EXTRA_AUTO, true)
            .putExtra(InstallUpdateActivity.EXTRA_PACKAGE, pending.packageName)
            .putExtra(InstallUpdateActivity.EXTRA_LABEL, pending.label)
            .putExtra(InstallUpdateActivity.EXTRA_REPLACE, true)
            .putExtra(InstallUpdateActivity.EXTRA_PHASE, InstallUpdateActivity.PHASE_UNINSTALL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val act = activity
        if (act != null) {
            act.startActivity(open)
        } else if (MainActivity.isInForeground) {
            app.startActivity(open)
        }
        notify(
            app,
            open,
            "Uninstall ${pending.label}",
            "This app's phone data clears, but StellarTimeLock backups/${pending.label} is kept.",
            UNINSTALL_NOTICE_ID,
        )
    }

    fun confirm(context: Context, confirm: Intent, transferId: String, uninstall: Boolean) {
        val act = activity
        if (act != null) {
            act.startConfirmation(confirm)
            return
        }
        val open = Intent(context, InstallUpdateActivity::class.java)
            .putExtra(InstallUpdateActivity.EXTRA_ID, transferId)
            .putExtra(InstallUpdateActivity.EXTRA_REPLACE, store?.replaceStore?.load()?.id == transferId)
            .putExtra(
                InstallUpdateActivity.EXTRA_PHASE,
                if (uninstall) InstallUpdateActivity.PHASE_UNINSTALL else InstallUpdateActivity.PHASE_INSTALL,
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val title = if (uninstall) "Finish uninstall" else "Finish install"
        notify(context.applicationContext, open, title, "Tap to open the system prompt.", NOTICE_ID)
        try {
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
        } catch (_: Exception) {
            Unit
        }
    }

    fun report(context: Context, transferId: String, ok: Boolean, status: String, message: String) {
        reporter?.invoke(transferId, ok, status, message)
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (ok || status == "uninstalled") {
            mgr.cancel(NOTICE_ID)
            mgr.cancel(UNINSTALL_NOTICE_ID)
        } else if (status != "install_pending" && status != "replace_pending") {
            notify(
                context.applicationContext,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "App update",
                message,
                NOTICE_ID,
            )
        }
    }

    fun notify(context: Context, open: Intent, title: String, text: String, id: Int = NOTICE_ID) {
        val pending = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, BotLinkApp.UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val transferId = intent.getStringExtra(InstallUpdateActivity.EXTRA_ID).orEmpty()
        val op = intent.getStringExtra(InstallUpdateActivity.EXTRA_OP).orEmpty()
        val uninstall = op == InstallUpdateActivity.OP_UNINSTALL
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    InstallBridge.confirm(context, confirm, transferId, uninstall)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (uninstall) {
                    val pkg = intent.getStringExtra(InstallUpdateActivity.EXTRA_PACKAGE).orEmpty()
                    InstallBridge.store?.onUninstalled(pkg)
                } else {
                    InstallBridge.report(context, transferId, true, "installed", "App updated.")
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val reason = if (uninstall) "failed:uninstall_canceled" else "install_failed"
                val text = if (uninstall) "Uninstall canceled." else "Install canceled."
                val statusName = if (uninstall) reason else "install_failed"
                InstallBridge.report(context, transferId, false, statusName, text)
            }
            else -> {
                val detail = message.ifBlank { "Failed ($status)" }
                val statusName = if (uninstall) "failed:uninstall_failed" else "install_failed"
                InstallBridge.report(context, transferId, false, statusName, detail)
            }
        }
    }
}

class PackageRemovedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_REMOVED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        InstallBridge.store?.onUninstalled(pkg)
    }
}
