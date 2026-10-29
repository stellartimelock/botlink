package org.stellartimelock.botlink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.stellartimelock.botlink.flipsend.FlipSendDetector
import org.stellartimelock.botlink.pairing.PairingStore
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import org.stellartimelock.botlink.protocol.BotNote
import org.stellartimelock.botlink.protocol.NoteKind
import org.stellartimelock.botlink.protocol.NoteStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BotLinkRoot(viewModel: BotLinkViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snack.showSnackbar(msg)
        viewModel.consumeToast()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (!state.flipSendInstalled) {
            InstallFlipSendFirst(onRecheck = viewModel::refreshFlipSend)
        } else {
            Home(state = state, viewModel = viewModel)
        }
        SnackbarHost(
            hostState = snack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun InstallFlipSendFirst(onRecheck: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Bot Link", style = MaterialTheme.typography.titleLarge)
        Text(
            "Install FlipSend first.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Bot Link is the Grok Bot / suite-bot layer. It sits on FlipSend’s pairing and relay. " +
                "It is not FlipSend, and it does not move files.",
            style = MaterialTheme.typography.bodyLarge,
        )
        CardBlock {
            Text("What to do", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Install FlipSend (package org.stellartimelock.stellarsend) the same way you already put it on this phone — sideload the APK you use for rebuilds.\n" +
                    "2. Open FlipSend once and pair the phone to the PC if you have not already.\n" +
                    "3. Come back here and tap Check again.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Button(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) {
            Text("Check again")
        }
        OutlinedButton(
            onClick = { openPlayOrStore(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Look for FlipSend in Play Store")
        }
        Text(
            "Play Store may not have it. If the store page is missing, sideload FlipSend. Bot Link will stay here until that package is present.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Home(state: UiState, viewModel: BotLinkViewModel) {
    val context = LocalContext.current
    var place by rememberSaveable { mutableStateOf("home") }
    var noteReturn by rememberSaveable { mutableStateOf("home") }
    var enlarge by rememberSaveable { mutableStateOf(false) }

    fun goHome() {
        viewModel.select(null)
        viewModel.setTab(UiState.Tab.Inbox)
        enlarge = false
        place = "home"
    }

    LaunchedEffect(state.openAiRoomAt) {
        if (state.openAiRoomAt > 0L) {
            goHome()
        }
    }

    fun openNote(id: String, from: String) {
        noteReturn = from
        viewModel.select(id)
        place = "note"
    }

    fun openPage(id: String) {
        noteReturn = "home"
        viewModel.select(id)
        place = "page"
    }

    when (place) {
        "page" -> {
            val note = state.selected
            if (note == null || note.html.isNullOrBlank()) {
                LaunchedEffect(Unit) { place = "home" }
            } else {
                TranscriptPage(
                    html = transcriptHtml(note.text, note.html),
                    onBack = {
                        viewModel.select(null)
                        place = "home"
                    },
                )
            }
        }
        "note" -> {
            val note = state.selected
            if (note == null) {
                LaunchedEffect(Unit) { place = noteReturn }
            } else {
                NoteDetail(
                    note = note,
                    armed = state.actionsArmed,
                    backLabel = if (noteReturn == "inbox") "Back to inbox" else "Back",
                    onBack = {
                        viewModel.select(null)
                        place = noteReturn
                    },
                    onDecide = viewModel::approve,
                    onSign = { sep7 -> viewModel.signInWallet(context, sep7) },
                )
            }
        }
        "inbox" -> Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Review & approve", onBack = ::goHome)
            InboxPane(state, onOpen = { openNote(it, "inbox") })
        }
        "outbox" -> Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Outbox", onBack = ::goHome)
            OutboxPane(state, viewModel)
        }
        "settings" -> Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Settings", onBack = ::goHome)
            StatusPane(
                state = state,
                viewModel = viewModel,
                context = context,
                onOutbox = {
                    viewModel.setTab(UiState.Tab.Outbox)
                    place = "outbox"
                },
                onOpenNote = { openNote(it, "settings") },
            )
        }
        else -> {
            ApproveFirstHome(
                state = state,
                onOpenBlurb = ::openPage,
                onArmed = viewModel::setArmed,
                onEnlarge = { enlarge = true },
                onDraftChange = viewModel::setDraft,
                onSendDraft = viewModel::sendDraft,
                onSettings = {
                    viewModel.setTab(UiState.Tab.Status)
                    place = "settings"
                },
            )
            if (enlarge) {
                EnlargePairingDialog(
                    code = state.pairing?.displayCode,
                    onCopy = {
                        val code = state.pairing?.displayCode ?: return@EnlargePairingDialog
                        copyPairingCode(context, code, viewModel)
                    },
                    onDismiss = { enlarge = false },
                )
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("Back", fontSize = 16.sp)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ColumnScope.InboxPane(state: UiState, onOpen: (String) -> Unit) {
    if (state.inbox.isEmpty()) {
        EmptyPane(
            title = "Inbox is empty",
            body = "When Grok Bot or another suite bot sends a short note from the PC companion, it lands here. Anything that would act for you waits for Approve.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f, fill = true),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.inbox, key = { it.id }) { note ->
            NoteCard(note) { onOpen(note.id) }
        }
    }
}

@Composable
private fun ColumnScope.OutboxPane(state: UiState, viewModel: BotLinkViewModel) {
    Column(
        Modifier
            .weight(1f, fill = true)
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { if (it.length <= BotNote.MAX_CHARS) viewModel.setDraft(it) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            label = { Text("Short note to the PC") },
            placeholder = { Text("No files. No seeds. A sentence is enough.") },
            minLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::sendDraft,
            enabled = state.draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send to PC companion") }
        Spacer(Modifier.height(16.dp))
        if (state.outbox.isEmpty()) {
            Text(
                "Nothing in the outbox yet. Approvals you tap and notes you type show up here after they go on the pipe.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(state.outbox, key = { it.id }) { note ->
                    NoteCard(note, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.StatusPane(
    state: UiState,
    viewModel: BotLinkViewModel,
    context: Context,
    onOutbox: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val approvals = approvalEntries(state.notes)
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (approvals.isNotEmpty()) {
            CardBlock {
                Text("Approvals", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                approvals.forEach { note ->
                    NoteCard(note) { onOpenNote(note.id) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow bot actions", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "Off until you turn it on. Approve is still required.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.actionsArmed, onCheckedChange = viewModel::setArmed)
            }
        }
        CardBlock {
            Text("Relay", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                relayCaption(state.link),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Bot Link is free. Same Wi-Fi is direct and unlimited.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CardBlock {
            Text("Agent spend", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                spendCaption(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CardBlock {
            Text("Pairing code", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                state.pairing?.displayCode ?: "—",
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "One step on the PC: run companion/BotLink.bat (Windows) or companion/BotLink.sh.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row {
                TextButton(onClick = {
                    val code = state.pairing?.displayCode ?: return@TextButton
                    copyPairingCode(context, code, viewModel)
                }) { Text("Copy code") }
                TextButton(onClick = viewModel::rotatePairing) { Text("New code") }
            }
        }
        CardBlock {
            Text("Pipe", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("LAN  ${state.link.lanDetail}", style = MaterialTheme.typography.bodyLarge)
            Text("Relay  ${state.link.relayDetail}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Phone IP  ${state.link.ipv4 ?: "not on Wi-Fi"}:${PairingStore.LAN_PORT}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Room  ${state.pairing?.roomId?.take(16) ?: "—"}…",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "FlipSend pipe export: ${state.pipeStatus.settingsLine()}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.link.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        OutlinedButton(onClick = viewModel::openFlipSend, modifier = Modifier.fillMaxWidth()) {
            Text("Open FlipSend")
        }
        OutlinedButton(onClick = onOutbox, modifier = Modifier.fillMaxWidth()) {
            Text("Short note to the PC")
        }
        Text(
            "No telemetry. No ads. Ciphertext only on the public relay. Seeds are refused.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TranscriptPage(html: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
            Text("Back", fontSize = 16.sp)
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(0xFF0B1020.toInt())
                }
            },
            update = { web ->
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            },
        )
    }
}

@Composable
private fun NoteDetail(
    note: BotNote,
    armed: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onDecide: (String, Boolean) -> Unit,
    onSign: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(backLabel, fontSize = 16.sp) }
        Text(note.from, style = MaterialTheme.typography.titleMedium)
        Text(statusLabel(note), color = chipColor(note.status), fontSize = 16.sp)
        Text(whenStamp(note.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        CardBlock {
            Text(note.text.ifBlank { "(empty note)" }, style = MaterialTheme.typography.bodyLarge)
            if (!note.sep7.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (note.paid) "Signed in StellarTimeLock. Bot Link never held the key."
                    else "x402 · StellarTimeLock signs via SEP-7. Bot Link never holds keys.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (note.status == NoteStatus.REJECTED_SEED) {
            Text(
                "Blocked. This channel does not carry seeds.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (note.status == NoteStatus.HELD) {
            Text(
                "Held because Allow bot actions is off. Turn it on in Status, then Approve if you want this to go through.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val canDecide = note.status == NoteStatus.PENDING || note.status == NoteStatus.HELD
        if (canDecide && note.wantsAction) {
            Button(
                onClick = {
                    onDecide(note.id, true)
                    val uri = note.sep7
                    if (!uri.isNullOrBlank()) onSign(uri)
                },
                enabled = armed,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (note.sep7.isNullOrBlank()) "Approve" else "Approve and sign") }
            OutlinedButton(onClick = { onDecide(note.id, false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Deny")
            }
            if (!armed) {
                Text(
                    "Approve stays locked until you turn on Allow bot actions.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (note.status == NoteStatus.APPROVED && !note.sep7.isNullOrBlank() && !note.paid) {
            Button(
                onClick = { onSign(note.sep7) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in StellarTimeLock") }
        } else if (canDecide) {
            Button(onClick = { onDecide(note.id, true) }, modifier = Modifier.fillMaxWidth()) {
                Text("Acknowledge")
            }
            OutlinedButton(onClick = { onDecide(note.id, false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun NoteCard(note: BotNote, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) {
        Row {
            Text(note.from, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(statusLabel(note), color = chipColor(note.status), fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            note.text.ifBlank { "(empty note)" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 4,
        )
        Spacer(Modifier.height(6.dp))
        Text(whenStamp(note.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
    }
}

@Composable
private fun EmptyPane(title: String, body: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun chipColor(status: NoteStatus) = when (status) {
    NoteStatus.PENDING -> MaterialTheme.colorScheme.primary
    NoteStatus.HELD -> MaterialTheme.colorScheme.secondary
    NoteStatus.APPROVED, NoteStatus.SENT -> MaterialTheme.colorScheme.secondary
    NoteStatus.DENIED, NoteStatus.REJECTED_SEED -> MaterialTheme.colorScheme.error
}

private fun statusLabel(note: BotNote): String = when (note.status) {
    NoteStatus.PENDING -> if (note.wantsAction) "Needs Approve" else "New"
    NoteStatus.HELD -> "Held"
    NoteStatus.APPROVED -> "Approved"
    NoteStatus.DENIED -> "Denied"
    NoteStatus.SENT -> if (note.kind == NoteKind.DECISION) "Decision sent" else "Sent"
    NoteStatus.REJECTED_SEED -> "Blocked"
}

private fun whenStamp(epoch: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(epoch))

private fun copyPairingCode(context: Context, code: String, viewModel: BotLinkViewModel) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText("Bot Link pairing", code))
    viewModel.notice("Pairing code copied.")
}

private fun openPlayOrStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${FlipSendDetector.FLIPSEND_PACKAGE}"))
    market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: Exception) {
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${FlipSendDetector.FLIPSEND_PACKAGE}"),
        )
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(web)
    }
}
