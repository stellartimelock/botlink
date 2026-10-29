package org.stellartimelock.botlink.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.stellartimelock.botlink.BotLinkApp
import org.stellartimelock.botlink.flipsend.FlipSendPipe
import org.stellartimelock.botlink.flipsend.PipeExport
import org.stellartimelock.botlink.net.LinkStatus
import org.stellartimelock.botlink.pairing.Pairing
import org.stellartimelock.botlink.protocol.BotNote
import org.stellartimelock.botlink.protocol.NoteStatus

data class UiState(
    val flipSendInstalled: Boolean = false,
    val pipeStatus: FlipSendPipe.Status = FlipSendPipe.Status.Missing,
    val pairing: Pairing? = null,
    val actionsArmed: Boolean = false,
    val notes: List<BotNote> = emptyList(),
    val link: LinkStatus = LinkStatus(),
    val selectedId: String? = null,
    val draft: String = "",
    val toast: String? = null,
    val tab: Tab = Tab.Inbox,
    val openAiRoomAt: Long = 0L,
) {
    enum class Tab { Inbox, Outbox, Status }

    val selected: BotNote? get() = notes.find { it.id == selectedId }
    val inbox: List<BotNote> get() = notes.filter { it.direction == BotNote.Direction.IN }
    val outbox: List<BotNote> get() = notes.filter { it.direction == BotNote.Direction.OUT }
    val pending: Int get() = inbox.count { it.status == NoteStatus.PENDING || it.status == NoteStatus.HELD }
}

class BotLinkViewModel(private val app: BotLinkApp) : ViewModel() {
    private val flipInstalled = MutableStateFlow(app.flipSend.isInstalled())
    private val pipeStatus = MutableStateFlow(app.flipSend.pipeStatus())
    private val pairing = MutableStateFlow(app.pairing.current())
    private val armed = MutableStateFlow(app.pairing.actionsArmed())
    private val selectedId = MutableStateFlow<String?>(null)
    private val draft = MutableStateFlow("")
    private val toast = MutableStateFlow<String?>(null)
    private val tab = MutableStateFlow(UiState.Tab.Inbox)
    private val openAiRoomAt = MutableStateFlow(0L)

    val ui: StateFlow<UiState> = combine(
        combine(flipInstalled, pipeStatus, pairing, armed) { installed, export, pair, isArmed ->
            Quad(installed, export, pair, isArmed)
        },
        combine(app.notes.notes, app.hub.status, selectedId, draft) { notes, link, sel, text ->
            Quad(notes, link, sel, text)
        },
        combine(toast, tab, openAiRoomAt) { t, tb, roomAt -> Triple(t, tb, roomAt) },
    ) { a, b, c ->
        UiState(
            flipSendInstalled = a.a,
            pipeStatus = a.b,
            pairing = a.c,
            actionsArmed = a.d,
            notes = b.a,
            link = b.b,
            selectedId = b.c,
            draft = b.d,
            toast = c.first,
            tab = c.second,
            openAiRoomAt = c.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(4_000), UiState(pairing = pairing.value))

    fun refreshFlipSend() {
        flipInstalled.value = app.flipSend.isInstalled()
        val next = app.flipSend.pipeStatus()
        if (pipeStatus.value == FlipSendPipe.Status.Missing ||
            pipeStatus.value == FlipSendPipe.Status.NotExported ||
            pipeStatus.value == FlipSendPipe.Status.Advertised
        ) {
            pipeStatus.value = next
        } else if (next == FlipSendPipe.Status.Missing || next == FlipSendPipe.Status.NotExported) {
            pipeStatus.value = next
        }
    }

    fun pipeQueryIntent(): android.content.Intent? {
        if (app.flipSend.pipeExport() != PipeExport.Advertised) return null
        return FlipSendPipe.queryIntent()
    }

    fun onPipeResult(status: FlipSendPipe.Status) {
        pipeStatus.value = status
    }

    fun openFlipSend() {
        val intent = app.flipSend.launchIntent()
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } else {
            toast.value = "FlipSend is installed but has no launcher activity."
        }
    }

    fun setArmed(value: Boolean) {
        app.pairing.setActionsArmed(value)
        armed.value = value
        toast.value = if (value) {
            "Bot actions on. Approve is still required for anything that acts for you."
        } else {
            "Bot actions off. Incoming act notes will sit in Held."
        }
    }

    fun rotatePairing() {
        pairing.value = app.pairing.rotate()
        armed.value = false
        toast.value = "New pairing code. Type this into the PC companion."
    }

    fun select(id: String?) {
        selectedId.value = id
    }

    fun setTab(next: UiState.Tab) {
        tab.value = next
    }

    fun setDraft(text: String) {
        draft.value = text
    }

    /** Open the AI room and fill the composer. Does not send. */
    fun openAiRoom(text: String?) {
        val incoming = text.orEmpty()
        if (incoming.isNotEmpty()) {
            draft.value = incoming.take(BotNote.MAX_CHARS)
        }
        selectedId.value = null
        tab.value = UiState.Tab.Inbox
        openAiRoomAt.value = openAiRoomAt.value + 1L
    }

    fun sendDraft() {
        val note = app.hub.sendNote(draft.value)
        if (note != null && note.status != NoteStatus.REJECTED_SEED) {
            draft.value = ""
            toast.value = "Sent to outbox."
            tab.value = UiState.Tab.Outbox
        } else if (note?.status == NoteStatus.REJECTED_SEED) {
            toast.value = "Blocked. No seeds on this channel."
        }
    }

    fun approve(id: String, approved: Boolean) {
        val result = app.hub.approve(id, approved)
        toast.value = when {
            result == null && !app.pairing.actionsArmed() && approved ->
                "Turn on Allow bot actions, then tap Approve."
            result == null -> "Could not update that note."
            approved && !result.sep7.isNullOrBlank() -> {
                selectedId.value = id
                "Approved. Open StellarTimeLock to sign. Bot Link never holds keys."
            }
            approved -> "Approved. Decision sent to the PC companion."
            else -> "Denied. The bot should not act."
        }
    }

    fun signInWallet(context: android.content.Context, sep7: String) {
        toast.value = org.stellartimelock.botlink.wallet.WalletSign.launch(context, sep7)
    }

    fun onSignedXdr(xdr: String) {
        val id = selectedId.value ?: ui.value.notes.firstOrNull {
            !it.sep7.isNullOrBlank() && it.signedXdr.isNullOrBlank() &&
                (it.status == NoteStatus.APPROVED || it.status == NoteStatus.PENDING)
        }?.id
        if (id == null) {
            toast.value = "Signed, but no spend note is waiting."
            return
        }
        val paid = app.hub.attachPayment(id, xdr)
        toast.value = if (paid != null) {
            "Signed in the wallet. Payment proof sent to the PC companion."
        } else {
            "Could not attach that signature."
        }
    }

    fun notice(msg: String) {
        toast.value = msg
    }

    fun consumeToast() {
        toast.value = null
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BotLinkViewModel(application as BotLinkApp) as T
                }
            }
    }
}
