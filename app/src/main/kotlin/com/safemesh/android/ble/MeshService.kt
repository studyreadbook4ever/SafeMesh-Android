package com.safemesh.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.safemesh.android.R
import com.safemesh.android.proto.Constants
import com.safemesh.android.proto.Identity
import com.safemesh.android.proto.IdempotencyKey
import com.safemesh.android.proto.SafeMeshNode
import com.safemesh.android.ui.AckNotice
import com.safemesh.android.ui.ChatMessage
import com.safemesh.android.ui.DeliveryState
import com.safemesh.android.ui.MainActivity
import com.safemesh.android.ui.MeshUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that wires together advertising, scanning, GATT server,
 * GATT client, the connection tracker, and the protocol engine
 * (`SafeMeshNode`). All BLE callbacks dispatch through `nodeScope` so that
 * the protocol engine (which is single-threaded) sees one event at a time.
 *
 * Bitchat reference: dual role + central scan/dial + peripheral accept +
 * dedup-by-address. Connection thrash prevention (don't tear down redundant
 * links; dedup at message layer) is enforced here by simply NOT calling
 * disconnect on duplicates — message dedup happens inside SafeMeshNode via
 * idempotency keys (mirror of Rust `node.rs::process_packet` seen check).
 */
class MeshService : Service(), SafeMeshNode.NodeIo {

