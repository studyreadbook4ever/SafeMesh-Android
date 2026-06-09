package com.safemesh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safemesh.android.proto.Fingerprint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================ shared bits

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(tsMs: Long): String = timeFmt.format(Date(tsMs))

private fun deliveryLabel(msg: ChatMessage): String {
    return when (msg.delivery) {
        DeliveryState.Pending -> "pending"
        DeliveryState.Sent -> "sent"
        DeliveryState.Acked -> {
            if (msg.convId == BROADCAST) return "sent"
            val n = msg.ackedBy.size
            if (n > 1) "ACK x$n" else "ACK"
        }
        DeliveryState.Failed -> "failed"
        DeliveryState.Received -> ""
    }
}

/**
 * nick + a distinct monospace background chip showing the key-derived #tag.
 * The tag is ALWAYS rendered from public_id (Fingerprint.short), never from
 * the nick — that is the impersonation-resistance UI invariant.
 */
@Composable
fun NameWithFingerprint(
    nick: String,
    publicId: Int,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = nick.ifBlank { "anon" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = Fingerprint.short(publicId),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

/** Circular avatar with the first letter of the nick (Discord-style). */
@Composable
private fun Avatar(nick: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = nick.trim().take(1).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * KakaoTalk-style chat bubble.
 *   - mine  : right-aligned, primary (blue), no name header.
 *   - others: left-aligned, gray, nick#tag header + time.
 */
@Composable
fun MessageBubble(msg: ChatMessage, selfPublicId: Int) {
    val mine = msg.isMine
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (!mine) {
            NameWithFingerprint(
                nick = msg.fromNick,
                publicId = msg.fromPid,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (mine) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    val status = deliveryLabel(msg)
                    if (status.isNotEmpty()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.delivery == DeliveryState.Acked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formatTime(msg.tsMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Surface(
                color = if (mine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (!mine) {
                Text(
                    text = formatTime(msg.tsMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/** Bottom message composer: text field + send icon. */
@Composable
private fun MessageComposer(
    placeholder: String,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty()) { onSend(t); input = "" }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AckNoticeRow(notice: AckNotice) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "${notice.byNick.ifBlank { "anon" }}님이 위 메시지를 받았습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    selfPublicId: Int,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(messages) { msg ->
            MessageBubble(msg, selfPublicId)
            if (msg.convId == BROADCAST && msg.isMine) {
                msg.ackNotices.forEach { notice -> AckNoticeRow(notice) }
            }
        }
    }
}

// ============================================================ Broadcast (home)

@Composable
fun HomeScreen(
    state: MeshUiState,
    onSendBroadcast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Info banner.
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Public broadcast channel. Everyone nearby on the mesh "
                    + "receives this. Internet not required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
        MessageList(
            messages = state.broadcast,
            selfPublicId = state.selfPublicId,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.weight(1f)
        )
        MessageComposer(
            placeholder = "Message broadcast...",
            onSend = onSendBroadcast
        )
    }
}

// ============================================================ DM

@Composable
fun DmScreen(
    peerId: Int,
    state: MeshUiState,
    onSendDm: (Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages = state.dms[peerId].orEmpty()
    Column(modifier = modifier.fillMaxSize()) {
        MessageList(
            messages = messages,
            selfPublicId = state.selfPublicId,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.weight(1f)
        )
        MessageComposer(
            placeholder = "Message...",
            onSend = { text -> onSendDm(peerId, text) }
        )
    }
}

// ============================================================ Contacts drawer

/**
 * Side sheet: "Broadcast" channel entry on top, then a DIRECT MESSAGES list.
 * The DM list is the union of known peers and any peer we already have a
 * conversation with (so a thread stays reachable even after the peer ages out).
 */
@Composable
fun ContactsDrawer(
    state: MeshUiState,
    onOpenBroadcast: () -> Unit,
    onOpenDm: (Int) -> Unit
) {
    // Build the DM target list: known peers ∪ conversation partners, deduped.
    data class Contact(val pid: Int, val nick: String)
    val byPid = LinkedHashMap<Int, Contact>()
    state.peers.forEach { p ->
        if (p.publicId != state.selfPublicId) byPid[p.publicId] = Contact(p.publicId, p.nick)
    }
    state.dms.keys.forEach { pid ->
        if (pid != state.selfPublicId && pid != BROADCAST && !byPid.containsKey(pid)) {
            val nick = state.dms[pid]?.firstOrNull { !it.isMine }?.fromNick
                ?: "%08x".format(pid)
            byPid[pid] = Contact(pid, nick)
        }
    }
    val contacts = byPid.values.toList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp)
    ) {
        Text(
            text = "MeshTalk",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Broadcast channel entry.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenBroadcast() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Broadcast",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Public messages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            text = "DIRECT MESSAGES",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (contacts.isEmpty()) {
            Text(
                text = "No peers yet — waiting for the mesh…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(contacts) { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDm(c.pid) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(c.nick)
                        Spacer(Modifier.width(12.dp))
                        NameWithFingerprint(nick = c.nick, publicId = c.pid)
                    }
                }
            }
        }
    }
}

/** Title block used in the DM top app bar: name + #tag. */
@Composable
fun DmTitle(peerId: Int, state: MeshUiState) {
    val nick = state.dms[peerId]?.firstOrNull { !it.isMine }?.fromNick
        ?: state.peers.firstOrNull { it.publicId == peerId }?.nick
        ?: "%08x".format(peerId)
    NameWithFingerprint(nick = nick, publicId = peerId)
}
