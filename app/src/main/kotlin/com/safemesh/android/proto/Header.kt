package com.safemesh.android.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 24-byte SafeMesh packet header. byte-for-byte identical to Rust
 * `codec::MeshHeader::{encode,decode}`.
 *
 * ```
 * 0:        (flag<<6) | (ttl & 0x07)
 * 1:        patch_version (=2)
 * 2:        header_len (=24)
 * 3:        payload_len (u8)
 * 4..12:    timestamp_ms (u64 BE)
 * 12..16:   sender_id (u32 BE)
 * 16..18:   epoch (u16 BE)
 * 18..24:   seq (u48 BE)
 * ```
 */
data class Header(
    val flag: Int,
    val ttl: Int,
    val payloadLen: Int,
    val timestampMs: Long,
    val senderId: Int,
    val epoch: Int,
    val seq: Long
) {
    init {
        require(flag in 0..0b11) { "flag must be 2 bits" }
        require(ttl in 0..Constants.MAX_TTL) { "ttl 0..7" }
        require(payloadLen in 0..255) { "payload_len 0..255" }
        require(seq and 0xFFFF_FFFF_FFFFL == seq) { "seq must be u48" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(Constants.HEADER_LEN)
        out[0] = (((flag and 0b11) shl 6) or (ttl and 0x07)).toByte()
        out[1] = Constants.PATCH_VERSION
        out[2] = Constants.HEADER_LEN.toByte()
        out[3] = payloadLen.toByte()
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.position(4); buf.putLong(timestampMs)
        buf.position(12); buf.putInt(senderId)
        buf.position(16); buf.putShort(epoch.toShort())
        val seq6 = IdempotencyKey.u48ToBytes(seq)
        System.arraycopy(seq6, 0, out, 18, 6)
        return out
    }

    /**
     * AAD form for AEAD private payloads. TTL is zeroed (decremented per hop)
     * and payload_len is zeroed (only known after encryption). Everything else
     * is authenticated. Matches Rust `MeshHeader::aad_for_private`.
     */
    fun aadForPrivate(): ByteArray = copy(ttl = 0, payloadLen = 0).encode()

    fun idempotencyKey(): IdempotencyKey = IdempotencyKey(senderId, epoch, seq)

    companion object {
        fun decode(src: ByteArray, offset: Int = 0): Header {
            require(src.size - offset >= Constants.HEADER_LEN) {
                "input shorter than 24-byte header"
            }
            val b0 = src[offset].toInt() and 0xff
            val flag = b0 ushr 6
            val ttl = b0 and 0x07
            val patch = src[offset + 1]
            val hlen = src[offset + 2].toInt() and 0xff
            val plen = src[offset + 3].toInt() and 0xff
            require(patch == Constants.PATCH_VERSION) { "unsupported patch $patch" }
            require(hlen == Constants.HEADER_LEN) { "bad header_len $hlen" }
            require(ttl <= Constants.MAX_TTL) { "ttl > 7" }
            val buf = ByteBuffer.wrap(src, offset, Constants.HEADER_LEN).order(ByteOrder.BIG_ENDIAN)
            buf.position(offset + 4); val ts = buf.long
            buf.position(offset + 12); val sender = buf.int
            buf.position(offset + 16); val epoch = (buf.short.toInt()) and 0xffff
            val seq = IdempotencyKey.bytesToU48(src, offset + 18)
            return Header(flag, ttl, plen, ts, sender, epoch, seq)
        }
    }
}
