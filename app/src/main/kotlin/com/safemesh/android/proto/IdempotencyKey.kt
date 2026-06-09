package com.safemesh.android.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 12 bytes = senderId(4 BE) + epoch(2 BE) + seq(6 BE).
 * Mirrors `codec::IdempotencyKey`. `seq` only uses the low 48 bits.
 */
data class IdempotencyKey(
    val senderId: Int,   // u32 BE on the wire — we keep it as Int and mask when needed
    val epoch: Int,      // u16
    val seq: Long        // u48
) {
    fun toBytes12(): ByteArray {
        val out = ByteArray(12)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(senderId)
        buf.putShort(epoch.toShort())
        // u48 BE: write 6 high-to-low bytes of `seq`
        out[6] = ((seq ushr 40) and 0xff).toByte()
        out[7] = ((seq ushr 32) and 0xff).toByte()
        out[8] = ((seq ushr 24) and 0xff).toByte()
        out[9] = ((seq ushr 16) and 0xff).toByte()
        out[10] = ((seq ushr 8) and 0xff).toByte()
        out[11] = (seq and 0xff).toByte()
        return out
    }

    companion object {
        fun fromBytes12(src: ByteArray, offset: Int = 0): IdempotencyKey {
            require(src.size - offset >= 12) { "idempotency key needs 12 bytes" }
            val sender = ByteBuffer.wrap(src, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            val epoch = (ByteBuffer.wrap(src, offset + 4, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()) and 0xffff
            val seq = bytesToU48(src, offset + 6)
            return IdempotencyKey(sender, epoch, seq)
        }

        fun bytesToU48(src: ByteArray, offset: Int): Long {
            return ((src[offset].toLong() and 0xff) shl 40) or
                    ((src[offset + 1].toLong() and 0xff) shl 32) or
                    ((src[offset + 2].toLong() and 0xff) shl 24) or
                    ((src[offset + 3].toLong() and 0xff) shl 16) or
                    ((src[offset + 4].toLong() and 0xff) shl 8) or
                    (src[offset + 5].toLong() and 0xff)
        }

        fun u48ToBytes(v: Long): ByteArray = byteArrayOf(
            ((v ushr 40) and 0xff).toByte(),
            ((v ushr 32) and 0xff).toByte(),
            ((v ushr 24) and 0xff).toByte(),
            ((v ushr 16) and 0xff).toByte(),
            ((v ushr 8) and 0xff).toByte(),
            (v and 0xff).toByte()
        )
    }
}
