package com.safemesh.android.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTxQueueTest {
    @Test
    fun failedFrameDoesNotBlockNextFrame() {
        val queue = FrameTxQueue(maxQueuedFrames = 8)
        queue.enqueue(listOf(byteArrayOf(1), byteArrayOf(2)), priority = false)

        val first = requireStarted(queue)
        assertArrayEquals(byteArrayOf(1), first.frame)
        assertTrue(queue.complete(first.token, success = false))
        assertNull(queue.inFlight)
        assertEquals(1, queue.consecutiveFailures)

        val second = requireStarted(queue)
        assertArrayEquals(byteArrayOf(2), second.frame)
        assertTrue(queue.complete(second.token, success = true))
        assertEquals(0, queue.consecutiveFailures)
        assertNull(queue.inFlight)
    }

    @Test
    fun staleCompletionCannotClearCurrentInFlight() {
        val queue = FrameTxQueue(maxQueuedFrames = 8)
        queue.enqueue(listOf(byteArrayOf(1), byteArrayOf(2)), priority = false)

        val first = requireStarted(queue)
        assertTrue(queue.complete(first.token, success = false))
        val second = requireStarted(queue)

        assertFalse(queue.complete(first.token, success = true))
        assertArrayEquals(byteArrayOf(2), queue.inFlight?.frame)
        assertTrue(queue.complete(second.token, success = true))
    }

    @Test
    fun priorityFramesJumpAheadWithoutReversingPacketOrder() {
        val queue = FrameTxQueue(maxQueuedFrames = 8)
        queue.enqueue(listOf(byteArrayOf(10), byteArrayOf(11)), priority = false)
        queue.enqueue(listOf(byteArrayOf(1), byteArrayOf(2)), priority = true)

        val first = requireStarted(queue)
        assertArrayEquals(byteArrayOf(1), first.frame)
        assertTrue(queue.complete(first.token, success = true))

        val second = requireStarted(queue)
        assertArrayEquals(byteArrayOf(2), second.frame)
        assertTrue(queue.complete(second.token, success = true))

        val third = requireStarted(queue)
        assertArrayEquals(byteArrayOf(10), third.frame)
    }

    @Test
    fun queueDropsOldBacklogTailWhenFull() {
        val queue = FrameTxQueue(maxQueuedFrames = 2)
        queue.enqueue(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)), priority = false)

        val first = requireStarted(queue)
        assertArrayEquals(byteArrayOf(1), first.frame)
        assertTrue(queue.complete(first.token, success = true))

        val second = requireStarted(queue)
        assertArrayEquals(byteArrayOf(2), second.frame)
        assertTrue(queue.complete(second.token, success = true))

        assertNull(queue.startNext())
    }

    private fun requireStarted(queue: FrameTxQueue): FrameTxQueue.InFlight {
        val started = queue.startNext()
        assertNotNull(started)
        return started!!
    }
}
