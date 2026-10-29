package org.stellartimelock.botlink

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import org.stellartimelock.botlink.data.NoteRepository
import org.stellartimelock.botlink.flipsend.FlipSendDetector
import org.stellartimelock.botlink.net.LinkHub
import org.stellartimelock.botlink.pairing.PairingStore
import org.stellartimelock.botlink.update.ApkMeta
import org.stellartimelock.botlink.update.ApkStore
import org.stellartimelock.botlink.update.InstallBridge
import org.stellartimelock.botlink.update.PackageRemovedReceiver

class BotLinkApp : Application() {
    lateinit var pairing: PairingStore
        private set
    lateinit var notes: NoteRepository
        private set
    lateinit var flipSend: FlipSendDetector
        private set
    lateinit var hub: LinkHub
        private set
    lateinit var updates: ApkStore
        private set

    override fun onCreate() {
        super.onCreate()
        createChannel()
        pairing = PairingStore(this)
        notes = NoteRepository(this)
        flipSend = FlipSendDetector(this)
        hub = LinkHub(this, pairing, notes)
        updates = ApkStore(
            this,
            send = { hub.pushJson(it) },
            inspect = { ApkMeta.fromFile(packageManager, it) },
            installed = { pkg -> ApkMeta.fromInstalled(packageManager, pkg) },
            labelOf = { pkg, apk -> ApkMeta.label(packageManager, pkg, apk) },
            promptInstall = { file, info, id, pending ->
                InstallBridge.promptInstall(this, file, info, id, pending)
            },
            promptUninstall = { pending -> InstallBridge.promptUninstall(this, pending) },
        )
        hub.apkStore = updates
        InstallBridge.store = updates
        InstallBridge.reporter = { id, ok, status, message -> updates.report(id, ok, status, message) }
        registerPackageRemoved()
        updates.resumePending()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun registerPackageRemoved() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")
        ContextCompat.registerReceiver(
            this,
            PackageRemovedReceiver(),
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    companion object {
        const val CHANNEL_ID = "botlink.pipe"
        const val UPDATE_CHANNEL_ID = "botlink.update"
    }
}
