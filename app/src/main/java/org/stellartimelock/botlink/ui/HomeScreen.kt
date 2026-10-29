package org.stellartimelock.botlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import org.stellartimelock.botlink.protocol.BotNote
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import org.stellartimelock.botlink.ui.theme.Cream
import org.stellartimelock.botlink.ui.theme.DeepSpace
import org.stellartimelock.botlink.ui.theme.Gold
import org.stellartimelock.botlink.ui.theme.GoldInk
import org.stellartimelock.botlink.ui.theme.Muted
import org.stellartimelock.botlink.ui.theme.Stamp
import org.stellartimelock.botlink.ui.theme.Teal
import org.stellartimelock.botlink.ui.theme.Violet

private val CardShape = RoundedCornerShape(18.dp)
private val RowShape = RoundedCornerShape(12.dp)
private val TwinShape = RoundedCornerShape(14.dp)
private val PairShape = RoundedCornerShape(16.dp)

@Composable
internal fun ApproveFirstHome(
    state: UiState,
    onOpenBlurb: (String) -> Unit,
    onArmed: (Boolean) -> Unit,
    onEnlarge: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    onSettings: () -> Unit,
) {
    val blurbs = roomBlurbs(state.notes)
    val code = state.pairing?.displayCode
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Header(linked = state.flipSendInstalled)
        AiRoomCard(
            blurbs = blurbs,
            onOpen = onOpenBlurb,
            draft = state.draft,
            onDraftChange = onDraftChange,
            onSendDraft = onSendDraft,
            showComposer = state.openAiRoomAt > 0L || state.draft.isNotEmpty(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FlipSendCard(
                pipe = pipeLine(state.link),
                modifier = Modifier.weight(1f),
            )
            AllowCard(
                armed = state.actionsArmed,
                onArmed = onArmed,
                modifier = Modifier.weight(1f),
            )
        }
        PairCard(code = code, onEnlarge = onEnlarge)
        Text(
            text = settingsLabel(state.pending),
            color = Muted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp)
                .clickable(role = Role.Button, onClick = onSettings),
        )
    }
}

@Composable
private fun Header(linked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ShieldGlyph(Modifier.size(20.dp))
            }
            Column {
                Text(
                    text = "Bot Link",
                    color = Cream,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    text = "Room · Allow · Pair",
                    color = Muted,
                    fontSize = 16.sp,
                )
            }
        }
        FlipSendPill(linked = linked)
    }
}

@Composable
private fun FlipSendPill(linked: Boolean) {
    val fg = if (linked) Teal else Muted
    val label = if (linked) "FlipSend" else "No FlipSend"
    Row(
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(CircleShape)
            .background(fg.copy(alpha = 0.10f))
            .border(1.dp, fg.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = if (linked) "FlipSend linked" else "FlipSend not installed"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .shadow(6.dp, CircleShape, clip = false, ambientColor = fg, spotColor = fg)
                .background(fg, CircleShape),
        )
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AiRoomCard(
    blurbs: List<org.stellartimelock.botlink.protocol.BotNote>,
    onOpen: (String) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendDraft: () -> Unit,
    showComposer: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Gold.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.03f),
                        Violet.copy(alpha = 0.10f),
                    ),
                ),
                CardShape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "AI room",
                color = Cream,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Beta",
                color = Violet,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .border(1.dp, Violet.copy(alpha = 0.45f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = "AI room beta" },
            )
        }
        if (blurbs.isEmpty()) {
            Text(
                text = "Agent summaries land here, each with a link to the full HTML transcript.",
                color = Muted,
                fontSize = 16.sp,
            )
        } else {
            blurbs.take(6).forEach { note ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RowShape)
                        .background(DeepSpace.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RowShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = note.text,
                            color = Cream,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = note.from + " · " + agoLabel(note.createdAt),
                            color = Stamp,
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        text = "Open",
                        color = Gold,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = { onOpen(note.id) })
                            .semantics { contentDescription = "Open HTML transcript" },
                    )
                }
            }
        }
        if (showComposer) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= BotNote.MAX_CHARS) onDraftChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "AI room composer" },
                textStyle = androidx.compose.ui.text.TextStyle(color = Cream, fontSize = 16.sp),
                label = { Text("Composer") },
                placeholder = { Text("Shared text stays here until you send.") },
                minLines = 3,
            )
            Button(
                onClick = onSendDraft,
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send to PC companion") }
        }
    }
}

@Composable
private fun FlipSendCard(pipe: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 116.dp)
            .background(Teal.copy(alpha = 0.06f), TwinShape)
            .border(1.dp, Teal.copy(alpha = 0.28f), TwinShape)
            .padding(12.dp)
            .semantics { contentDescription = "FlipSend linked. $pipe" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "FLIPSEND",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = "Linked",
            color = Teal,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = pipe, color = Muted, fontSize = 16.sp)
    }
}