    inner class LocalBinder : Binder() { fun service(): MeshService = this@MeshService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var node: SafeMeshNode
    private lateinit var identity: Identity
    private val tracker = ConnectionTracker()
    private var advertiser: Advertiser? = null
    private var scanner: Scanner? = null
    private var gattServer: GattServer? = null
    private var gattClient: GattClient? = null

    /** Outbound writer per link id: peripheral:* (client) or central:* (server). */
    private val activeLinks = ConcurrentHashMap<String, Unit>()

    private val nodeScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickJob: Job? = null

    /**
     * SafeMeshNode is not thread-safe. EVERY access (from BLE callbacks via
     * nodeScope.launch AND from UI poll threads) must hold this lock.
     */
    private val nodeLock = Any()

    private val mutableEvents = mutableListOf<SafeMeshNode.MeshEvent>()
    fun snapshotEvents(): List<SafeMeshNode.MeshEvent> =
        synchronized(nodeLock) { mutableEvents.toList() }
    fun snapshotPeers(): List<SafeMeshNode.KnownPeer> = synchronized(nodeLock) {
        if (this::node.isInitialized) node.knownPeers() else emptyList()
    }

    // ----- Conversation store (UI source of truth) -----------------------
    //
    // All reads/writes happen under nodeLock (emit() runs inside node calls
    // that already hold it; broadcast()/sendDm() take it explicitly).

    private val broadcastMsgs = mutableListOf<ChatMessage>()
    private val dmMsgs = HashMap<Int, MutableList<ChatMessage>>()

    /** Hot UI state. Updated on every mesh event and every outbound send. */
    private val _state = MutableStateFlow(MeshUiState.EMPTY)
    val state: StateFlow<MeshUiState> = _state.asStateFlow()
    private var nextMessageNotifId = 2_000

    /** Rebuild the immutable snapshot. Caller MUST hold nodeLock. */
    private fun publishState() {
        _state.value = MeshUiState(
            selfNick = if (this::node.isInitialized) node.nick else "",
            selfPublicId = if (this::identity.isInitialized) identity.publicId else 0,
            peers = if (this::node.isInitialized) node.knownPeers() else emptyList(),
            broadcast = broadcastMsgs.toList(),
            dms = dmMsgs.mapValues { it.value.toList() }
        )
    }

    private fun appendBroadcast(msg: ChatMessage) {
        broadcastMsgs += msg
        while (broadcastMsgs.size > 500) broadcastMsgs.removeAt(0)
    }

    private fun appendDm(peerId: Int, msg: ChatMessage) {
        val list = dmMsgs.getOrPut(peerId) { mutableListOf() }
        list += msg
        while (list.size > 500) list.removeAt(0)
    }

    private fun outboundDelivery(): DeliveryState =
        if (activeLinks.isEmpty()) DeliveryState.Pending else DeliveryState.Sent

    private fun nickForPid(pid: Int): String =
        if (this::node.isInitialized) {
            node.knownPeers().firstOrNull { it.publicId == pid }?.nick ?: "%08x".format(pid)
        } else {
            "%08x".format(pid)
        }

    private fun markAck(key: IdempotencyKey, byPid: Int): Boolean {
        var changed = false
        val byNick = nickForPid(byPid)
        val now = System.currentTimeMillis()
        fun updated(msg: ChatMessage): ChatMessage {
            if (!msg.isMine || msg.key != key) return msg
            if (msg.delivery == DeliveryState.Failed || now >= msg.expiresAtMs) return msg
            if (msg.ackedBy.contains(byPid)) return msg
            changed = true
            val notices = if (msg.convId == BROADCAST_CONV_ID) {
                msg.ackNotices + AckNotice(byPid, byNick, now)
            } else {
                msg.ackNotices
            }
            return msg.copy(
                delivery = DeliveryState.Acked,
                ackedBy = msg.ackedBy + byPid,
                ackNotices = notices
            )
        }

        for (i in broadcastMsgs.indices) {
            broadcastMsgs[i] = updated(broadcastMsgs[i])
        }
        for (messages in dmMsgs.values) {
            for (i in messages.indices) {
                messages[i] = updated(messages[i])
            }
        }
        return changed
    }

    private fun refreshOutboundDelivery(now: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        val hasLink = activeLinks.isNotEmpty()
        fun updated(msg: ChatMessage): ChatMessage {
            if (!msg.isMine || msg.delivery == DeliveryState.Acked || msg.delivery == DeliveryState.Received) return msg
            if (now >= msg.expiresAtMs) {
                if (msg.delivery != DeliveryState.Failed) changed = true
                return msg.copy(delivery = DeliveryState.Failed)
            }
            if (hasLink && msg.key != null && msg.delivery == DeliveryState.Pending) {
                changed = true
                return msg.copy(delivery = DeliveryState.Sent)
            }
            return msg
        }

        for (i in broadcastMsgs.indices) {
            broadcastMsgs[i] = updated(broadcastMsgs[i])
        }
        for (messages in dmMsgs.values) {
            for (i in messages.indices) {
                messages[i] = updated(messages[i])
            }
        }
        return changed
    }

    private fun upsertPrivateSent(targetPid: Int, text: String, key: IdempotencyKey) {
        val list = dmMsgs.getOrPut(targetPid) { mutableListOf() }
        val idx = list.indexOfLast { it.isMine && it.key == key }
            .takeIf { it >= 0 }
            ?: list.indexOfLast {
                it.isMine &&
                    it.text == text &&
                    it.key == null &&
                    it.delivery != DeliveryState.Failed
            }
        val now = System.currentTimeMillis()
        val sent = ChatMessage(
            convId = targetPid,
            fromPid = identity.publicId,
            fromNick = node.nick,
            text = text,
            isMine = true,
            tsMs = now,
            key = key,
            expiresAtMs = now + Constants.BROADCAST_REPLAY_MS,
            delivery = outboundDelivery()
        )
        if (idx >= 0) {
            val pending = list[idx]
            list[idx] = sent.copy(
                tsMs = pending.tsMs,
                expiresAtMs = pending.expiresAtMs,
                delivery = if (pending.delivery == DeliveryState.Failed) DeliveryState.Failed else sent.delivery
            )
        } else {
            appendDm(targetPid, sent)
        }
    }

    fun broadcast(text: String) { synchronized(nodeLock) {
        if (this::node.isInitialized) {
            node.broadcastText(text)   // emit() records the local echo + publishes
        }
    }}

    /** Open/continue a private DM to a peer by its public_id (fingerprint). No passkey (v3). */
    fun sendDm(targetPid: Int, text: String) { synchronized(nodeLock) {
        if (this::node.isInitialized) {
            val hadSession = node.hasSession(targetPid)
            if (!hadSession) {
                val now = System.currentTimeMillis()
                appendDm(targetPid, ChatMessage(
                    convId = targetPid,
                    fromPid = identity.publicId,
                    fromNick = node.nick,
                    text = text,
                    isMine = true,
                    tsMs = now,
                    key = null,
                    expiresAtMs = now + Constants.BROADCAST_REPLAY_MS,
                    delivery = DeliveryState.Pending
                ))
            }
            try { node.sendPrivate(targetPid, text) } catch (t: Throwable) { Log.w(TAG, "sendDm: $t") }
            publishState()
        }
    }}

    fun resetSystem() { synchronized(nodeLock) {
        scanner?.stop()
        scanner = null
        advertiser?.stop()
        advertiser = null
        gattClient?.disconnectAll()
        gattClient = null
        gattServer?.stop()
        gattServer = null
        tracker.clear()
        activeLinks.clear()
        mutableEvents.clear()
        broadcastMsgs.clear()
        dmMsgs.clear()

        Identity.reset(this)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(PREF_NICK).apply()
        identity = Identity.loadOrCreate(this)
        val nick = currentNick(identity)
        node = SafeMeshNode(nick = nick, identity = identity, io = this)
        Log.i(TAG, "system reset: nick=$nick public_id=${identity.publicIdHex}")
        publishState()
        startBle(nick)
    }}

    fun setNickname(raw: String) { synchronized(nodeLock) {
        if (!this::node.isInitialized || !this::identity.isInitialized) return
        val nick = sanitizeNick(raw).ifBlank { defaultNick(identity) }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_NICK, nick)
            .apply()
        node.nick = nick
        publishState()
    }}

