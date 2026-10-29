package org.stellartimelock.botlink.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkProtocolTest {
    @Test
    fun kindsAreSidecarNotesNotFlipSendFileRoom() {
        assertTrue(ApkProtocol.isApkKind("apk-offer"))
        assertTrue(ApkProtocol.isApkKind("apk-chunk"))
        assertTrue(ApkProtocol.isApkKind("apk-ack"))
        assertTrue(ApkProtocol.isApkKind("apk-done"))
        assertTrue(ApkProtocol.isApkKind("apk-result"))
        assertTrue(ApkProtocol.isApkKind("replace"))
        assertTrue(ApkProtocol.isApkKind("apk-query"))
        assertTrue(ApkProtocol.isApkKind("apk-info"))
        assertTrue(ApkProtocol.isOfferKind("replace"))
        assertTrue(ApkProtocol.isReplaceMode("replace", null))
        assertTrue(ApkProtocol.isReplaceMode("apk-offer", "replace"))
        assertFalse(ApkProtocol.isReplaceMode("apk-offer", "upgrade"))
        assertFalse(ApkProtocol.isApkKind("file"))
        assertFalse(ApkProtocol.isApkKind("ask"))
        assertEquals(96 * 1024, ApkProtocol.CHUNK_SIZE)
    }

    @Test
    fun chunkMathMatchesCompanion() {
        assertEquals(0, ApkProtocol.chunkCount(0))
        assertEquals(1, ApkProtocol.chunkCount(1))
        assertEquals(3, ApkProtocol.chunkCount(250_000, 100_000))
        assertEquals(200_000L, ApkProtocol.chunkOffset(2, 100_000))
        assertEquals(50_000, ApkProtocol.chunkLength(250_000, 2, 100_000))
        assertEquals(2, ApkProtocol.nextIndex(setOf(0, 1), 5))
        assertEquals(5, ApkProtocol.nextIndex(setOf(0, 1, 2, 3, 4), 5))
    }

    @Test
    fun assemblerResumesFromMissingIndex() {
        val size = 10_000L
        val chunk = 3_000
        val assembler = ApkAssembler("t1", "FlipSend-1.apk", size, "abc", chunk)
        val sink = ByteArray(size.toInt())
        val write = { offset: Long, bytes: ByteArray ->
            System.arraycopy(bytes, 0, sink, offset.toInt(), bytes.size)
        }
        assembler.accept(0, ByteArray(3_000) { 1 }, write)
        assembler.accept(2, ByteArray(3_000) { 3 }, write)
        assertEquals(1, assembler.nextIndex())
        assertFalse(assembler.complete())
        assembler.accept(1, ByteArray(3_000) { 2 }, write)
        assembler.accept(3, ByteArray(1_000) { 4 }, write)
        assertTrue(assembler.complete())
        assertEquals(4, assembler.nextIndex())
        assertEquals(1.toByte(), sink[0])
        assertEquals(2.toByte(), sink[3_000])
        assertEquals(4.toByte(), sink[9_000])
    }

    @Test
    fun restoreHaveSkipsCorruptIndices() {
        val assembler = ApkAssembler("t1", "x.apk", 4_000, "d", 1_000)
        assembler.restoreHave(listOf(0, 99, 2))
        assertEquals(1, assembler.nextIndex())
        assertEquals(setOf(0, 2), assembler.snapshotHave())
    }
}
