package org.stellartimelock.botlink.update

/**
 * Chunked APK over Bot Link's encrypted sidecar.
 * Same JSON notes / AES-GCM SSND frames. Never FlipSend's file room.
 *
 * `kind=replace` is a suite key-switch offer (same chunks as apk-offer).
 * `mode=replace` on an apk-offer is the same thing.
 */
object ApkProtocol {
    const val VERSION: Int = 1
    const val CHUNK_SIZE: Int = 96 * 1024
    const val KIND_OFFER: String = "apk-offer"
    const val KIND_REPLACE: String = "replace"
    const val KIND_CHUNK: String = "apk-chunk"
    const val KIND_ACK: String = "apk-ack"
    const val KIND_DONE: String = "apk-done"
    const val KIND_RESULT: String = "apk-result"
    const val KIND_QUERY: String = "apk-query"
    const val KIND_INFO: String = "apk-info"

    const val MODE_UPGRADE: String = "upgrade"
    const val MODE_REPLACE: String = "replace"

    val KINDS: Set<String> = setOf(
        KIND_OFFER,
        KIND_REPLACE,
        KIND_CHUNK,
        KIND_ACK,
        KIND_DONE,
        KIND_RESULT,
        KIND_QUERY,
        KIND_INFO,
    )

    fun isApkKind(kind: String?): Boolean = kind != null && kind in KINDS

    fun isOfferKind(kind: String?): Boolean =
        kind == KIND_OFFER || kind == KIND_REPLACE

    fun isReplaceMode(kind: String?, mode: String?): Boolean {
        if (kind == KIND_REPLACE) return true
        return mode == MODE_REPLACE
    }

    fun chunkCount(size: Long, chunkSize: Int = CHUNK_SIZE): Int {
        require(size >= 0L) { "size must be >= 0" }
        require(chunkSize > 0) { "chunkSize must be > 0" }
        if (size == 0L) return 0
        return ((size + chunkSize - 1) / chunkSize).toInt()
    }

    fun chunkOffset(index: Int, chunkSize: Int = CHUNK_SIZE): Long {
        require(index >= 0) { "index must be >= 0" }
        return index.toLong() * chunkSize
    }

    fun chunkLength(size: Long, index: Int, chunkSize: Int = CHUNK_SIZE): Int {
        val offset = chunkOffset(index, chunkSize)
        require(offset < size) { "index past end of file" }
        return minOf(chunkSize.toLong(), size - offset).toInt()
    }

    fun nextIndex(have: Set<Int>, chunks: Int): Int {
        require(chunks >= 0) { "chunks must be >= 0" }
        var idx = 0
        while (idx < chunks && idx in have) idx += 1
        return idx
    }

    data class Offer(
        val id: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val chunkSize: Int,
        val chunks: Int,
    )

    data class Result(
        val id: String,
        val ok: Boolean,
        val status: String,
        val message: String,
    )
}

class ApkAssembler(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val chunkSize: Int,
) {
    val chunks: Int = ApkProtocol.chunkCount(size, chunkSize)
    private val have = HashSet<Int>()

    fun accept(index: Int, data: ByteArray, write: (offset: Long, bytes: ByteArray) -> Unit): Int {
        val length = ApkProtocol.chunkLength(size, index, chunkSize)
        require(data.size == length) { "chunk $index length ${data.size} != $length" }
        write(ApkProtocol.chunkOffset(index, chunkSize), data)
        have.add(index)
        return nextIndex()
    }

    fun nextIndex(): Int = ApkProtocol.nextIndex(have, chunks)

    fun complete(): Boolean = nextIndex() == chunks

    fun snapshotHave(): Set<Int> = have.toSet()

    fun restoreHave(indices: Collection<Int>) {
        have.clear()
        have.addAll(indices.filter { it in 0 until chunks })
    }
}
