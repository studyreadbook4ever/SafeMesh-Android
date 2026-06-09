package com.safemesh.android.ble

/**
 * Small, testable single-flight frame queue used by both GATT client writes
 * and GATT server notifications.
 */
internal class FrameTxQueue(
    private val maxQueuedFrames: Int
) {
    data class InFlight(
        val frame: ByteArray,
        val token: Long
    )

    private val queue = ArrayDeque<ByteArray>()
    private var nextToken = 0L

    var inFlight: InFlight? = null
        private set
    var consecutiveFailures: Int = 0
        private set

    fun enqueue(frames: List<ByteArray>, priority: Boolean) {
        if (priority) {
            frames.asReversed().forEach { queue.addFirst(it.copyOf()) }
        } else {
            frames.forEach { queue.addLast(it.copyOf()) }
        }
        while (queue.size > maxQueuedFrames) {
            queue.removeLast()
        }
    }

    fun startNext(): InFlight? {
        if (inFlight != null || queue.isEmpty()) return null
        val started = InFlight(queue.removeFirst(), ++nextToken)
        inFlight = started
        return started
    }

    fun complete(token: Long?, success: Boolean): Boolean {
        val current = inFlight ?: return false
        if (token != null && current.token != token) return false
        inFlight = null
        if (success) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures += 1
        }
        return true
    }

    fun hasInFlight(): Boolean = inFlight != null

    fun clear() {
        queue.clear()
        inFlight = null
        consecutiveFailures = 0
    }
}
