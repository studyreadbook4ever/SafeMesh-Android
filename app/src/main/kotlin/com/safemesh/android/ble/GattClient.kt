package com.safemesh.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Central side: connect to a discovered peer, subscribe to FAST/RELIABLE
 * notifications, and accept outbound frame writes.
 *
 * Link IDs are `peripheral:<remoteAddr>` — same convention as the Linux CLI,
 * so an inbound (central:addr) and an outbound (peripheral:addr) to the same
 * peer are tracked as two separate logical links by the protocol engine.
 * Bitchat tolerates that redundancy; we forward to message-layer dedup
 * (idempotency keys) instead of force-disconnecting.
 */
class GattClient(
    private val ctx: Context,
    private val tracker: ConnectionTracker,
    private val onInbound: (linkId: String, frame: ByteArray) -> Unit,
    private val onLinkUp: (linkId: String, rxHint: Int) -> Unit,
    private val onLinkDown: (linkId: String) -> Unit
) {
    private enum class SetupOp { NONE, DESCRIPTOR, MTU }

    private data class Conn(
        val gatt: BluetoothGatt,
        var fast: BluetoothGattCharacteristic? = null,
        var reliable: BluetoothGattCharacteristic? = null,
        var mtu: Int = 23,
        val setupQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque(),
        var setupOp: SetupOp = SetupOp.NONE,
        var setupToken: Long = 0,
        var mtuAttempted: Boolean = false,
        var linkReady: Boolean = false,
        val tx: FrameTxQueue = FrameTxQueue(MAX_QUEUED_FRAMES_PER_LINK)
    )

    private val conns = ConcurrentHashMap<String, Conn>()  // linkId -> Conn
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connectIfEligible(device: BluetoothDevice) {
        val addr = device.address
        if (tracker.isAddressConnected(addr)) return  // already have an edge
        if (!tracker.beginAttempt(addr)) return       // throttled

        Log.i(TAG, "connecting to $addr")
        // autoConnect=false → direct connect; matches bitchat for first contact.
        device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        // gatt instance is delivered in onConnectionStateChange via the callback.
    }

    /** Write a frame outbound on a known link. Returns false if no writer. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun write(linkId: String, frame: ByteArray): Boolean {
        return write(linkId, listOf(frame), priority = false)
    }

    /** Write a packet's frames as one ordered batch; priority batches jump ahead of replay backlog. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun write(linkId: String, frames: List<ByteArray>, priority: Boolean): Boolean {
        val conn = conns[linkId] ?: return false
        if (!conn.linkReady) return false
        if (conn.fast == null && conn.reliable == null) return false
        conn.tx.enqueue(frames, priority)
        drainWriteQueueLocked(linkId, conn)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueueLocked(linkId: String, conn: Conn) {
        if (!conn.linkReady || conn.setupOp != SetupOp.NONE) return
        val started = conn.tx.startNext() ?: return
        val ch = conn.reliable ?: conn.fast ?: run {
            conn.tx.complete(started.token, success = false)
            failLinkLocked(linkId, "no writable characteristic")
            return
        }
        ch.writeType = if (ch == conn.reliable)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = started.frame
        val ok = conn.gatt.writeCharacteristic(ch)
        if (!ok) {
            conn.tx.complete(started.token, success = false)
            failLinkLocked(linkId, "writeCharacteristic returned false")
            return
        }
        scheduleWriteTimeout(linkId, conn.gatt, started.token)
        if (ch != conn.reliable) {
            scheduleWriteComplete(linkId, conn.gatt, started.token, WRITE_NO_RESPONSE_DRAIN_DELAY_MS, success = true)
        }
    }

    private fun scheduleWriteComplete(
        linkId: String,
        gatt: BluetoothGatt,
        token: Long,
        delayMs: Long,
        success: Boolean
    ) {
        handler.postDelayed({
            synchronized(this) {
                val conn = conns[linkId]
                if (conn?.gatt === gatt) completeWriteLocked(linkId, token, success)
            }
        }, delayMs)
    }

    private fun scheduleWriteTimeout(linkId: String, gatt: BluetoothGatt, token: Long) {
        handler.postDelayed({
            synchronized(this) {
                val conn = conns[linkId]
                if (conn?.gatt === gatt && conn.tx.inFlight?.token == token) {
                    failLinkLocked(linkId, "write callback timeout")
                }
            }
        }, WRITE_CALLBACK_TIMEOUT_MS)
    }

    private fun completeWriteLocked(linkId: String, token: Long? = null, success: Boolean) {
        val conn = conns[linkId] ?: return
        if (!conn.tx.complete(token, success)) return
        if (!success) {
            failLinkLocked(linkId, "write callback failed")
            return
        }
        drainWriteQueueLocked(linkId, conn)
    }

    @SuppressLint("MissingPermission")
    private fun failLinkLocked(linkId: String, reason: String) {
        val conn = conns[linkId] ?: return
        Log.w(TAG, "$reason for $linkId; dropping transport queue and reconnecting later")
        conns.remove(linkId)
        conn.tx.clear()
        val addr = linkId.removePrefix("peripheral:")
        tracker.removeEdge(addr)
        tracker.endAttempt(addr)
        try { conn.gatt.disconnect(); conn.gatt.close() } catch (_: Throwable) {}
        onLinkDown(linkId)
    }

    @SuppressLint("MissingPermission")
    private fun drainSetupLocked(linkId: String, conn: Conn) {
        if (conn.linkReady || conn.setupOp != SetupOp.NONE) return

        if (conn.setupQueue.isNotEmpty()) {
            val descriptor = conn.setupQueue.removeFirst()
            conn.setupOp = SetupOp.DESCRIPTOR
            conn.setupToken += 1
            val token = conn.setupToken
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = conn.gatt.writeDescriptor(descriptor)
            if (!ok) {
                Log.w(TAG, "CCCD write returned false for $linkId; continuing setup")
                completeDescriptorSetupLocked(linkId, conn.gatt, success = false)
            } else {
                scheduleSetupTimeout(linkId, conn.gatt, token)
            }
            return
        }

        if (!conn.mtuAttempted) {
            conn.mtuAttempted = true
            conn.setupOp = SetupOp.MTU
            conn.setupToken += 1
            val token = conn.setupToken
            val ok = conn.gatt.requestMtu(247)
            if (!ok) {
                Log.w(TAG, "requestMtu returned false for $linkId; using default MTU")
                completeMtuSetupLocked(linkId, conn.gatt, conn.mtu)
            } else {
                scheduleSetupTimeout(linkId, conn.gatt, token)
            }
            return
        }

        markLinkReadyLocked(linkId, conn, Constants_SAFE_DEFAULT_GATT_RX)
    }

    private fun scheduleSetupTimeout(linkId: String, gatt: BluetoothGatt, token: Long) {
        handler.postDelayed({
            synchronized(this) {
                val conn = conns[linkId] ?: return@synchronized
                if (conn.gatt !== gatt || conn.setupToken != token || conn.setupOp == SetupOp.NONE) return@synchronized
                when (conn.setupOp) {
                    SetupOp.DESCRIPTOR -> {
                        Log.w(TAG, "CCCD callback timeout for $linkId; continuing setup")
                        conn.setupOp = SetupOp.NONE
                        drainSetupLocked(linkId, conn)
                    }
                    SetupOp.MTU -> {
                        Log.w(TAG, "MTU callback timeout for $linkId; marking link ready with default MTU")
                        completeMtuSetupLocked(linkId, gatt, conn.mtu)
                    }
                    SetupOp.NONE -> Unit
                }
            }
        }, SETUP_CALLBACK_TIMEOUT_MS)
    }

    private fun completeDescriptorSetupLocked(linkId: String, gatt: BluetoothGatt, success: Boolean) {
        val conn = conns[linkId] ?: return
        if (conn.gatt !== gatt || conn.setupOp != SetupOp.DESCRIPTOR) return
        if (!success) Log.w(TAG, "CCCD write failed for $linkId; continuing setup")
        conn.setupOp = SetupOp.NONE
        drainSetupLocked(linkId, conn)
    }

    private fun completeMtuSetupLocked(linkId: String, gatt: BluetoothGatt, mtu: Int) {
        val conn = conns[linkId] ?: return
        if (conn.gatt !== gatt) return
        conn.setupOp = SetupOp.NONE
        conn.mtu = mtu
        val rxHint = (mtu - 3).coerceAtLeast(Constants_SAFE_DEFAULT_GATT_RX)
        markLinkReadyLocked(linkId, conn, rxHint)
    }

    private fun markLinkReadyLocked(linkId: String, conn: Conn, rxHint: Int) {
        if (!conn.linkReady) {
            conn.linkReady = true
            onLinkUp(linkId, rxHint)
        }
        drainWriteQueueLocked(linkId, conn)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(linkId: String) {
        val conn = conns.remove(linkId) ?: return
        try { conn.gatt.disconnect(); conn.gatt.close() } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        val ids = conns.keys.toList()
        ids.forEach { disconnect(it) }
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            val linkId = "peripheral:$addr"
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "client connected to $addr; discovering services")
                    synchronized(this@GattClient) {
                        conns[linkId] = Conn(gatt)
                        tracker.addEdge(addr, isClient = true, gatt = gatt)
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "client disconnected $addr status=$status")
                    synchronized(this@GattClient) {
                        conns.remove(linkId)
                        tracker.removeEdge(addr)
                        tracker.endAttempt(addr)
                        try { gatt.close() } catch (_: Throwable) {}
                        onLinkDown(linkId)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val addr = gatt.device.address
            val linkId = "peripheral:$addr"
            synchronized(this@GattClient) {
                val conn = conns[linkId] ?: return@synchronized
                if (conn.gatt !== gatt) return@synchronized
                val svc: BluetoothGattService? = gatt.getService(Uuids.SERVICE)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                    Log.w(TAG, "no usable SafeMesh service on $addr status=$status; disconnecting")
                    gatt.disconnect()
                    return@synchronized
                }
                conn.fast = svc.getCharacteristic(Uuids.FAST)
                conn.reliable = svc.getCharacteristic(Uuids.RELIABLE)
                if (conn.fast == null && conn.reliable == null) {
                    Log.w(TAG, "SafeMesh service has no writable characteristics on $addr; disconnecting")
                    gatt.disconnect()
                    return@synchronized
                }
                listOfNotNull(conn.fast, conn.reliable).forEach { ch ->
                    gatt.setCharacteristicNotification(ch, true)
                    ch.getDescriptor(Uuids.CCCD)?.let { d -> conn.setupQueue.addLast(d) }
                }
                drainSetupLocked(linkId, conn)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val linkId = "peripheral:${gatt.device.address}"
            synchronized(this@GattClient) {
                val conn = conns[linkId] ?: return@synchronized
                if (conn.gatt !== gatt) return@synchronized
                val safeMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else conn.mtu
                completeMtuSetupLocked(linkId, gatt, safeMtu)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val linkId = "peripheral:${gatt.device.address}"
            val data = characteristic.value ?: return
            onInbound(linkId, data.copyOf())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val linkId = "peripheral:${gatt.device.address}"
            synchronized(this@GattClient) {
                val conn = conns[linkId] ?: return@synchronized
                if (conn.gatt !== gatt) return@synchronized
                completeWriteLocked(linkId, success = status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val linkId = "peripheral:${gatt.device.address}"
            synchronized(this@GattClient) {
                completeDescriptorSetupLocked(linkId, gatt, success = status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    // Kept here so this file doesn't have to import the proto package for one int.
    private val Constants_SAFE_DEFAULT_GATT_RX: Int = 20

    companion object {
        private const val TAG = "SM/GattCli"
        private const val MAX_QUEUED_FRAMES_PER_LINK: Int = 256
        private const val WRITE_NO_RESPONSE_DRAIN_DELAY_MS: Long = 35
        private const val WRITE_CALLBACK_TIMEOUT_MS: Long = 1_500
        private const val SETUP_CALLBACK_TIMEOUT_MS: Long = 1_500
    }
}
