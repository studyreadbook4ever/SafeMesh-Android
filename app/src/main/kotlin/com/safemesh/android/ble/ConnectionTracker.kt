package com.safemesh.android.ble

import android.bluetooth.BluetoothGatt
import java.util.concurrent.ConcurrentHashMap

/**
 * Bitchat-style connection tracking — port of the relevant bits of
 * `reference/bitchat-android/.../BluetoothConnectionTracker.kt`.
 *
 * Two duties:
 *   1. Dedup BEFORE connecting: don't redial a peer we already have a
 *      central/peripheral edge to. Cuts the "both sides dial each other"
 *      race that produced the Broken-pipe thrash on the Linux side.
 *   2. Attempt throttling: a recently-failed/cooling-down peer is skipped.
 *
 * Eviction by limits / RSSI is intentionally out of scope for the mockup —
 * the Linux node's primary-first rule (one outbound edge) already keeps
 * the topology sparse.
 */
class ConnectionTracker {
    /** A device we have an active edge to (either client or server side). */
    data class Edge(val address: String, val isClient: Boolean, val gatt: BluetoothGatt? = null)

    private data class Attempt(val attempts: Int, val lastAttemptMs: Long)

    private val connected = ConcurrentHashMap<String, Edge>()  // addr -> Edge
    private val pending = ConcurrentHashMap<String, Attempt>()

    // -- Connection state ----------------------------------------------------

    fun addEdge(addr: String, isClient: Boolean, gatt: BluetoothGatt? = null) {
        connected[addr] = Edge(addr, isClient, gatt)
        pending.remove(addr)
    }

    fun removeEdge(addr: String) {
        connected.remove(addr)
    }

    /** Any edge to this address (client OR server). */
    fun isAddressConnected(addr: String): Boolean = connected.containsKey(addr)

    fun connectedCount(): Int = connected.size

    fun edges(): List<Edge> = connected.values.toList()

    fun clear() {
        connected.clear()
        pending.clear()
    }

    // -- Throttling ---------------------------------------------------------

    /** Returns true if we are allowed to start a new client-side connect to `addr`. */
    fun isConnectAttemptAllowed(addr: String, now: Long = System.currentTimeMillis()): Boolean {
        val a = pending[addr] ?: return true
        val elapsed = now - a.lastAttemptMs
        // Bitchat: retry-delay must elapse, and respect max attempts.
        if (a.attempts >= MAX_ATTEMPTS && elapsed < RETRY_DELAY_MS * 2) return false
        return elapsed >= RETRY_DELAY_MS
    }

    /** Record a fresh attempt; returns false if we should not dial right now. */
    @Synchronized
    fun beginAttempt(addr: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isConnectAttemptAllowed(addr, now)) return false
        val prev = pending[addr]
        val attempts = if (prev != null && now - prev.lastAttemptMs > RETRY_DELAY_MS * 2) 1
            else (prev?.attempts ?: 0) + 1
        pending[addr] = Attempt(attempts, now)
        return true
    }

    fun endAttempt(addr: String) {
        pending.remove(addr)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS: Long = 5_000
    }
}
