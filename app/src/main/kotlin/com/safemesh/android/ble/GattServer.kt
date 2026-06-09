package com.safemesh.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Peripheral side: host the SafeMesh service so other devices can connect
 * to US and write/notify. Characteristics are intentionally **open** (no
 * encrypt / no permission gating) — pairing is handled by the OS via
 * Just Works when needed.
 *
 * Inbound writes arrive on either FAST or RELIABLE and are forwarded to the
 * supplied `onInbound` callback, keyed by `central:<remoteAddr>` so the
 * protocol engine treats inbound (we're peripheral) and outbound (we're
 * central) links to the same peer as distinct link IDs — matching the
 * Linux CLI's naming.
 */
class GattServer(
    private val ctx: Context,
    private val onInbound: (linkId: String, frame: ByteArray) -> Unit,
    private val onLinkUp: (linkId: String) -> Unit,
    private val onLinkDown: (linkId: String) -> Unit
) {
    private var server: BluetoothGattServer? = null
    private val fastChar = BluetoothGattCharacteristic(
        Uuids.FAST,
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply { addDescriptor(makeCccd()) }
    private val reliableChar = BluetoothGattCharacteristic(
        Uuids.RELIABLE,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply { addDescriptor(makeCccd()) }

    private val subscribers = HashMap<String, BluetoothDevice>()  // linkId -> device
    private data class NotifyState(
        val tx: FrameTxQueue = FrameTxQueue(MAX_QUEUED_FRAMES_PER_LINK)
    )
    private val notifyStates = HashMap<String, NotifyState>()
    private val handler = Handler(Looper.getMainLooper())

    private fun makeCccd() = BluetoothGattDescriptor(
        Uuids.CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    @SuppressLint("MissingPermission")
    fun start() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        server = mgr.openGattServer(ctx, cb)
        val svc = BluetoothGattService(Uuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(fastChar)
            addCharacteristic(reliableChar)
        }
        server?.addService(svc)
        Log.i(TAG, "GATT server started, SafeMesh service registered")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { server?.close() } catch (_: Throwable) {}
        server = null
        subscribers.clear()
        notifyStates.clear()
    }

    /** Notify a frame to a specific inbound subscriber link. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun notify(linkId: String, frame: ByteArray): Boolean {
        return notify(linkId, listOf(frame), priority = false)
    }

    /** Notify a packet's frames as one ordered batch; priority batches jump ahead of replay backlog. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun notify(linkId: String, frames: List<ByteArray>, priority: Boolean): Boolean {
        if (!subscribers.containsKey(linkId)) return false
        val state = notifyStates.getOrPut(linkId) { NotifyState() }
        state.tx.enqueue(frames, priority)
        drainNotifyQueueLocked(linkId, state)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drainNotifyQueueLocked(linkId: String, state: NotifyState) {
        val dev = subscribers[linkId] ?: return
        val started = state.tx.startNext() ?: return
        fastChar.value = started.frame
        val ok = server?.notifyCharacteristicChanged(dev, fastChar, false) == true
        if (!ok) {
            completeNotifyLocked(linkId, started.token, success = false)
            return
        }
        scheduleNotifyTimeout(linkId, started.token)
    }

    private fun scheduleNotifyTimeout(linkId: String, token: Long) {
        handler.postDelayed({
            synchronized(this) {
                val state = notifyStates[linkId]
                if (state?.tx?.inFlight?.token == token) {
                    completeNotifyLocked(linkId, token, success = false)
                }
            }
        }, NOTIFY_CALLBACK_TIMEOUT_MS)
    }

    private fun completeNotifyLocked(linkId: String, token: Long? = null, success: Boolean) {
        val state = notifyStates[linkId] ?: return
        if (!state.tx.complete(token, success)) return
        if (!success) {
            Log.w(TAG, "notification failed for $linkId; dropped one frame")
            if (state.tx.consecutiveFailures >= MAX_CONSECUTIVE_NOTIFY_FAILURES) {
                failNotifyLinkLocked(linkId, "too many notification failures")
                return
            }
            scheduleNotifyDrain(linkId)
            return
        }
        drainNotifyQueueLocked(linkId, state)
    }

    private fun scheduleNotifyDrain(linkId: String) {
        handler.postDelayed({
            synchronized(this) {
                val state = notifyStates[linkId] ?: return@synchronized
                drainNotifyQueueLocked(linkId, state)
            }
        }, NOTIFY_FAILURE_DRAIN_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun failNotifyLinkLocked(linkId: String, reason: String) {
        val state = notifyStates.remove(linkId) ?: return
        Log.w(TAG, "$reason for $linkId; dropping transport queue and waiting for a fresh subscribe")
        val dev = subscribers.remove(linkId)
        state.tx.clear()
        try {
            if (dev != null) server?.cancelConnection(dev)
        } catch (_: Throwable) {}
        onLinkDown(linkId)
    }

    private val cb = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val linkId = "central:${device.address}"
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "central connected: $linkId")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "central disconnected: $linkId status=$status")
                    synchronized(this@GattServer) {
                        subscribers.remove(linkId)
                        notifyStates.remove(linkId)
                        onLinkDown(linkId)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            val linkId = "central:${device.address}"
            onInbound(linkId, value)
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            // CCCD write: notify is safe only after the peer subscribes.
            val linkId = "central:${device.address}"
            val wantsNotify = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            synchronized(this@GattServer) {
                val wasSubscribed = subscribers.containsKey(linkId)
                if (wantsNotify) {
                    subscribers[linkId] = device
                    notifyStates.getOrPut(linkId) { NotifyState() }
                    if (!wasSubscribed) onLinkUp(linkId)
                } else if (wasSubscribed) {
                    subscribers.remove(linkId)
                    notifyStates.remove(linkId)
                    onLinkDown(linkId)
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val linkId = "central:${device.address}"
            synchronized(this@GattServer) {
                completeNotifyLocked(linkId, success = status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    companion object {
        private const val TAG = "SM/GattSrv"
        private const val MAX_QUEUED_FRAMES_PER_LINK: Int = 256
        private const val NOTIFY_CALLBACK_TIMEOUT_MS: Long = 1_500
        private const val NOTIFY_FAILURE_DRAIN_DELAY_MS: Long = 80
        private const val MAX_CONSECUTIVE_NOTIFY_FAILURES: Int = 8
    }
}
