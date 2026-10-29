package org.stellartimelock.botlink.update

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Staged APK + pending uninstall/install across the system dialog and a restart. */
class ReplaceStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "updates")
    private val file = File(dir, PENDING_NAME)

    data class Pending(
        val id: String,
        val packageName: String,
        val path: String,
        val label: String,
        val versionCode: Long,
        val incomingCert: String,
        val action: String,
        val phase: String,
    )

    fun dir(): File = dir

    fun stagedFile(id: String): File = File(dir, "$id.apk")

    fun save(pending: Pending) {
        dir.mkdirs()
        file.writeText(
            JSONObject()
                .put("id", pending.id)
                .put("packageName", pending.packageName)
                .put("path", pending.path)
                .put("label", pending.label)
                .put("versionCode", pending.versionCode)
                .put("incomingCert", pending.incomingCert)
                .put("action", pending.action)
                .put("phase", pending.phase)
                .toString(),
        )
    }

    fun load(): Pending? {
        if (!file.isFile) return null
        return try {
            val obj = JSONObject(file.readText())
            val pending = Pending(
                id = obj.optString("id"),
                packageName = obj.optString("packageName"),
                path = obj.optString("path"),
                label = obj.optString("label"),
                versionCode = obj.optLong("versionCode"),
                incomingCert = obj.optString("incomingCert"),
                action = obj.optString("action"),
                phase = obj.optString("phase"),
            )
            if (pending.id.isBlank() || pending.packageName.isBlank() || pending.path.isBlank()) {
                null
            } else {
                pending
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    fun deleteStaged(id: String, extra: File? = null) {
        stagedFile(id).delete()
        extra?.delete()
        File(dir, "$id.apk.part").delete()
        File(dir, "$id.meta.json").delete()
    }

    companion object {
        const val PENDING_NAME: String = "replace-pending.json"
        const val PHASE_UNINSTALL: String = "await_uninstall"
        const val PHASE_INSTALL: String = "await_install"
        const val ACTION_INSTALL: String = "install"
        const val ACTION_UPGRADE: String = "upgrade"
        const val ACTION_REINSTALL: String = "reinstall"
    }
}
