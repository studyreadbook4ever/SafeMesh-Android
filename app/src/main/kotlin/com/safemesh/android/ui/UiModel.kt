package com.safemesh.android.ui

import com.safemesh.android.proto.IdempotencyKey
import com.safemesh.android.proto.SafeMeshNode

/**
 * UI-facing conversation model. The MeshService owns the source of truth and
 * publishes immutable snapshots of this state through a StateFlow; the Compose
 * layer only reads it.
 */

/** Conversation id for the public broadcast channel. Any other id is a peer public_id. */
const val BROADCAST: Int = 0

/**
 * One rendered chat line.
 *
 * @param convId   BROADCAST (=0) for the public channel, otherwise the remote public_id.
 * @param fromPid  sender's key-derived public_id (drives the fingerprint tag).
 * @param fromNick sender's nickname (display only — never trusted for identity).
 * @param text     message body.
 * @param isMine   true when this device authored the message (right-aligned, blue).
 * @param tsMs     wall-clock timestamp for the "HH:mm" header.
 */
data class ChatMessage(
    val convId: Int,
    val fromPid: Int,
    val fromNick: String,
    val text: String,
    val isMine: Boolean,
    val tsMs: Long,
    val key: IdempotencyKey? = null,
    val expiresAtMs: Long = tsMs + 60_000,
    val delivery: DeliveryState = if (isMine) DeliveryState.Pending else DeliveryState.Received,
    val ackedBy: Set<Int> = emptySet(),
    val ackNotices: List<AckNotice> = emptyList()
)

data class AckNotice(
    val byPid: Int,
    val byNick: String,
    val tsMs: Long
)

enum class DeliveryState {
    Received,
    Pending,
    Sent,
    Acked,
    Failed
}

/**
 * Immutable snapshot of everything the UI renders. Recreated on every mesh
 * event / outbound send while holding the node lock.
 */
data class MeshUiState(
    val selfNick: String,
    val selfPublicId: Int,
    val peers: List<SafeMeshNode.KnownPeer>,
    val broadcast: List<ChatMessage>,
    val dms: Map<Int, List<ChatMessage>>
) {
    companion object {
        val EMPTY = MeshUiState(
            selfNick = "",
            selfPublicId = 0,
            peers = emptyList(),
            broadcast = emptyList(),
            dms = emptyMap()
        )
    }
}

/** State-based navigation target (no navigation-compose dependency). */
sealed class Screen {
    object Broadcast : Screen()
    data class Dm(val peerId: Int) : Screen()
}