@Composable
private fun AllowCard(
    armed: Boolean,
    onArmed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 116.dp)
            .background(Color.White.copy(alpha = 0.035f), TwinShape)
            .border(1.dp, Color.White.copy(alpha = 0.09f), TwinShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "BOT ACTIONS",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = "Allow",
            color = Cream,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (armed) "ON" else "OFF",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
            AllowToggle(armed = armed, onArmed = onArmed)
        }
    }
}

@Composable
private fun AllowToggle(armed: Boolean, onArmed: (Boolean) -> Unit) {
    val knob = if (armed) GoldInk else Color(0xFFC9C0B2)
    val track = if (armed) Gold else Color.White.copy(alpha = 0.08f)
    val edge = if (armed) Gold else Color.White.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, edge, CircleShape)
            .clickable(role = Role.Switch, onClick = { onArmed(!armed) })
            .semantics {
                contentDescription = "Allow bot actions"
            }
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .align(if (armed) Alignment.CenterEnd else Alignment.CenterStart)
                .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.35f))
                .background(knob, CircleShape),
        )
    }
}

@Composable
private fun PairCard(code: String?, onEnlarge: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                12.dp,
                PairShape,
                ambientColor = Gold.copy(alpha = 0.08f),
                spotColor = Gold.copy(alpha = 0.10f),
            )
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Gold.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.03f),
                        Teal.copy(alpha = 0.05f),
                    ),
                ),
                PairShape,
            )
            .border(1.dp, Gold.copy(alpha = 0.28f), PairShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Cream)
                .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(6.dp)
                .semantics { contentDescription = "Pairing QR code" },
            contentAlignment = Alignment.Center,
        ) {
            if (code != null) {
                PairingQr(payload = code, modifier = Modifier.fillMaxSize())
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "PAIR · PC COMPANION",
                color = Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
            )
            Text(
                text = "Scan on desktop",
                color = Cream,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = code ?: "—",
                color = Gold,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                lineHeight = 18.sp,
            )
            Text(
                text = "Link Bot Link to your PC companion over FlipSend’s pipe.",
                color = Muted,
                fontSize = 16.sp,
                lineHeight = 20.sp,
            )
            OutlinedButton(
                onClick = onEnlarge,
                enabled = code != null,
                modifier = Modifier.padding(top = 4.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, Teal.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Teal.copy(alpha = 0.08f),
                    contentColor = Teal,
                ),
            ) {
                Text("Enlarge QR", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun EnlargePairingDialog(
    code: String?,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF121018))
                .border(1.dp, Gold.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Pair · PC companion",
                color = Muted,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Cream)
                    .padding(12.dp)
                    .semantics { contentDescription = "Enlarged pairing QR code" },
            ) {
                if (code != null) {
                    PairingQr(payload = code, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = code ?: "—",
                color = Gold,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onCopy, enabled = code != null) {
                Text("Copy code", fontSize = 16.sp)
            }
            TextButton(onClick = onDismiss) {
                Text("Close", color = Muted, fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun PairingQr(payload: String, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { encodeQr(payload) }
    Canvas(modifier) {
        val code = matrix ?: return@Canvas
        val cols = code.width
        val rows = code.height
        if (cols == 0 || rows == 0) return@Canvas
        val cell = size.minDimension / cols.toFloat()
        val x0 = (size.width - cell * cols) / 2f
        val y0 = (size.height - cell * rows) / 2f
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (code.get(x, y)) {
                    drawRect(
                        color = DeepSpace,
                        topLeft = androidx.compose.ui.geometry.Offset(x0 + x * cell, y0 + y * cell),
                        size = androidx.compose.ui.geometry.Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}

private fun encodeQr(text: String): com.google.zxing.common.BitMatrix? {
    if (text.isBlank()) return null
    return try {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun ShieldGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        fun p(v: Float) = v * s
        val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shield = Path().apply {
            moveTo(p(12f), p(3f))
            lineTo(p(19f), p(6f))
            lineTo(p(19f), p(11f))
            cubicTo(p(19f), p(15.5f), p(16f), p(18.5f), p(12f), p(20f))
            cubicTo(p(8f), p(18.5f), p(5f), p(15.5f), p(5f), p(11f))
            lineTo(p(5f), p(6f))
            close()
        }
        drawPath(shield, Gold, style = stroke)
        val check = Path().apply {
            moveTo(p(9f), p(12f))
            lineTo(p(11f), p(14f))
            lineTo(p(15f), p(10f))
        }
        drawPath(check, Gold, style = stroke)
    }
}