    fun publicIdHex(): String = synchronized(nodeLock) {
        if (this::identity.isInitialized) identity.publicIdHex else "<init…>"
    }
    /** Raw key-derived public_id; UI renders the fingerprint tag from THIS, not the nick. */
    fun publicId(): Int = synchronized(nodeLock) {
        if (this::identity.isInitialized) identity.publicId else 0
    }
    fun nickname(): String = synchronized(nodeLock) {
        if (this::node.isInitialized) node.nick else ""
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotif())
        identity = Identity.loadOrCreate(this)

        // Nickname: derive a short stable nick from public_id for the mockup.
        // (A future build can let the user set this via the UI.)
        val nick = currentNick(identity)

        node = SafeMeshNode(nick = nick, identity = identity, io = this)
        Log.i(TAG, "node ready: nick=$nick public_id=${identity.publicIdHex}")
        synchronized(nodeLock) { publishState() }

        startBle(nick)
        startTickLoop()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        nodeScope.cancel()
        scanner?.stop()
        advertiser?.stop()
        gattClient?.disconnectAll()
        gattServer?.stop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ----- BLE bring-up --------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startBle(nick: String) {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter ?: run {
            Log.w(TAG, "No Bluetooth adapter")
            return
        }
        gattServer = GattServer(
            ctx = this,
            onInbound = { linkId, frame -> nodeScope.launch { synchronized(nodeLock) { node.receiveFrame(linkId, frame) } } },
            onLinkUp = { linkId -> activeLinks[linkId] = Unit; nodeScope.launch { synchronized(nodeLock) { node.onLinkUp(linkId, rxHint = 0) } } },
            onLinkDown = { linkId -> activeLinks.remove(linkId); nodeScope.launch { synchronized(nodeLock) { node.onLinkDown(linkId, "central disconnected") } } }
        ).also { it.start() }

        adapter.bluetoothLeAdvertiser?.let { adv ->
            advertiser = Advertiser(adv).also { it.start(sanitizeName("SM-$nick")) }
        }

        gattClient = GattClient(
            ctx = this,
            tracker = tracker,
            onInbound = { linkId, frame -> nodeScope.launch { synchronized(nodeLock) { node.receiveFrame(linkId, frame) } } },
            onLinkUp = { linkId, rxHint -> activeLinks[linkId] = Unit; nodeScope.launch { synchronized(nodeLock) { node.onLinkUp(linkId, rxHint) } } },
            onLinkDown = { linkId -> activeLinks.remove(linkId); nodeScope.launch { synchronized(nodeLock) { node.onLinkDown(linkId, "peripheral disconnected") } } }
        )

        adapter.bluetoothLeScanner?.let { ls ->
            scanner = Scanner(ls, nodeScope) { result ->
                onScanResult(result)
            }.also { it.start() }
        }
    }

    private fun onScanResult(result: ScanResult) {
        val device = result.device
        // Bitchat dial gate: don't re-dial a peer we already have an edge to.
        // ConnectionTracker handles both that and attempt throttling.
        gattClient?.connectIfEligible(device)
    }

    private fun startTickLoop() {
        tickJob = nodeScope.launch {
            while (isActive) {
                try {
                    synchronized(nodeLock) {
                        node.tick()
                        if (refreshOutboundDelivery()) publishState()
                    }
                } catch (t: Throwable) { Log.w(TAG, "tick: $t") }
                delay(Constants.ACK_GOSSIP_MS)
            }
        }
    }

    // ----- SafeMeshNode.NodeIo --------------------------------------------

    override fun sendFrameOnLink(linkId: String, frame: ByteArray): Boolean {
        return sendFramesOnLink(linkId, listOf(frame), priority = false)
    }

    override fun sendFramesOnLink(linkId: String, frames: List<ByteArray>, priority: Boolean): Boolean {
        return if (linkId.startsWith("peripheral:")) {
            gattClient?.write(linkId, frames, priority) == true
        } else if (linkId.startsWith("central:")) {
            gattServer?.notify(linkId, frames, priority) == true
        } else {
            false
        }
    }

