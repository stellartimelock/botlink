package org.stellartimelock.botlink.update

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Receives APK chunks into app storage. FlipSend `--send-apk` upgrades never
 * uninstall. `--replace-apk` (`kind=replace`) may uninstall a wrong-key
 * Stellar suite app, then install the staged APK. Only the paired PC can
 * talk on this channel (AES-GCM of the pairing secret).
 */
class ApkStore(
    context: Context,
    private val send: (JSONObject) -> Unit,
    private val inspect: (File) -> ApkGate.ApkInfo?,
    private val installed: (packageName: String) -> ApkGate.ApkInfo?,
    private val labelOf: (packageName: String, apk: File) -> String,
    private val promptInstall: (File, ApkGate.ApkInfo, String, ReplaceStore.Pending?) -> Unit,
    private val promptUninstall: (ReplaceStore.Pending) -> Unit,
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "updates")
    val replaceStore = ReplaceStore(appContext)
    private val lock = Any()
    private var session: Session? = null

    fun onPlain(obj: JSONObject) {
        when (obj.optString("kind")) {
            ApkProtocol.KIND_OFFER, ApkProtocol.KIND_REPLACE -> onOffer(obj)
            ApkProtocol.KIND_CHUNK -> onChunk(obj)
            ApkProtocol.KIND_DONE -> onDone(obj)
            ApkProtocol.KIND_QUERY -> onQuery(obj)
            else -> Unit
        }
    }

    fun report(id: String, ok: Boolean, status: String, message: String) {
        if (ok) cleanup(id)
        send(
            JSONObject()
                .put("v", ApkProtocol.VERSION)
                .put("kind", ApkProtocol.KIND_RESULT)
                .put("id", id)
                .put("ok", ok)
                .put("status", status)
                .put("message", message)
                .put("createdAt", System.currentTimeMillis()),
        )
    }

    fun resumePending() {
        val pending = replaceStore.load() ?: return
        val file = File(pending.path)
        if (!file.isFile) {
            replaceStore.clear()
            report(pending.id, false, "failed:apk_missing", "Staged APK is gone.")
            return
        }
        val stillThere = installed(pending.packageName) != null
        if (pending.phase == ReplaceStore.PHASE_UNINSTALL && !stillThere) {
            onUninstalled(pending.packageName)
            return
        }
        val info = inspect(file) ?: ApkGate.ApkInfo(
            pending.packageName,
            pending.incomingCert,
            pending.versionCode,
        )
        if (pending.phase == ReplaceStore.PHASE_UNINSTALL) {
            promptUninstall(pending)
        } else {
            promptInstall(file, info, pending.id, pending)
        }
    }

    /** On resume: if uninstall already happened, start the staged install. Do not re-prompt otherwise. */
    fun onHostResume() {
        val pending = replaceStore.load() ?: return
        if (pending.phase == ReplaceStore.PHASE_UNINSTALL && installed(pending.packageName) == null) {
            onUninstalled(pending.packageName)
        }
    }

    fun onUninstalled(packageName: String) {
        val pending = replaceStore.load() ?: return
        if (pending.packageName != packageName) return
        if (pending.phase != ReplaceStore.PHASE_UNINSTALL) return
        val next = pending.copy(phase = ReplaceStore.PHASE_INSTALL)
        replaceStore.save(next)
        report(pending.id, false, "uninstalled", "${pending.label} is off the phone. Opening Install.")
        val file = File(pending.path)
        val info = inspect(file) ?: ApkGate.ApkInfo(
            pending.packageName,
            pending.incomingCert,
            pending.versionCode,
        )
        promptInstall(file, info, pending.id, next)
    }

    private fun onQuery(obj: JSONObject) {
        val id = obj.optString("id").ifBlank { return }
        val pkg = obj.optString("package").ifBlank { return }
        val info = installed(pkg)
        val body = JSONObject()
            .put("v", ApkProtocol.VERSION)
            .put("kind", ApkProtocol.KIND_INFO)
            .put("id", id)
            .put("package", pkg)
            .put("installed", info != null)
        if (info != null) {
            body.put("certSha256", info.certSha256)
            body.put("versionCode", info.versionCode)
        }
        send(body)
    }

    private fun onOffer(obj: JSONObject) {
        val id = obj.optString("id").ifBlank { return }
        val name = File(obj.optString("name").ifBlank { "$id.apk" }).name.ifBlank { "$id.apk" }
        val size = obj.optLong("size")
        val digest = obj.optString("sha256").lowercase()
        val chunkSize = obj.optInt("chunkSize", ApkProtocol.CHUNK_SIZE)
        val mode = if (ApkProtocol.isReplaceMode(obj.optString("kind"), obj.optString("mode"))) {
            ApkProtocol.MODE_REPLACE
        } else {
            ApkProtocol.MODE_UPGRADE
        }
        if (size <= 0L || digest.isBlank() || chunkSize <= 0) {
            report(id, false, failStatus(mode, "rejected_offer"), "Bad APK offer.")
            return
        }
        if (size > MAX_APK_BYTES) {
            report(id, false, failStatus(mode, "rejected_offer"), "APK is larger than 200 MB.")
            return
        }
        synchronized(lock) {
            val existing = session
            if (existing != null && existing.assembler.id == id && existing.assembler.sha256 == digest) {
                ack(id, existing.assembler.nextIndex(), null)
                return
            }
            existing?.close()
            dir.mkdirs()
            val part = File(dir, "$id.apk.part")
            val meta = File(dir, "$id.meta.json")
            val assembler = ApkAssembler(id, name, size, digest, chunkSize)
            if (part.exists() && meta.exists()) {
                restore(assembler, meta)
            }
            val raf = RandomAccessFile(part, "rw")
            raf.setLength(size)
            session = Session(assembler, part, meta, raf, mode)
            persist(session!!)
            ack(id, assembler.nextIndex(), null)
        }
    }

    private fun onChunk(obj: JSONObject) {
        val id = obj.optString("id").ifBlank { return }
        val index = obj.optInt("index", -1)
        val raw = obj.optString("data")
        if (index < 0 || raw.isBlank()) return
        val data = try {
            Base64.decode(raw, Base64.DEFAULT)
        } catch (_: Exception) {
            return
        }
        synchronized(lock) {
            val current = session
            if (current == null || current.assembler.id != id) {
                ack(id, 0, null)
                return
            }
            try {
                val next = current.assembler.accept(index, data) { offset, bytes ->
                    current.file.seek(offset)
                    current.file.write(bytes)
                }
                persist(current)
                ack(id, next, index)
            } catch (e: Exception) {
                Log.w(TAG, "chunk $index", e)
                ack(id, current.assembler.nextIndex(), null)
            }
        }
    }

    private fun onDone(obj: JSONObject) {
        val id = obj.optString("id").ifBlank { return }
        val expected = obj.optString("sha256").lowercase()
        val file: File
        val assembler: ApkAssembler
        val mode: String
        synchronized(lock) {
            val current = session
            if (current == null || current.assembler.id != id) {
                report(id, false, "failed:rejected_hash", "No matching transfer.")
                return
            }
            if (!current.assembler.complete()) {
                ack(id, current.assembler.nextIndex(), null)
                return
            }
            current.close()
            assembler = current.assembler
            mode = current.mode
            file = replaceStore.stagedFile(id)
            dir.mkdirs()
            if (current.part.exists()) {
                current.part.copyTo(file, overwrite = true)
            }
        }
        val digest = ApkMeta.sha256File(file)
        if (digest != assembler.sha256 || (expected.isNotBlank() && expected != digest)) {
            file.delete()
            report(id, false, failStatus(mode, "rejected_hash"), "APK hash mismatch. Transfer discarded.")
            return
        }
        val incoming = inspect(file)
        if (incoming == null) {
            file.delete()
            report(id, false, failStatus(mode, "rejected_package"), "Could not read the APK.")
            return
        }
        if (mode == ApkProtocol.MODE_REPLACE) {
            finishReplace(file, incoming, id)
        } else {
            finishUpgrade(file, incoming, id)
        }
    }

    private fun finishUpgrade(file: File, incoming: ApkGate.ApkInfo, id: String) {
        val verdict = ApkGate.check(incoming, installed(ApkGate.TARGET_PACKAGE))
        if (!verdict.ok) {
            file.delete()
            report(id, false, verdict.status, verdict.message)
            return
        }
        report(id, false, "install_pending", "FlipSend update is on the phone. Tap Install.")
        promptInstall(file, incoming, id, null)
    }

    private fun finishReplace(file: File, incoming: ApkGate.ApkInfo, id: String) {
        val verdict = ReplaceGate.check(incoming, installed(incoming.packageName))
        if (!verdict.ok || verdict.action == null) {
            file.delete()
            replaceStore.clear()
            report(id, false, verdict.wireStatus(), verdict.message)
            return
        }
        val label = labelOf(incoming.packageName, file).ifBlank { incoming.packageName.substringAfterLast('.') }
        val action = when (verdict.action) {
            ReplaceGate.Action.INSTALL -> ReplaceStore.ACTION_INSTALL
            ReplaceGate.Action.UPGRADE -> ReplaceStore.ACTION_UPGRADE
            ReplaceGate.Action.REINSTALL -> ReplaceStore.ACTION_REINSTALL
        }
        val phase = if (verdict.action == ReplaceGate.Action.REINSTALL) {
            ReplaceStore.PHASE_UNINSTALL
        } else {
            ReplaceStore.PHASE_INSTALL
        }
        val pending = ReplaceStore.Pending(
            id = id,
            packageName = incoming.packageName,
            path = file.absolutePath,
            label = label,
            versionCode = incoming.versionCode,
            incomingCert = incoming.certSha256,
            action = action,
            phase = phase,
        )
        replaceStore.save(pending)
        report(
            id,
            false,
            "replace_pending",
            if (verdict.action == ReplaceGate.Action.REINSTALL) {
                "Confirm Uninstall for $label, then Install. Phone data clears; StellarTimeLock backups/$label is kept."
            } else {
                "$label is staged. Tap Install."
            },
        )
        if (verdict.action == ReplaceGate.Action.REINSTALL) {
            promptUninstall(pending)
        } else {
            promptInstall(file, incoming, id, pending)
        }
    }

    private fun cleanup(id: String) {
        val pending = replaceStore.load()
        if (pending?.id == id) {
            replaceStore.deleteStaged(id, File(pending.path))
            replaceStore.clear()
        } else {
            replaceStore.deleteStaged(id)
        }
    }

    private fun failStatus(mode: String, reason: String): String =
        if (mode == ApkProtocol.MODE_REPLACE) "failed:$reason" else reason

    private fun ack(id: String, next: Int, got: Int?) {
        val body = JSONObject()
            .put("v", ApkProtocol.VERSION)
            .put("kind", ApkProtocol.KIND_ACK)
            .put("id", id)
            .put("next", next)
        if (got != null) body.put("got", got)
        send(body)
    }

    private fun persist(current: Session) {
        val have = JSONArray()
        current.assembler.snapshotHave().sorted().forEach { have.put(it) }
        current.meta.writeText(
            JSONObject()
                .put("id", current.assembler.id)
                .put("name", current.assembler.name)
                .put("size", current.assembler.size)
                .put("sha256", current.assembler.sha256)
                .put("chunkSize", current.assembler.chunkSize)
                .put("chunks", current.assembler.chunks)
                .put("mode", current.mode)
                .put("have", have)
                .toString(),
        )
    }

    private fun restore(assembler: ApkAssembler, meta: File) {
        try {
            val obj = JSONObject(meta.readText())
            if (obj.optString("sha256").lowercase() != assembler.sha256) return
            if (obj.optLong("size") != assembler.size) return
            val arr = obj.optJSONArray("have") ?: return
            val have = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) have.add(arr.optInt(i))
            assembler.restoreHave(have)
        } catch (_: Exception) {
            Unit
        }
    }

    private class Session(
        val assembler: ApkAssembler,
        val part: File,
        val meta: File,
        val file: RandomAccessFile,
        val mode: String,
    ) {
        fun close() {
            try {
                file.close()
            } catch (_: Exception) {
                Unit
            }
        }
    }

    companion object {
        private const val TAG = "BotLinkApk"
        private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    }
}
