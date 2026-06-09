package com.safemesh.android.proto

import java.io.ByteArrayOutputStream

/**
 * Protocol state machine — a trimmed mirror of Rust `node.rs`. Holds:
 *   - active link table (link_id -> rx_hint + reassembler + peer_public_id)
 *   - seen idempotency keys (dedup with TTL)
 *   - known peers (learned from control gossip)
 *   - short-lived ACK receipts retried out-of-band from user message retry
 *
 * Wires:
 *   - sendFrameOnLink(linkId, frame) is supplied by the BLE layer.
 *   - onEvent(MeshEvent) surfaces user-visible things to the UI.
 *
 * Locking: this class is NOT thread-safe; callers (MeshService) serialize
 * via a single coroutine.
 */
class SafeMeshNode(
    var nick: String,
    val identity: Identity,
    val flags: Int = Constants.LOCAL_FLAG_AES_GCM or Constants.LOCAL_FLAG_PRIVATE_RELAY_ALLOWED,
    val rx: Int = Constants.DEFAULT_RX,
    private val io: NodeIo
) {
    interface NodeIo {
        fun sendFrameOnLink(linkId: String, frame: ByteArray): Boolean
        fun sendFramesOnLink(linkId: String, frames: List<ByteArray>, priority: Boolean = false): Boolean {
            var accepted = true
            frames.forEach { frame ->
                accepted = sendFrameOnLink(linkId, frame) && accepted
            }
            return accepted
        }
        /** Snapshot of all currently-up link IDs (used for broadcast forward). */
        fun activeLinkIds(): List<String>
        fun emit(event: MeshEvent)
    }

    sealed class MeshEvent {
        data class Broadcast(val fromPid: Int, val fromNick: String, val hops: Int, val text: String, val key: IdempotencyKey) : MeshEvent()
        data class PeerSeen(val pid: Int, val nick: String, val hops: Int) : MeshEvent()
        data class Disconnect(val pid: Int, val nick: String, val reason: String) : MeshEvent()
        data class AckReceived(val acked: IdempotencyKey, val byPid: Int) : MeshEvent()
        /** Decrypted private DM addressed to us (v3 KEX + AEAD). */
        data class PrivateMessage(val fromPid: Int, val fromNick: String, val text: String) : MeshEvent()
        data class PrivateSent(val targetPid: Int, val text: String, val key: IdempotencyKey) : MeshEvent()
        data class PrivateDropped(val fromPid: Int) : MeshEvent() // undecryptable / not for us
    }

    val publicId: Int get() = identity.publicId

    private data class LinkState(
        var rxHint: Int,
        var peerPublicId: Int?,
        val reassembler: Reassembler = Reassembler(),
        var lastSeenMs: Long = System.currentTimeMillis()
    )

    data class KnownPeer(
        val publicId: Int,
        var nick: String,
        var flags: Int,
        var rx: Int,
        var hops: Int,
        var lastSeenMs: Long,
        var viaLink: String?
    )

    /** Established DM session (root key + negotiated AEAD), keyed by peer public_id. */
    data class DmSession(
        val peerPublicId: Int,
        val peerNick: String,
        val peerIdentityPub: ByteArray,
        val rootKey: ByteArray,
        val alg: Byte
    )

    private data class DelayedDmText(
        val text: String,
        val createdMs: Long
    )

    /** In-flight KEX we initiated, awaiting KEX_RESP. */
    private data class PendingKex(
        val targetPublicId: Int,
        val ephPriv: ByteArray,
        val ephPub: ByteArray,
        val initPacket: MeshPacket,
        val kexKey: IdempotencyKey,
        val createdMs: Long,
        var retryUntilMs: Long,
        var expiresMs: Long
    )

    private data class RetryPacket(
        val packet: MeshPacket,
        val retryUntilMs: Long,
        val expiresMs: Long,
        var nextRetryMs: Long,
        var attempts: Int = 0
    )

    private data class FanoutPacket(
        val packet: MeshPacket,
        val expiresMs: Long
    )

    private data class UserOutboxPacket(
        val packet: MeshPacket,
        val targetPid: Int?,
        val retryWindowMs: Long,
        val retryUntilMs: Long,
        val expiresMs: Long
    )

    private data class AckOutboxKey(
        val acked: IdempotencyKey,
        val ackingPid: Int
    )

    private data class PendingPublicAck(
        val acked: IdempotencyKey,
        val firstSeenMs: Long
    )

    private data class PublicAckBundle(
        val packet: MeshPacket,
        val acked: List<IdempotencyKey>
    )

    private data class AckOutboxPacket(
        var packet: MeshPacket,
        val originPid: Int,
        val ingressLink: String?,
        val retryUntilMs: Long,
        val expiresMs: Long,
        var nextRetryMs: Long,
        var attempts: Int = 0
    )

    private data class CachedKexResponse(
        val packet: MeshPacket,
        val expiresMs: Long
    )

    private data class PendingPrivateData(
        val packet: MeshPacket,
        val ingressLink: String,
        val expiresMs: Long
    )

    private val messageDedupMs: Long = 24L * 60 * 60 * 1000
    private val links = HashMap<String, LinkState>()
    private val known = HashMap<Int, KnownPeer>()
    private val seen = HashMap<IdempotencyKey, Long>()   // expires
    private val deliveredMessages = HashMap<IdempotencyKey, Long>()
    private val ackSeen = HashSet<Pair<IdempotencyKey, Int>>()  // (key, ackingPid)
    private val fanoutCache = HashMap<IdempotencyKey, FanoutPacket>()
    private val retrySpool = HashMap<IdempotencyKey, RetryPacket>()
    private val userOutbox = HashMap<IdempotencyKey, UserOutboxPacket>()
    private val ackOutbox = HashMap<AckOutboxKey, AckOutboxPacket>()
    private val publicAckBuffer = LinkedHashMap<IdempotencyKey, PendingPublicAck>()
    private val kexResponseCache = HashMap<IdempotencyKey, CachedKexResponse>()
    private val pendingPrivateData = HashMap<IdempotencyKey, PendingPrivateData>()
    private val delayedDmOutbox = HashMap<Int, ArrayDeque<DelayedDmText>>()
    private val linkPacketTx = HashMap<Pair<String, IdempotencyKey>, Long>()
    private val sessions = HashMap<Int, DmSession>()           // peer public_id -> DM session
    private val pendingKex = HashMap<Int, PendingKex>()        // target public_id -> in-flight KEX
    private val PRIV_DATA_LABEL = "SafeMesh/v2/private-data".toByteArray(Charsets.US_ASCII)
    private val PRIV_ACK_LABEL = "SafeMesh/v2/private-ack".toByteArray(Charsets.US_ASCII)

    private var seq: Long = 0
    private var lastControlMs: Long = 0

    private val fragmenter = Fragmenter()

    // ============================================================ Public API

    fun onLinkUp(linkId: String, rxHint: Int) {
        val safeRx = sanitizeRx(rxHint)
        val now = System.currentTimeMillis()
        expire(now)
        val state = links.getOrPut(linkId) { LinkState(safeRx, null) }
        state.rxHint = safeRx
        state.lastSeenMs = now
        lastControlMs = now
        sendControlToLink(linkId)
        flushPublicAckBundleToLink(linkId, now)
        flushAckOutboxToLink(linkId, now)
        flushPendingKexToLink(linkId, now)
        flushFanoutCacheToLink(linkId, now)
        flushRetrySpoolToLink(linkId, now)
        flushUserOutboxToLink(linkId, now)
    }

    fun onLinkDown(linkId: String, reason: String) {
        val state = links.remove(linkId) ?: return
        state.peerPublicId?.let { pid ->
            known[pid]?.viaLink = null
        }
        val txIt = linkPacketTx.keys.iterator()
        while (txIt.hasNext()) {
            if (txIt.next().first == linkId) txIt.remove()
        }
    }

    fun receiveFrame(linkId: String, frame: ByteArray) {
        val link = links.getOrPut(linkId) {
            LinkState(sanitizeRx(0), null)
        }
        link.lastSeenMs = System.currentTimeMillis()
        val packetBytes = try {
            link.reassembler.push(frame) ?: return
        } catch (t: Throwable) {
            // bad fragment — drop silently for the mockup
            return
        }
        val pkt = try { MeshPacket.decode(packetBytes) } catch (t: Throwable) { return }
        processPacket(pkt, linkId)
    }

    /** Send a broadcast text to all neighbors. Returns the idempotency key. */
    fun broadcastText(text: String): IdempotencyKey {
        val payload = Codec.encodeBroadcastText(text)
        val pkt = makePacket(Constants.FLAG_BROADCAST, Constants.MAX_TTL, payload)
        val key = pkt.header.idempotencyKey()
        rememberUserOutbound(pkt, targetPid = null, now = System.currentTimeMillis(), retryWindowMs = Constants.BROADCAST_REPLAY_MS)
        sendPacketAll(pkt, exclude = null)
        // Local echo to UI
        rememberDeliveredMessage(key)
        io.emit(MeshEvent.Broadcast(publicId, nick, 0, text, key))
        return key
    }

    /**
     * Send a private DM to [targetPid]. If a session exists, encrypt immediately;
     * otherwise run a v3 KEX (passkey-free) and flush this text once the session is up.
     * No passkey — identity is bound to the peer's fingerprint (public_id).
     */
    fun sendPrivate(targetPid: Int, text: String): IdempotencyKey {
        val s = sessions[targetPid]
        return if (s != null) sendPrivateData(s, text) else sendKexInit(targetPid, text)
    }

    fun hasSession(targetPid: Int): Boolean = sessions.containsKey(targetPid)

    /** Drive periodic control + ACK/DM retry. Called on the ACK gossip cadence. */
    fun tick() {
        val now = System.currentTimeMillis()
        expire(now)
        if (lastControlMs == 0L || now - lastControlMs >= Constants.CONTROL_PERIOD_MS) {
            lastControlMs = now
            sendControlAll()
        }
        sendPublicAckBundleAll(now)
        retryDueAcks(now)
        keepPendingKexAlive(now)
        flushDelayedDmOutbox(now)
        keepUserOutboxAlive(now)
        drainPendingPrivateData(now)
        retryDuePackets(now)
    }

    fun knownPeers(): List<KnownPeer> = known.values.toList()

    // ============================================================ Internals

    private fun sanitizeRx(rxHint: Int): Int {
        val v = if (rxHint == 0 || rxHint == Constants.DEFAULT_RX) Constants.SAFE_DEFAULT_GATT_RX else rxHint
        return v.coerceAtLeast(Frag.MIN_GATT_FRAME_LEN)
    }

    private fun effectiveRxForLink(linkId: String): Int {
        val linkRx = links[linkId]?.rxHint ?: Constants.SAFE_DEFAULT_GATT_RX
        val cap = if (rx == 0 || rx == Constants.DEFAULT_RX) linkRx else rx.coerceAtLeast(Frag.MIN_GATT_FRAME_LEN)
        return minOf(linkRx, cap).coerceAtLeast(Frag.MIN_GATT_FRAME_LEN)
    }

    private fun makePacket(flag: Int, ttl: Int, payload: ByteArray): MeshPacket {
        val t = System.currentTimeMillis()
        val epoch = Constants.epoch10s(t)
        seq = (seq + 1) and 0xFFFF_FFFF_FFFFL
        val h = Header(flag, ttl, payload.size, t, publicId, epoch, seq)
        return MeshPacket(h, payload)
    }

    private fun sendPacketAll(pkt: MeshPacket, exclude: String?, priority: Boolean = false): Boolean {
        var accepted = false
        for (link in io.activeLinkIds()) {
            if (link == exclude) continue
            accepted = sendPacketToLink(link, pkt, priority) || accepted
        }
        return accepted
    }
    private val defaultFragRx = Constants.SAFE_DEFAULT_GATT_RX

    private fun sendPacketToLink(linkId: String, pkt: MeshPacket, priority: Boolean = false): Boolean {
        val rxBytes = effectiveRxForLink(linkId)
        val accepted = io.sendFramesOnLink(linkId, fragmentPacket(pkt, rxBytes), priority)
        if (accepted) {
            rememberLinkPacketTx(linkId, pkt.header.idempotencyKey())
        }
        return accepted
    }

    private fun rememberLinkPacketTx(
        linkId: String,
        key: IdempotencyKey,
        now: Long = System.currentTimeMillis()
    ) {
        linkPacketTx[linkId to key] = now + Constants.LINK_TX_SUPPRESS_MS
    }

    private fun hasSentOnLink(linkId: String, key: IdempotencyKey, now: Long): Boolean =
        (linkPacketTx[linkId to key] ?: 0L) > now

    private fun sendPrivatePacketToTarget(targetPid: Int, pkt: MeshPacket, priority: Boolean = false) {
        candidateLinksToward(targetPid, includeAllActive = true)
            .forEach { sendPacketToLink(it, pkt, priority = priority) }
    }

    private fun candidateLinksToward(
        targetPid: Int,
        extraLink: String? = null,
        includeAllActive: Boolean = false
    ): LinkedHashSet<String> {
        val ids = LinkedHashSet<String>()
        links.forEach { (linkId, state) ->
            if (state.peerPublicId == targetPid) ids += linkId
        }
        known[targetPid]?.viaLink
            ?.takeIf { links.containsKey(it) }
            ?.let { ids += it }
        extraLink
            ?.takeIf { links.containsKey(it) }
            ?.let { ids += it }
        if (includeAllActive || ids.isEmpty()) io.activeLinkIds().sorted().forEach { ids += it }
        return ids
    }

    private fun shouldSendPrivateRetryOnLink(linkId: String, targetPid: Int): Boolean =
        candidateLinksToward(targetPid, includeAllActive = true).contains(linkId)

    private fun fragmentPacket(pkt: MeshPacket, perLinkRx: Int?): List<ByteArray> {
        val bytes = pkt.encode()
        val rxBytes = perLinkRx ?: defaultFragRx
        return fragmenter.fragment(bytes, rxBytes)
    }

    private fun processPacket(pkt: MeshPacket, ingressLink: String) {
        val key = pkt.header.idempotencyKey()
        val now = System.currentTimeMillis()
        val firstSighting = !seen.containsKey(key)
        if (firstSighting) rememberSeen(key, now)

        when (pkt.header.flag) {
            Constants.FLAG_BROADCAST -> {
                if (firstSighting) {
                    handleBroadcast(pkt)
                    noteReceipt(key, Constants.ACK_PUBLIC, now, ingressLink)
                    rememberForwardFanout(pkt, now)
                    forward(pkt, ingressLink)
                }
            }
            Constants.FLAG_CONTROL -> {
                if (firstSighting) {
                    handleControl(pkt, ingressLink)
                    forward(pkt, ingressLink)
                }
            }
            Constants.FLAG_ACK -> {
                if (firstSighting) {
                    handleAck(pkt)
                    forward(pkt, ingressLink)
                } else {
                    forwardToUnsentLinks(pkt, ingressLink, now)
                }
            }
            Constants.FLAG_PRIVATE -> {
                val privateKind = pkt.payload.firstOrNull()
                if (firstSighting) {
                    val isPrivateData = privateKind == Constants.PVT_DATA
                    val consumed = handlePrivate(pkt) // decrypts if addressed to us; emits PrivateMessage
                    if (consumed && isPrivateData) {
                        pendingPrivateData.remove(key)
                        noteReceipt(key, Constants.ACK_PRIVATE, now, ingressLink)
                    } else if (!consumed && isPrivateData) {
                        rememberPendingPrivateData(pkt, ingressLink, now)
                    }
                    rememberForwardFanout(pkt, now)
                    forward(pkt, ingressLink)   // relays still forward ciphertext they can't read
                } else {
                    when (privateKind) {
                        Constants.PVT_KEX_INIT -> {
                            handleKexInit(pkt)
                        }
                        Constants.PVT_KEX_RESP -> {
                            handleKexResp(pkt)
                        }
                        Constants.PVT_DATA -> {
                            val wasPending = pendingPrivateData.containsKey(key)
                            if (handlePrivateData(pkt, emit = wasPending)) {
                                pendingPrivateData.remove(key)
                                noteReceipt(key, Constants.ACK_PRIVATE, now, ingressLink)
                            } else if (wasPending) {
                                rememberPendingPrivateData(pkt, ingressLink, now)
                            }
                        }
                    }
                    forwardToUnsentLinks(pkt, ingressLink, now)
                }
            }
        }
    }

    private fun forward(pkt: MeshPacket, ingress: String?) {
        if (pkt.header.ttl == 0) return
        val fwd = pkt.copy(header = pkt.header.copy(ttl = pkt.header.ttl - 1))
        sendPacketAll(
            fwd,
            exclude = ingress,
            priority = fwd.header.flag == Constants.FLAG_PRIVATE || fwd.header.flag == Constants.FLAG_ACK
        )
    }

    private fun forwardToUnsentLinks(pkt: MeshPacket, ingress: String?, now: Long) {
        if (pkt.header.ttl == 0) return
        if (pkt.header.senderId == publicId) return
        val fwd = pkt.copy(header = pkt.header.copy(ttl = pkt.header.ttl - 1))
        val key = fwd.header.idempotencyKey()
        val priority = fwd.header.flag == Constants.FLAG_PRIVATE || fwd.header.flag == Constants.FLAG_ACK
        io.activeLinkIds().sorted().forEach { linkId ->
            if (linkId != ingress && !hasSentOnLink(linkId, key, now)) {
                sendPacketToLink(linkId, fwd, priority = priority)
            }
        }
    }

    private fun handleBroadcast(pkt: MeshPacket) {
        val text = Codec.decodeBroadcastText(pkt.payload) ?: return
        if (!rememberDeliveredMessage(pkt.header.idempotencyKey())) return
        val peerNick = known[pkt.header.senderId]?.nick ?: "%08x".format(pkt.header.senderId)
        val hops = (Constants.MAX_TTL - pkt.header.ttl + 1).coerceAtLeast(1)
        io.emit(MeshEvent.Broadcast(pkt.header.senderId, peerNick, hops, text, pkt.header.idempotencyKey()))
    }

    private fun handleControl(pkt: MeshPacket, ingress: String) {
        val baseHops = (Constants.MAX_TTL - pkt.header.ttl + 1).coerceAtLeast(1)
        val cp = try { Codec.decodeControlPayload(pkt.payload, baseHops) } catch (t: Throwable) { return }
        cp.entries.forEachIndexed { i, e ->
            if (e.publicId == publicId) return@forEachIndexed
            if (i == 0) {
                links[ingress]?.peerPublicId = e.publicId
            }
            val now = System.currentTimeMillis()
            val existed = known[e.publicId]
            if (existed == null) {
                known[e.publicId] = KnownPeer(e.publicId, e.nick, e.flags, e.rx, e.hops, now, ingress)
                io.emit(MeshEvent.PeerSeen(e.publicId, e.nick, e.hops))
            } else {
                existed.nick = e.nick
                existed.flags = e.flags
                existed.rx = e.rx
                if (e.hops < existed.hops) existed.hops = e.hops
                existed.lastSeenMs = now
                existed.viaLink = ingress
            }
        }
    }

    private fun handleAck(pkt: MeshPacket) {
        if (pkt.payload.isEmpty()) return
        when (pkt.payload[0]) {
            Constants.ACK_PUBLIC -> {
                val key = try { Codec.decodePublicAck(pkt.payload) } catch (t: Throwable) { return }
                observeAck(key, pkt.header.senderId)
            }
            Constants.ACK_BUNDLE -> {
                val records = try { Codec.decodeAckBundle(pkt.payload) } catch (t: Throwable) { return }
                for (r in records) {
                    observeAck(r.acked, r.ackingNodeId)
                }
            }
            Constants.ACK_PRIVATE -> handlePrivateAck(pkt)
            else -> { /* ignore unknown ACK kinds */ }
        }
    }

    // ----- Control / ACK emission ----------------------------------------

    private fun controlPayload(): ByteArray {
        val local = ControlEntry(nick, publicId, flags, rx, hops = 0)
        val knownEntries = known.values.sortedBy { it.hops }.take(6).map {
            ControlEntry(it.nick, it.publicId, it.flags, it.rx, it.hops)
        }
        return Codec.encodeControlPayload(local, knownEntries)
    }

    private fun sendControlAll() {
        val pkt = makePacket(Constants.FLAG_CONTROL, Constants.MAX_TTL, controlPayload())
        rememberSeen(pkt.header.idempotencyKey())
        sendPacketAll(pkt, exclude = null)
    }

    private fun sendControlToLink(linkId: String) {
        val pkt = makePacket(Constants.FLAG_CONTROL, Constants.MAX_TTL, controlPayload())
        rememberSeen(pkt.header.idempotencyKey())
        sendPacketToLink(linkId, pkt)
    }

    private fun noteReceipt(key: IdempotencyKey, ackKind: Byte, now: Long, ingressLink: String?) {
        if (key.senderId == publicId) return
        when (ackKind) {
            Constants.ACK_PRIVATE -> {
                val outboxKey = AckOutboxKey(key, publicId)
                if ((ackOutbox[outboxKey]?.retryUntilMs ?: 0L) > now) return
                sendPrivateAck(key, key.senderId, ingressLink, now)
            }
            else -> rememberPublicAck(key, now)
        }
    }

    private fun observeAck(acked: IdempotencyKey, byPid: Int) {
        if (!ackSeen.add(acked to byPid)) return
        if (acked.senderId != publicId) return
        if (pendingKex.values.any { it.kexKey == acked }) return
        retrySpool.remove(acked)
        userOutbox.remove(acked)
        io.emit(MeshEvent.AckReceived(acked, byPid))
    }

    private fun sendPublicAck(acked: IdempotencyKey, ingressLink: String?, now: Long) {
        val pkt = makePacket(Constants.FLAG_ACK, Constants.MAX_TTL, Codec.encodePublicAck(acked))
        rememberSeen(pkt.header.idempotencyKey(), now)
        rememberAckOutbox(acked, pkt, acked.senderId, ingressLink, now)
        sendAckPacketToward(acked.senderId, pkt, ingressLink)
    }

    private fun rememberPublicAck(acked: IdempotencyKey, now: Long) {
        if (publicAckBuffer.containsKey(acked)) return
        publicAckBuffer[acked] = PendingPublicAck(acked, now)
        while (publicAckBuffer.size > 64) {
            val oldest = publicAckBuffer.keys.firstOrNull() ?: break
            publicAckBuffer.remove(oldest)
        }
    }

    private fun makePublicAckBundle(now: Long): PublicAckBundle? {
        if (publicAckBuffer.isEmpty()) return null
        val pending = publicAckBuffer.values
            .take(Constants.MAX_ACK_BUNDLE_RECORDS)
        val records = pending.map { pendingAck ->
                AckRecord(
                    acked = pendingAck.acked,
                    ackingNodeId = publicId,
                    ackKind = Constants.ACK_PUBLIC,
                    firstSeenAgeMs = (now - pendingAck.firstSeenMs).coerceIn(0, 65_535).toInt()
                )
            }
        if (records.isEmpty()) return null
        val pkt = makePacket(Constants.FLAG_ACK, Constants.MAX_TTL, Codec.encodeAckBundle(records))
        rememberSeen(pkt.header.idempotencyKey(), now)
        return PublicAckBundle(pkt, pending.map { it.acked })
    }

    private fun sendPublicAckBundleAll(now: Long) {
        val bundle = makePublicAckBundle(now) ?: return
        if (sendPacketAll(bundle.packet, exclude = null)) {
            bundle.acked.forEach { publicAckBuffer.remove(it) }
        }
    }

    private fun flushPublicAckBundleToLink(linkId: String, now: Long) {
        val bundle = makePublicAckBundle(now) ?: return
        sendPacketToLink(linkId, bundle.packet)
    }

    private fun sendPrivateAck(acked: IdempotencyKey, peerId: Int, ingressLink: String?, now: Long) {
        val pkt = makePrivateAckPacket(acked, peerId, now) ?: return
        rememberAckOutbox(acked, pkt, peerId, ingressLink, now)
        sendAckPacketToward(peerId, pkt, ingressLink)
    }

    private fun makePrivateAckPacket(acked: IdempotencyKey, peerId: Int, now: Long): MeshPacket? {
        val session = sessions[peerId] ?: return null
        val nonce = Crypto.randomBytes(12)
        val inner = ByteArrayOutputStream()
        inner.write("SMA1".toByteArray(Charsets.US_ASCII))
        inner.write(acked.toBytes12())
        val stub = nextHeaderStub(Constants.FLAG_ACK, Constants.MAX_TTL)
        val aad = stub.aadForPrivate()
        val keymat = Crypto.deriveSessionKey(session.rootKey, PRIV_ACK_LABEL, nonce)
        val ct = Crypto.aeadEncrypt(session.alg, keymat, nonce, aad, inner.toByteArray())
        val payload = ByteArrayOutputStream()
        payload.write(Constants.ACK_PRIVATE.toInt())
        payload.write(session.alg.toInt())
        payload.write(nonce)
        payload.write(ct)
        val payloadBytes = payload.toByteArray()
        val pkt = MeshPacket(stub.copy(payloadLen = payloadBytes.size), payloadBytes)
        rememberSeen(pkt.header.idempotencyKey(), now)
        return pkt
    }

    private fun handlePrivateAck(pkt: MeshPacket) {
        val p = pkt.payload
        if (p.size < 1 + 1 + 12 + 16) return
        val alg = p[1]
        val nonce = p.copyOfRange(2, 14)
        val ct = p.copyOfRange(14, p.size)
        val aad = pkt.header.aadForPrivate()
        for (session in sessions.values.toList()) {
            val keymat = Crypto.deriveSessionKey(session.rootKey, PRIV_ACK_LABEL, nonce)
            val pt = try { Crypto.aeadDecrypt(alg, keymat, nonce, aad, ct) } catch (t: Throwable) { continue }
            if (pt.size != 16 || !magicEq(pt, 0, "SMA1")) continue
            val acked = try { IdempotencyKey.fromBytes12(pt, 4) } catch (t: Throwable) { return }
            dropKexResponseOutboxFor(session.peerPublicId)
            observeAck(acked, pkt.header.senderId)
            return
        }
    }

    private fun sendAckPacketToward(
        originPid: Int,
        pkt: MeshPacket,
        ingressLink: String?,
        includeAllActive: Boolean = false
    ) {
        candidateLinksToward(originPid, extraLink = ingressLink, includeAllActive = includeAllActive)
            .forEach { sendPacketToLink(it, pkt, priority = true) }
    }

    private fun rememberAckOutbox(acked: IdempotencyKey, pkt: MeshPacket, originPid: Int, ingressLink: String?, now: Long) {
        val replayMs = if (pkt.payload.firstOrNull() == Constants.ACK_PRIVATE) {
            Constants.BROADCAST_REPLAY_MS
        } else {
            Constants.ACK_REPLAY_MS
        }
        ackOutbox[AckOutboxKey(acked, publicId)] = AckOutboxPacket(
            packet = pkt,
            originPid = originPid,
            ingressLink = ingressLink,
            retryUntilMs = now + replayMs,
            expiresMs = now + replayMs + Constants.LAZY_CLEANUP_GRACE_MS,
            nextRetryMs = now + Constants.ACK_GOSSIP_MS
        )
    }

    private fun retryDueAcks(now: Long) {
        val due = ackOutbox
            .filter { (_, ack) -> ack.retryUntilMs > now && ack.nextRetryMs <= now }
            .keys
            .toList()

        for (key in due) {
            val ack = ackOutbox[key] ?: continue
            val isPrivateAck = ack.packet.payload.firstOrNull() == Constants.ACK_PRIVATE
            sendAckPacketToward(
                ack.originPid,
                ack.packet,
                ack.ingressLink,
                includeAllActive = isPrivateAck && ack.attempts >= 2
            )
            ack.attempts += 1
            val backoff = Constants.ACK_GOSSIP_MS * (1L shl ack.attempts.coerceAtMost(3))
            ack.nextRetryMs = now + backoff.coerceAtMost(Constants.ENCRYPTED_RETRY_MAX_MS)
        }
    }

    // ----- Private DM (v3: passkey-free, fingerprint-bound) --------------
    //
    // Wire follows the Rust engine v3 layout. KEX_INIT establishes only the
    // session; user DM text is sent later as encrypted PVT_DATA.
    //   KEX_INIT inner: "SMKI" target(4) initiator(4) nick8(8) id_pub(32)
    //                   eph_pub(32) flags(2) rx(1) msg_len(1=0)
    //   KEX_RESP inner: "SMKR" initiator(4) responder(4) nick8(8) id_pub(32)
    //                   eph_pub(32) flags(2) rx(1) [initiator_eph_pub(32)]
    //   PVT_DATA: type(1) alg(1) nonce(12) AEAD( "SMD1" recipient(4) sender(4) nick8(8) text )

    private fun handlePrivate(pkt: MeshPacket): Boolean {
        if (pkt.payload.isEmpty()) return false
        return when (pkt.payload[0]) {
            Constants.PVT_KEX_INIT -> handleKexInit(pkt)
            Constants.PVT_KEX_RESP -> handleKexResp(pkt)
            Constants.PVT_DATA -> handlePrivateData(pkt)
            else -> false
        }
    }

    private fun sendKexInit(targetPid: Int, text: String): IdempotencyKey {
        val now = System.currentTimeMillis()
        if (text.isNotBlank()) enqueueDelayedDm(targetPid, text, now)
        pendingKex[targetPid]?.let { pending ->
            pruneExpiredDelayedDm(targetPid, now)
            if (pending.retryUntilMs > now) {
                pending.retryUntilMs = maxOf(pending.retryUntilMs, now + Constants.BROADCAST_REPLAY_MS)
                pending.expiresMs = maxOf(pending.expiresMs, pending.retryUntilMs + Constants.LAZY_CLEANUP_GRACE_MS)
                rememberPendingKexOutbound(pending, now)
                sendPrivatePacketToTarget(targetPid, pending.initPacket, priority = true)
                return pending.kexKey
            }
            removePendingKex(targetPid, pending)
        }
        val (ephPriv, ephPub) = Crypto.x25519Keypair()
        val inner = ByteArrayOutputStream()
        inner.write("SMKI".toByteArray(Charsets.US_ASCII))
        inner.write(intBE(targetPid))
        inner.write(intBE(publicId))
        inner.write(Codec.encodeNick8(nick))
        inner.write(identity.publicKey)
        inner.write(ephPub)
        inner.write(shortBE(flags))
        inner.write(rx and 0xff)
        inner.write(0)
        val payload = ByteArrayOutputStream()
        payload.write(Constants.PVT_KEX_INIT.toInt())
        payload.write(inner.toByteArray())
        val pkt = privatePacket(payload.toByteArray())
        val key = pkt.header.idempotencyKey()
        val pending = PendingKex(
            targetPublicId = targetPid,
            ephPriv = ephPriv,
            ephPub = ephPub,
            initPacket = pkt,
            kexKey = key,
            createdMs = now,
            retryUntilMs = now + Constants.BROADCAST_REPLAY_MS,
            expiresMs = now + Constants.LAZY_CLEANUP_MS
        )
        pendingKex[targetPid] = pending
        rememberPendingKexOutbound(pending, now)
        sendPrivatePacketToTarget(targetPid, pkt, priority = true)
        return key
    }

    private fun sendKexResp(
        initiatorPid: Int,
        respEphPub: ByteArray,
        initiatorEphPub: ByteArray,
        initKey: IdempotencyKey? = null
    ): IdempotencyKey {
        val inner = ByteArrayOutputStream()
        inner.write("SMKR".toByteArray(Charsets.US_ASCII))
        inner.write(intBE(initiatorPid))
        inner.write(intBE(publicId))
        inner.write(Codec.encodeNick8(nick))
        inner.write(identity.publicKey)
        inner.write(respEphPub)
        inner.write(shortBE(flags))
        inner.write(rx and 0xff)
        inner.write(initiatorEphPub)
        val payload = ByteArrayOutputStream()
        payload.write(Constants.PVT_KEX_RESP.toInt())
        payload.write(inner.toByteArray())
        val pkt = privatePacket(payload.toByteArray())
        val key = pkt.header.idempotencyKey()
        val now = System.currentTimeMillis()
        rememberSeen(key, now)
        rememberUserOutboundUntil(
            pkt = pkt,
            targetPid = initiatorPid,
            now = now,
            retryWindowMs = Constants.BROADCAST_REPLAY_MS,
            retryUntilMs = now + Constants.BROADCAST_REPLAY_MS,
            expiresMs = now + Constants.ENCRYPTED_RELAY_SPOOL_MS
        )
        if (initKey != null) {
            kexResponseCache[initKey] = CachedKexResponse(pkt, now + Constants.ENCRYPTED_RELAY_SPOOL_MS)
        }
        sendPrivatePacketToTarget(initiatorPid, pkt, priority = true)
        return key
    }

    private fun sendPrivateData(session: DmSession, text: String): IdempotencyKey {
        val inner = ByteArrayOutputStream()
        inner.write("SMD1".toByteArray(Charsets.US_ASCII))
        inner.write(intBE(session.peerPublicId))   // recipient
        inner.write(intBE(publicId))                // sender
        inner.write(Codec.encodeNick8(nick))
        // Cap so the whole packet stays within the 255-byte payload_len (u8) limit.
        val body = text.toByteArray(Charsets.UTF_8)
        inner.write(if (body.size > 200) body.copyOf(200) else body)
        val stub = nextHeaderStub(Constants.FLAG_PRIVATE, Constants.MAX_TTL)
        val nonce = Crypto.randomBytes(12)
        val aad = stub.aadForPrivate()
        val keymat = Crypto.deriveSessionKey(session.rootKey, PRIV_DATA_LABEL, nonce)
        val ct = Crypto.aeadEncrypt(session.alg, keymat, nonce, aad, inner.toByteArray())
        val payload = ByteArrayOutputStream()
        payload.write(Constants.PVT_DATA.toInt())
        payload.write(session.alg.toInt())
        payload.write(nonce)
        payload.write(ct)
        val payloadBytes = payload.toByteArray()
        val pkt = MeshPacket(stub.copy(payloadLen = payloadBytes.size), payloadBytes)
        val key = pkt.header.idempotencyKey()
        val now = System.currentTimeMillis()
        rememberUserOutbound(pkt, session.peerPublicId, now, Constants.BROADCAST_REPLAY_MS)
        sendPrivatePacketToTarget(session.peerPublicId, pkt, priority = true)
        io.emit(MeshEvent.PrivateSent(session.peerPublicId, text, key))
        return key
    }

    private fun handleKexInit(pkt: MeshPacket): Boolean {
        val p = pkt.payload
        if (p.size < 1 + 88) return false
        val inner = p.copyOfRange(1, p.size)
        if (!magicEq(inner, 0, "SMKI")) return false
        val targetPid = intFromBE(inner, 4)
        if (targetPid != publicId) return false
        val initiatorPid = intFromBE(inner, 8)
        val initiatorNick = Codec.decodeNick8(inner, 12)
        val initiatorIdPub = inner.copyOfRange(20, 52)
        val initiatorEphPub = inner.copyOfRange(52, 84)
        val initiatorFlags = shortFromBE(inner, 84)
        val msgLen = inner[87].toInt() and 0xff
        if (msgLen != 0) return false
        if (inner.size < 88 + msgLen) return false
        val initKey = pkt.header.idempotencyKey()
        val now = System.currentTimeMillis()
        val competingPending = pendingKex[initiatorPid]
        if (competingPending != null && !incomingKexWinsTie(initiatorPid)) {
            pruneExpiredDelayedDm(initiatorPid, now)
            rememberPendingKexOutbound(competingPending, now)
            sendPrivatePacketToTarget(initiatorPid, competingPending.initPacket, priority = true)
            return false
        }
        kexResponseCache[initKey]
            ?.takeIf { it.expiresMs > now && it.packet.header.timestampMs + Constants.BROADCAST_REPLAY_MS > now }
            ?.let { cached ->
                rememberUserOutboundUntil(
                    pkt = cached.packet,
                    targetPid = initiatorPid,
                    now = now,
                    retryWindowMs = Constants.BROADCAST_REPLAY_MS,
                    retryUntilMs = now + Constants.BROADCAST_REPLAY_MS,
                    expiresMs = now + Constants.ENCRYPTED_RELAY_SPOOL_MS
                )
                sendPrivatePacketToTarget(initiatorPid, cached.packet, priority = true)
                return true
            }
        // Fingerprint binding (C): claimed public_id MUST equal SHA-256(claimed key).
        if (Crypto.publicIdFromIdentity(initiatorIdPub) != initiatorPid) return false
        val (respEphPriv, respEphPub) = Crypto.x25519Keypair()
        val shared = Crypto.x25519(respEphPriv, initiatorEphPub)
        val root = Crypto.deriveDmRoot(shared, initiatorPid, publicId, initiatorIdPub, identity.publicKey, initiatorEphPub, respEphPub)
        val alg = Crypto.chooseAlg(initiatorFlags, flags)
        val session = DmSession(initiatorPid, initiatorNick, initiatorIdPub, root, alg)
        sessions[initiatorPid] = session
        if (competingPending != null) {
            pendingKex.remove(initiatorPid)
            retrySpool.remove(competingPending.kexKey)
            userOutbox.remove(competingPending.kexKey)
        }
        sendKexResp(initiatorPid, respEphPub, initiatorEphPub, initKey)
        flushDelayedDmFor(initiatorPid, session, System.currentTimeMillis())
        drainPendingPrivateData(System.currentTimeMillis())
        return true
    }

    private fun incomingKexWinsTie(initiatorPid: Int): Boolean =
        Integer.compareUnsigned(initiatorPid, publicId) < 0

    private fun handleKexResp(pkt: MeshPacket): Boolean {
        val p = pkt.payload
        if (p.size < 1 + 87) return false
        val inner = p.copyOfRange(1, p.size)
        if (!magicEq(inner, 0, "SMKR")) return false
        val initiatorPid = intFromBE(inner, 4)
        val responderPid = intFromBE(inner, 8)
        if (initiatorPid != publicId) return false
        val pending = pendingKex[responderPid] ?: return false
        val responderNick = Codec.decodeNick8(inner, 12)
        val responderIdPub = inner.copyOfRange(20, 52)
        val responderEphPub = inner.copyOfRange(52, 84)
        val responderFlags = shortFromBE(inner, 84)
        if (inner.size >= 119) {
            val echoedInitiatorEph = inner.copyOfRange(87, 119)
            if (!echoedInitiatorEph.contentEquals(pending.ephPub)) return false
        }
        // Fingerprint binding (C).
        if (Crypto.publicIdFromIdentity(responderIdPub) != responderPid) return false
        val shared = Crypto.x25519(pending.ephPriv, responderEphPub)
        val root = Crypto.deriveDmRoot(shared, publicId, responderPid, identity.publicKey, responderIdPub, pending.ephPub, responderEphPub)
        val alg = Crypto.chooseAlg(flags, responderFlags)
        val session = DmSession(responderPid, responderNick, responderIdPub, root, alg)
        sessions[responderPid] = session
        pendingKex.remove(responderPid)
        retrySpool.remove(pending.kexKey)
        userOutbox.remove(pending.kexKey)
        io.emit(MeshEvent.PeerSeen(responderPid, responderNick, 0))   // session established
        flushDelayedDmFor(responderPid, session, System.currentTimeMillis())
        drainPendingPrivateData(System.currentTimeMillis())
        return true
    }

    private fun handlePrivateData(pkt: MeshPacket, emit: Boolean = true): Boolean {
        val p = pkt.payload
        if (p.size < 1 + 1 + 12 + 16) return false
        val alg = p[1]
        val nonce = p.copyOfRange(2, 14)
        val ct = p.copyOfRange(14, p.size)
        val aad = pkt.header.aadForPrivate()
        for (session in sessions.values) {
            val key = Crypto.deriveSessionKey(session.rootKey, PRIV_DATA_LABEL, nonce)
            val pt = try { Crypto.aeadDecrypt(alg, key, nonce, aad, ct) } catch (t: Throwable) { continue }
            if (pt.size < 20 || !magicEq(pt, 0, "SMD1")) continue
            val recipient = intFromBE(pt, 4)
            if (recipient != publicId) continue
            val sender = intFromBE(pt, 8)
            val senderNick = Codec.decodeNick8(pt, 12)
            val text = String(pt, 20, pt.size - 20, Charsets.UTF_8)
            dropKexResponseOutboxFor(sender)
            if (emit && rememberDeliveredMessage(pkt.header.idempotencyKey())) {
                io.emit(MeshEvent.PrivateMessage(sender, if (senderNick.isEmpty()) session.peerNick else senderNick, text))
            }
            return true
        }
        return false
    }

    /** Build a FLAG_PRIVATE packet from a fully-formed payload (fresh epoch/seq). */
    private fun privatePacket(payloadBytes: ByteArray): MeshPacket {
        val stub = nextHeaderStub(Constants.FLAG_PRIVATE, Constants.MAX_TTL)
        return MeshPacket(stub.copy(payloadLen = payloadBytes.size), payloadBytes)
    }

    private fun nextHeaderStub(flag: Int, ttl: Int): Header {
        val t = System.currentTimeMillis()
        val epoch = Constants.epoch10s(t)
        seq = (seq + 1) and 0xFFFF_FFFF_FFFFL
        return Header(flag, ttl, 0, t, publicId, epoch, seq)
    }

    private fun intBE(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun shortBE(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun intFromBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
    private fun shortFromBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
    private fun magicEq(b: ByteArray, off: Int, s: String): Boolean {
        val m = s.toByteArray(Charsets.US_ASCII)
        if (b.size < off + m.size) return false
        for (i in m.indices) if (b[off + i] != m[i]) return false
        return true
    }

    // ----- Bookkeeping ---------------------------------------------------

    private fun rememberSeen(key: IdempotencyKey, now: Long = System.currentTimeMillis()) {
        seen[key] = now + Constants.SEEN_EXPIRY_MS
    }

    private fun rememberDeliveredMessage(key: IdempotencyKey, now: Long = System.currentTimeMillis()): Boolean {
        val existing = deliveredMessages[key]
        if (existing != null && existing > now) return false
        deliveredMessages[key] = now + messageDedupMs
        while (deliveredMessages.size > 2048) {
            val oldest = deliveredMessages.minByOrNull { it.value }?.key ?: break
            deliveredMessages.remove(oldest)
        }
        return true
    }

    private fun rememberOutbound(pkt: MeshPacket, now: Long) {
        rememberSeen(pkt.header.idempotencyKey(), now)
        rememberFanout(pkt, now)
        rememberRetry(pkt, now)
    }

    private fun rememberUserOutbound(pkt: MeshPacket, targetPid: Int?, now: Long, retryWindowMs: Long) {
        val key = pkt.header.idempotencyKey()
        val retryUntilMs = userOutbox[key]?.retryUntilMs ?: (pkt.header.timestampMs + retryWindowMs)
        val expiresMs = userOutbox[key]?.expiresMs ?: (retryUntilMs + Constants.LAZY_CLEANUP_GRACE_MS)
        rememberUserOutboundUntil(pkt, targetPid, now, retryWindowMs, retryUntilMs, expiresMs)
    }

    private fun rememberUserOutboundUntil(
        pkt: MeshPacket,
        targetPid: Int?,
        now: Long,
        retryWindowMs: Long,
        retryUntilMs: Long,
        expiresMs: Long
    ) {
        val key = pkt.header.idempotencyKey()
        if (expiresMs <= now) {
            retrySpool.remove(key)
            userOutbox.remove(key)
            return
        }
        rememberSeen(key, now)
        if (targetPid == null) rememberFanoutUntil(pkt, expiresMs)
        rememberRetryUntil(pkt, now, retryUntilMs, expiresMs)
        userOutbox[key] = UserOutboxPacket(pkt, targetPid, retryWindowMs, retryUntilMs, expiresMs)
    }

    private fun rememberPendingKexOutbound(pending: PendingKex, now: Long) {
        rememberUserOutboundUntil(
            pkt = pending.initPacket,
            targetPid = pending.targetPublicId,
            now = now,
            retryWindowMs = Constants.BROADCAST_REPLAY_MS,
            retryUntilMs = pending.retryUntilMs,
            expiresMs = pending.expiresMs
        )
    }

    private fun enqueueDelayedDm(targetPid: Int, text: String, now: Long) {
        val queue = delayedDmOutbox.getOrPut(targetPid) { ArrayDeque() }
        queue.addLast(DelayedDmText(text, now))
        while (queue.size > 64) queue.removeFirst()
    }

    private fun pruneExpiredDelayedDm(targetPid: Int, now: Long) {
        val queue = delayedDmOutbox[targetPid] ?: return
        while (queue.isNotEmpty() &&
            queue.first().createdMs + Constants.BROADCAST_REPLAY_MS <= now) {
            queue.removeFirst()
        }
        if (queue.isEmpty()) delayedDmOutbox.remove(targetPid)
    }

    private fun hasDelayedDm(targetPid: Int, now: Long): Boolean {
        pruneExpiredDelayedDm(targetPid, now)
        return !delayedDmOutbox[targetPid].isNullOrEmpty()
    }

    private fun flushDelayedDmFor(targetPid: Int, session: DmSession, now: Long) {
        pruneExpiredDelayedDm(targetPid, now)
        val queue = delayedDmOutbox.remove(targetPid) ?: return
        queue.forEach { delayed ->
            if (delayed.text.isNotBlank() &&
                delayed.createdMs + Constants.BROADCAST_REPLAY_MS > now) {
                sendPrivateData(session, delayed.text)
            }
        }
    }

    private fun flushDelayedDmOutbox(now: Long) {
        val peers = delayedDmOutbox.keys.toList()
        for (peerId in peers) {
            val session = sessions[peerId]
            if (session != null) {
                flushDelayedDmFor(peerId, session, now)
                continue
            }
            if (hasDelayedDm(peerId, now) && !pendingKex.containsKey(peerId)) {
                sendKexInit(peerId, "")
            }
        }
    }

    private fun removePendingKex(targetPid: Int, pending: PendingKex) {
        pendingKex.remove(targetPid)
        retrySpool.remove(pending.kexKey)
        userOutbox.remove(pending.kexKey)
    }

    private fun dropKexResponseOutboxFor(peerPid: Int) {
        val keys = userOutbox
            .filter { (_, out) ->
                out.targetPid == peerPid &&
                    out.packet.header.flag == Constants.FLAG_PRIVATE &&
                    out.packet.payload.firstOrNull() == Constants.PVT_KEX_RESP
            }
            .keys
            .toList()
        for (key in keys) {
            userOutbox.remove(key)
            retrySpool.remove(key)
        }
    }

    private fun rememberFanout(pkt: MeshPacket, now: Long) {
        rememberFanoutUntil(pkt, now + Constants.BROADCAST_REPLAY_MS)
    }

    private fun rememberFanoutUntil(pkt: MeshPacket, expiresMs: Long) {
        fanoutCache[pkt.header.idempotencyKey()] = FanoutPacket(
            packet = pkt,
            expiresMs = expiresMs
        )
    }

    private fun rememberForwardFanout(pkt: MeshPacket, now: Long) {
        if (pkt.header.ttl == 0) return
        val fwd = pkt.copy(header = pkt.header.copy(ttl = pkt.header.ttl - 1))
        rememberFanout(fwd, now)
    }

    private fun rememberRetry(pkt: MeshPacket, now: Long) {
        rememberRetry(pkt, now, Constants.BROADCAST_REPLAY_MS)
    }

    private fun rememberRetry(pkt: MeshPacket, now: Long, windowMs: Long) {
        val retryUntilMs = now + minOf(windowMs, Constants.BROADCAST_REPLAY_MS)
        val expiresMs = now + windowMs
        rememberRetryUntil(pkt, now, retryUntilMs, expiresMs)
    }

    private fun rememberRetryUntil(pkt: MeshPacket, now: Long, retryUntilMs: Long, expiresMs: Long) {
        if (expiresMs <= now) return
        if (retryUntilMs <= now) return
        val key = pkt.header.idempotencyKey()
        retrySpool[key] = RetryPacket(
            packet = pkt,
            retryUntilMs = retryUntilMs,
            expiresMs = expiresMs,
            nextRetryMs = now + Constants.ENCRYPTED_RETRY_INITIAL_MS
        )
    }

    private fun rememberPendingPrivateData(pkt: MeshPacket, ingressLink: String, now: Long) {
        val key = pkt.header.idempotencyKey()
        pendingPrivateData[key] = PendingPrivateData(
            packet = pkt,
            ingressLink = ingressLink,
            expiresMs = now + Constants.ENCRYPTED_RELAY_SPOOL_MS
        )
        while (pendingPrivateData.size > 64) {
            val oldest = pendingPrivateData.minByOrNull { it.value.expiresMs }?.key ?: break
            pendingPrivateData.remove(oldest)
        }
    }

    private fun drainPendingPrivateData(now: Long) {
        if (pendingPrivateData.isEmpty()) return
        val keys = pendingPrivateData.keys.toList()
        for (key in keys) {
            val pending = pendingPrivateData[key] ?: continue
            if (pending.expiresMs <= now) {
                pendingPrivateData.remove(key)
                continue
            }
            if (handlePrivateData(pending.packet, emit = true)) {
                pendingPrivateData.remove(key)
                noteReceipt(key, Constants.ACK_PRIVATE, now, pending.ingressLink)
            }
        }
    }

    private fun retryDuePackets(now: Long) {
        val due = retrySpool
            .filter { (_, retry) -> retry.retryUntilMs > now && retry.nextRetryMs <= now }
            .keys
            .toList()

        for (key in due) {
            val retry = retrySpool[key] ?: continue
            val targetPid = userOutbox[key]?.targetPid
            if (targetPid != null) {
                sendPrivatePacketToTarget(targetPid, retry.packet, priority = true)
            } else {
                sendPacketAll(retry.packet, exclude = null)
            }
            retry.attempts += 1
            val backoff = if (targetPid != null) {
                Constants.ENCRYPTED_RETRY_INITIAL_MS * (1L shl retry.attempts.coerceAtMost(5))
            } else {
                700L * (1L shl retry.attempts.coerceAtMost(5))
            }
            retry.nextRetryMs = now + backoff.coerceAtMost(if (targetPid != null) Constants.ENCRYPTED_RETRY_MAX_MS else 12_000)
            if (retry.expiresMs <= now) {
                retrySpool.remove(key)
            }
        }
    }

    private fun flushFanoutCacheToLink(linkId: String, now: Long) {
        val packets = fanoutCache.values
            .filter {
                it.expiresMs > now &&
                    !hasSentOnLink(linkId, it.packet.header.idempotencyKey(), now)
            }
            .map { it.packet }
        for (packet in packets) {
            sendPacketToLink(
                linkId,
                packet,
                priority = packet.header.flag == Constants.FLAG_PRIVATE || packet.header.flag == Constants.FLAG_ACK
            )
        }
    }

    private fun flushRetrySpoolToLink(linkId: String, now: Long) {
        val packets = retrySpool.entries
            .filter { (key, retry) ->
                retry.retryUntilMs > now &&
                    !hasSentOnLink(linkId, key, now) &&
                    userOutbox[key]?.targetPid?.let { targetPid -> shouldSendPrivateRetryOnLink(linkId, targetPid) } != false
            }
            .sortedBy { it.value.nextRetryMs }
            .take(8)
            .map { (key, retry) -> retry.packet to (userOutbox[key]?.targetPid != null) }
        for ((packet, isTargeted) in packets) sendPacketToLink(linkId, packet, priority = isTargeted)
    }

    private fun flushUserOutboxToLink(linkId: String, now: Long) {
        userOutbox.values
            .filter { out ->
                out.retryUntilMs > now &&
                    !hasSentOnLink(linkId, out.packet.header.idempotencyKey(), now) &&
                    out.targetPid?.let { targetPid -> shouldSendPrivateRetryOnLink(linkId, targetPid) } != false
            }
            .forEach { out ->
                rememberRetryUntil(out.packet, now, out.retryUntilMs, out.expiresMs)
                sendPacketToLink(linkId, out.packet, priority = out.targetPid != null)
            }
    }

    private fun flushPendingKexToLink(linkId: String, now: Long) {
        pendingKex.values
            .filter {
                !sessions.containsKey(it.targetPublicId) &&
                    hasDelayedDm(it.targetPublicId, now) &&
                    it.retryUntilMs > now &&
                    !hasSentOnLink(linkId, it.kexKey, now) &&
                    shouldSendPrivateRetryOnLink(linkId, it.targetPublicId)
            }
            .forEach { pending ->
                rememberPendingKexOutbound(pending, now)
                sendPacketToLink(linkId, pending.initPacket, priority = true)
            }
    }

    private fun flushAckOutboxToLink(linkId: String, now: Long) {
        ackOutbox.values
            .filter { ack ->
                ack.retryUntilMs > now &&
                    !hasSentOnLink(linkId, ack.packet.header.idempotencyKey(), now) &&
                    candidateLinksToward(ack.originPid, extraLink = ack.ingressLink).contains(linkId)
            }
            .take(8)
            .forEach { ack -> sendPacketToLink(linkId, ack.packet, priority = true) }
    }

    private fun keepPendingKexAlive(now: Long) {
        if (pendingKex.isEmpty() || io.activeLinkIds().isEmpty()) return
        pendingKex.values
            .filter { pending ->
                !sessions.containsKey(pending.targetPublicId) &&
                    hasDelayedDm(pending.targetPublicId, now) &&
                    pending.retryUntilMs > now &&
                    (!retrySpool.containsKey(pending.kexKey) || !userOutbox.containsKey(pending.kexKey))
            }
            .forEach { pending ->
                rememberPendingKexOutbound(pending, now)
                sendPrivatePacketToTarget(pending.targetPublicId, pending.initPacket, priority = true)
            }
    }

    private fun keepUserOutboxAlive(now: Long) {
        if (userOutbox.isEmpty() || io.activeLinkIds().isEmpty()) return
        userOutbox.entries
            .filter { it.value.retryUntilMs > now && !retrySpool.containsKey(it.key) }
            .forEach { (_, out) ->
                rememberRetryUntil(out.packet, now, out.retryUntilMs, out.expiresMs)
                out.targetPid?.let { sendPrivatePacketToTarget(it, out.packet, priority = true) }
                    ?: sendPacketAll(out.packet, exclude = null)
            }
    }

    private fun expire(now: Long) {
        val itSeen = seen.entries.iterator()
        while (itSeen.hasNext()) if (itSeen.next().value <= now) itSeen.remove()
        val itDelivered = deliveredMessages.entries.iterator()
        while (itDelivered.hasNext()) if (itDelivered.next().value <= now) itDelivered.remove()
        val itFanout = fanoutCache.entries.iterator()
        while (itFanout.hasNext()) if (itFanout.next().value.expiresMs <= now) itFanout.remove()
        val itLinkTx = linkPacketTx.entries.iterator()
        while (itLinkTx.hasNext()) if (itLinkTx.next().value <= now) itLinkTx.remove()
        val itRetry = retrySpool.entries.iterator()
        while (itRetry.hasNext()) {
            val retry = itRetry.next().value
            if (retry.expiresMs <= now) itRetry.remove()
        }
        val itUserOutbox = userOutbox.entries.iterator()
        while (itUserOutbox.hasNext()) {
            if (itUserOutbox.next().value.expiresMs <= now) itUserOutbox.remove()
        }
        val itPendingKex = pendingKex.entries.iterator()
        while (itPendingKex.hasNext()) {
            val pending = itPendingKex.next().value
            val hasDelayed = hasDelayedDm(pending.targetPublicId, now)
            if (pending.expiresMs <= now || !hasDelayed || sessions.containsKey(pending.targetPublicId)) {
                retrySpool.remove(pending.kexKey)
                userOutbox.remove(pending.kexKey)
                itPendingKex.remove()
            }
        }
        val itAck = ackOutbox.entries.iterator()
        while (itAck.hasNext()) {
            if (itAck.next().value.expiresMs <= now) itAck.remove()
        }
        val itPublicAck = publicAckBuffer.entries.iterator()
        while (itPublicAck.hasNext()) {
            if (itPublicAck.next().value.firstSeenMs + Constants.ACK_REPLAY_MS <= now) itPublicAck.remove()
        }
        val itKexResp = kexResponseCache.entries.iterator()
        while (itKexResp.hasNext()) {
            if (itKexResp.next().value.expiresMs <= now) itKexResp.remove()
        }
        val itPendingPrivate = pendingPrivateData.entries.iterator()
        while (itPendingPrivate.hasNext()) {
            if (itPendingPrivate.next().value.expiresMs <= now) itPendingPrivate.remove()
        }
        val delayedPeers = delayedDmOutbox.keys.toList()
        delayedPeers.forEach { pruneExpiredDelayedDm(it, now) }
    }
}
