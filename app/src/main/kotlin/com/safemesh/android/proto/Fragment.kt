package com.safemesh.android.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * SMF2 fragmenter / reassembler — byte-compat with Rust `fragment.rs` (post Phase 1).
 *
 * Frame layout (8-byte header + chunk):
 *   "S2" (2) | msg_id (u16 BE) | idx (u8) | count (u8) | total_len (u16 BE) | chunk
 *
 * Reassembler drops incomplete partials after FRAG_REASSEMBLY_TIMEOUT_MS and
 * caps concurrent partials, so one lost GATT fragment cannot poison a msg_id
 * slot forever. msg_id is a process-monotonic counter, avoiding the
 * random-id collisions that could merge unrelated streams.
 */
object Frag {
    const val HEADER_LEN: Int = 8
    const val MIN_GATT_FRAME_LEN: Int = HEADER_LEN + 1
    const val FRAG_REASSEMBLY_TIMEOUT_MS: Long = 10_000
    const val MAX_CONCURRENT_PARTIALS: Int = 64

    private val msgIdCounter = AtomicInteger(0)
    fun nextMsgId(): Int = msgIdCounter.getAndIncrement() and 0xffff
}

class Fragmenter {
    fun fragment(packet: ByteArray, rxHint: Int): List<ByteArray> {
        val rx = if (rxHint <= 0) Constants.SAFE_DEFAULT_GATT_RX else rxHint
        require(rx >= Frag.MIN_GATT_FRAME_LEN) { "rxHint too small for SMF2 header" }
        require(packet.size <= 0xffff) { "packet too large for SMF2" }
        val chunk = rx - Frag.HEADER_LEN
        val count = ((packet.size + chunk - 1) / chunk).coerceAtLeast(1)
        require(count <= 255) { "too many SMF2 fragments" }
        val msgId = Frag.nextMsgId()
        val out = ArrayList<ByteArray>(count)
        for (idx in 0 until count) {
            val from = idx * chunk
            val to = minOf(from + chunk, packet.size)
            val part = packet.copyOfRange(from, to)
            val frame = ByteArray(Frag.HEADER_LEN + part.size)
            frame[0] = 'S'.code.toByte()
            frame[1] = '2'.code.toByte()
            ByteBuffer.wrap(frame, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(msgId.toShort())
            frame[4] = idx.toByte()
            frame[5] = count.toByte()
            ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.BIG_ENDIAN).putShort(packet.size.toShort())
            System.arraycopy(part, 0, frame, Frag.HEADER_LEN, part.size)
            out += frame
        }
        return out
    }
}

class Reassembler {
    private data class Partial(
        val totalLen: Int,
        val count: Int,
        val chunks: Array<ByteArray?>,
        val createdMs: Long
    )

    private val active = HashMap<Int, Partial>()

    /** Returns the fully-reassembled SafeMesh packet bytes when the last fragment arrives. */
    fun push(frame: ByteArray): ByteArray? {
        require(frame.size >= Frag.HEADER_LEN) { "SMF2 frame shorter than header" }
        require(frame[0] == 'S'.code.toByte() && frame[1] == '2'.code.toByte()) {
            "bad SMF2 magic"
        }
        val now = System.currentTimeMillis()
        evictStale(now)

        val msgId = (ByteBuffer.wrap(frame, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()) and 0xffff
        val idx = frame[4].toInt() and 0xff
        val count = frame[5].toInt() and 0xff
        val totalLen = (ByteBuffer.wrap(frame, 6, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()) and 0xffff
        require(count > 0 && idx < count) { "bad SMF2 idx/count" }

        // Self-heal on metadata mismatch (msg_id reused for a different message).
        active[msgId]?.let { p ->
            if (p.totalLen != totalLen || p.count != count) active.remove(msgId)
        }
        val p = active.getOrPut(msgId) {
            Partial(totalLen, count, arrayOfNulls(count), now)
        }
        p.chunks[idx] = frame.copyOfRange(Frag.HEADER_LEN, frame.size)
        return tryComplete(msgId)
    }

    private fun tryComplete(msgId: Int): ByteArray? {
        val p = active[msgId] ?: return null
        if (p.chunks.any { it == null }) return null
        val out = ByteArray(p.totalLen)
        var pos = 0
        for (c in p.chunks) {
            val bytes = c!!
            System.arraycopy(bytes, 0, out, pos, bytes.size)
            pos += bytes.size
        }
        active.remove(msgId)
        require(pos == p.totalLen) { "reassembled length mismatch" }
        return out
    }

    private fun evictStale(now: Long) {
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdMs >= Frag.FRAG_REASSEMBLY_TIMEOUT_MS) it.remove()
        }
        while (active.size > Frag.MAX_CONCURRENT_PARTIALS) {
            val oldest = active.minByOrNull { it.value.createdMs }?.key ?: break
            active.remove(oldest)
        }
    }
}