    override fun activeLinkIds(): List<String> = activeLinks.keys.toList()

    override fun emit(event: SafeMeshNode.MeshEvent) {
        // emit() is called from inside `synchronized(nodeLock) { node.xxx() }`,
        // so we already hold nodeLock here. Append directly (no extra synchronized).
        mutableEvents += event
        while (mutableEvents.size > 200) mutableEvents.removeAt(0)

        // Distribute into the conversation model the UI renders.
        val self = if (this::identity.isInitialized) identity.publicId else 0
        val now = System.currentTimeMillis()
        when (event) {
            is SafeMeshNode.MeshEvent.Broadcast -> appendBroadcast(ChatMessage(
                convId = BROADCAST_CONV_ID,
                fromPid = event.fromPid,
                fromNick = event.fromNick,
                text = event.text,
                isMine = event.fromPid == self,
                tsMs = now,
                key = event.key,
                expiresAtMs = now + Constants.BROADCAST_REPLAY_MS,
                delivery = if (event.fromPid == self) outboundDelivery() else DeliveryState.Received
            ).also {
                if (event.fromPid != self) notifyMessage("Broadcast", event.fromNick, event.text)
            })
            is SafeMeshNode.MeshEvent.PrivateMessage -> {
                appendDm(event.fromPid, ChatMessage(
                    convId = event.fromPid,
                    fromPid = event.fromPid,
                    fromNick = event.fromNick,
                    text = event.text,
                    isMine = false,   // receive-only; outbound is recorded in sendDm()
                    tsMs = now,
                    expiresAtMs = now + Constants.BROADCAST_REPLAY_MS,
                    delivery = DeliveryState.Received
                ))
                notifyMessage("Direct message", event.fromNick, event.text)
            }
            is SafeMeshNode.MeshEvent.PrivateSent -> upsertPrivateSent(event.targetPid, event.text, event.key)
            is SafeMeshNode.MeshEvent.AckReceived -> {
                if (!markAck(event.acked, event.byPid)) return
            }
            // Peer presence / acks are not chat bubbles; the drawer uses node.knownPeers().
            is SafeMeshNode.MeshEvent.PeerSeen,
            is SafeMeshNode.MeshEvent.Disconnect,
            is SafeMeshNode.MeshEvent.PrivateDropped -> { /* no chat bubble */ }
        }
        publishState()
    }

    // ----- Foreground notification ----------------------------------------

    private fun buildNotif(): Notification {
        ensureNotificationChannels()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(appPendingIntent())
            .setOngoing(true)
            .build()
    }

    private fun notifyMessage(kind: String, fromNick: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        ensureNotificationChannels()
        val cleanText = text.replace('\n', ' ').take(140)
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = Notification.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle("$kind from ${fromNick.ifBlank { "anon" }}")
            .setContentText(cleanText)
            .setStyle(Notification.BigTextStyle().bigText(cleanText))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(appPendingIntent())
            .setAutoCancel(true)
            .build()
        mgr.notify(nextMessageNotifId++, notif)
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mesh = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_mesh),
            NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(mesh)

        val messages = NotificationChannel(MESSAGE_CHANNEL_ID, getString(R.string.notif_channel_messages),
            NotificationManager.IMPORTANCE_HIGH)
        messages.description = getString(R.string.notif_channel_messages_desc)
        mgr.createNotificationChannel(messages)
    }

    private fun appPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
    }

    private fun currentNick(identity: Identity): String {
        val stored = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_NICK, null)
        return sanitizeNick(stored.orEmpty()).ifBlank { defaultNick(identity) }
    }

    private fun sanitizeNick(raw: String): String {
        val trimmed = raw.trim()
        val out = StringBuilder()
        var bytes = 0
        for (ch in trimmed) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + size > 8) break
            if (!ch.isISOControl()) {
                out.append(ch)
                bytes += size
            }
        }
        return out.toString()
    }

    private fun defaultNick(identity: Identity): String =
        "A" + "%07x".format(identity.publicId and 0xFFFFFFF.toInt())

    private fun sanitizeName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '-' }.take(15)

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "safemesh-mesh"
        const val MESSAGE_CHANNEL_ID = "safemesh-messages"
        private const val PREFS_NAME = "safemesh-settings"
        private const val PREF_NICK = "nick"
        private const val TAG = "SM/Service"
        private const val BROADCAST_CONV_ID = 0
    }
}
