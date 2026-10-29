package org.stellartimelock.botlink.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.stellartimelock.botlink.protocol.BotNote
import org.stellartimelock.botlink.protocol.NoteKind
import org.stellartimelock.botlink.protocol.NoteStatus
import org.stellartimelock.botlink.protocol.SeedGuard
import java.io.File

class NoteRepository(context: Context) {
    private val file = File(context.filesDir, "notes.json")
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<BotNote>> = _notes.asStateFlow()

    fun inbox(): List<BotNote> = _notes.value.filter { it.direction == BotNote.Direction.IN }
    fun outbox(): List<BotNote> = _notes.value.filter { it.direction == BotNote.Direction.OUT }
    fun pendingCount(): Int = _notes.value.count {
        it.direction == BotNote.Direction.IN && it.status in setOf(NoteStatus.PENDING, NoteStatus.HELD)
    }

    @Synchronized
    fun upsert(note: BotNote) {
        val next = _notes.value.filterNot { it.id == note.id } + note
        _notes.value = next.sortedByDescending { it.createdAt }
        persist()
    }

    @Synchronized
    fun decide(id: String, approved: Boolean): BotNote? {
        val current = _notes.value.find { it.id == id } ?: return null
        val updated = current.copy(
            status = if (approved) NoteStatus.APPROVED else NoteStatus.DENIED,
            decisionAt = System.currentTimeMillis(),
        )
        upsert(updated)
        return updated
    }

    fun acceptIncoming(rawKind: String, draft: BotNote, actionsArmed: Boolean): BotNote {
        val rejected = SeedGuard.looksLikeSeed(draft.kind, rawKind, draft.text)
        val status = when {
            rejected -> NoteStatus.REJECTED_SEED
            draft.wantsAction && !actionsArmed -> NoteStatus.HELD
            draft.kind == NoteKind.ACT && !actionsArmed -> NoteStatus.HELD
            else -> if (draft.wantsAction) NoteStatus.PENDING else NoteStatus.PENDING
        }
        val stored = draft.copy(
            status = status,
            direction = BotNote.Direction.IN,
            text = draft.text.take(BotNote.MAX_CHARS),
        )
        upsert(stored)
        return stored
    }

    private fun load(): List<BotNote> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    add(BotNote.fromPlainJson(arr.getJSONObject(i), BotNote.Direction.IN))
                }
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        _notes.value.forEach { arr.put(it.toPlainJson()) }
        file.writeText(arr.toString())
    }
}
