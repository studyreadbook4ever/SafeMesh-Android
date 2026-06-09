package com.safemesh.android.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payload-layer encoders/decoders: broadcast text, control gossip, ACK bundle,
 * public ACK. Mirrors the byte layout of Rust `codec.rs`.
 */

/** 15-byte fixed-size record without hops; known peer records append +1 hops byte = 16B. */
data class ControlEntry(
    val nick: String,
    val publicId: Int,
    val flags: Int,
    val rx: Int,
    val hops: Int = 0
)

data class ControlTlv(val type: Byte, val value: ByteArray)

data class ControlPayload(val entries: List<ControlEntry>, val tlvs: List<ControlTlv>)

data class AckRecord(
    val acked: IdempotencyKey,
    val ackingNodeId: Int,
    val ackKind: Byte,
    val firstSeenAgeMs: Int
)

object Codec {
    // ------ Nicknames packed to 8 bytes (null-padded) ----------------------
    fun encodeNick8(nick: String): ByteArray {
        val out = ByteArray(8)
        val b = nick.toByteArray(Charsets.UTF_8)
        System.arraycopy(b, 0, out, 0, minOf(b.size, 8))
        return out
    }

    fun decodeNick8(src: ByteArray, offset: Int): String {
        var len = 0
        while (len < 8 && src[offset + len] != 0.toByte()) len++
        return String(src, offset, len, Charsets.UTF_8)
    }

    // ------ Broadcast text -------------------------------------------------
    fun encodeBroadcastText(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        require(1 + body.size <= 255) { "broadcast text too large" }
        val out = ByteArray(1 + body.size)
        out[0] = Constants.BCAST_TEXT
        System.arraycopy(body, 0, out, 1, body.size)
        return out
    }

    /** Parses a broadcast payload. Returns null if it's not a text broadcast. */
    fun decodeBroadcastText(payload: ByteArray): String? {
        if (payload.isEmpty() || payload[0] != Constants.BCAST_TEXT) return null
        return String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    // ------ Control gossip -------------------------------------------------
    /**
     * Local 15B entry: nick[8] + publicId(BE 4) + flags(BE 2) + rx(1).
     * Known peers append +1 hops byte (16B).
     */
    private fun encodeEntryWithoutHops(e: ControlEntry, out: ByteArray, off: Int) {
        System.arraycopy(encodeNick8(e.nick), 0, out, off, 8)
        ByteBuffer.wrap(out, off + 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(e.publicId)
        ByteBuffer.wrap(out, off + 12, 2).order(ByteOrder.BIG_ENDIAN).putShort(e.flags.toShort())
        out[off + 14] = (e.rx and 0xff).toByte()
    }

    fun encodeControlPayload(local: ControlEntry, known: List<ControlEntry>): ByteArray {
        val sb = ByteArrayBuilder()
        val localBytes = ByteArray(15)
        encodeEntryWithoutHops(local, localBytes, 0)
        sb.append(localBytes)
        for (k in known) {
            if (sb.size + 16 > 255) break
            val rec = ByteArray(16)
            encodeEntryWithoutHops(k, rec, 0)
            rec[15] = (k.hops and 0xff).toByte()
            sb.append(rec)
        }
        return sb.toByteArray()
    }

    /** Decode control payload (with optional TLVs prefixed by 0xff marker). */
    fun decodeControlPayload(payload: ByteArray, baseHops: Int): ControlPayload {
        require(payload.size >= 15) { "control payload shorter than local entry" }
        val entries = ArrayList<ControlEntry>()
        entries += ControlEntry(
            nick = decodeNick8(payload, 0),
            publicId = ByteBuffer.wrap(payload, 8, 4).order(ByteOrder.BIG_ENDIAN).int,
            flags = (ByteBuffer.wrap(payload, 12, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()) and 0xffff,
            rx = payload[14].toInt() and 0xff,
            hops = maxOf(baseHops, 1)
        )
        var off = 15
        while (off < payload.size) {
            if (payload[off] == Constants.CONTROL_TLV_MARKER) { off += 1; break }
            if (off + 16 > payload.size) break
            entries += ControlEntry(
                nick = decodeNick8(payload, off),
                publicId = ByteBuffer.wrap(payload, off + 8, 4).order(ByteOrder.BIG_ENDIAN).int,
                flags = (ByteBuffer.wrap(payload, off + 12, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()) and 0xffff,
                rx = payload[off + 14].toInt() and 0xff,
                hops = (baseHops + (payload[off + 15].toInt() and 0xff)).coerceAtMost(255)
            )
            off += 16
        }
        val tlvs = ArrayList<ControlTlv>()
        while (off + 2 <= payload.size) {
            val typ = payload[off]; val len = payload[off + 1].toInt() and 0xff
            off += 2
            if (off + len > payload.size) break
            tlvs += ControlTlv(typ, payload.copyOfRange(off, off + len))
            off += len
        }
        return ControlPayload(entries, tlvs)
    }

    // ------ ACK bundle / public ACK ---------------------------------------
    fun encodeAckBundle(records: List<AckRecord>): ByteArray {
        val n = minOf(records.size, Constants.MAX_ACK_BUNDLE_RECORDS)
        val out = ByteArrayBuilder()
        out.append(byteArrayOf(Constants.ACK_BUNDLE, n.toByte()))
        for (i in 0 until n) {
            val r = records[i]
            out.append(r.acked.toBytes12())
            val tail = ByteArray(7)
            ByteBuffer.wrap(tail, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(r.ackingNodeId)
            tail[4] = r.ackKind
            ByteBuffer.wrap(tail, 5, 2).order(ByteOrder.BIG_ENDIAN).putShort(r.firstSeenAgeMs.toShort())
            out.append(tail)
        }
        return out.toByteArray()
    }

    fun decodeAckBundle(payload: ByteArray): List<AckRecord> {
        require(payload.size >= 2 && payload[0] == Constants.ACK_BUNDLE) { "bad ack bundle" }
        val count = payload[1].toInt() and 0xff
        var off = 2
        val out = ArrayList<AckRecord>(count)
        repeat(count) {
            require(off + 19 <= payload.size) { "truncated ack bundle" }
            val key = IdempotencyKey.fromBytes12(payload, off)
            val acking = ByteBuffer.wrap(payload, off + 12, 4).order(ByteOrder.BIG_ENDIAN).int
            val kind = payload[off + 16]
            val age = (ByteBuffer.wrap(payload, off + 17, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()) and 0xffff
            out += AckRecord(key, acking, kind, age)
            off += 19
        }
        return out
    }

    fun encodeReceiptAck(kind: Byte, acked: IdempotencyKey): ByteArray {
        require(kind == Constants.ACK_PUBLIC || kind == Constants.ACK_PRIVATE) { "bad ack kind" }
        val out = ByteArray(13)
        out[0] = kind
        System.arraycopy(acked.toBytes12(), 0, out, 1, 12)
        return out
    }

    fun decodeReceiptAck(payload: ByteArray): Pair<Byte, IdempotencyKey> {
        require(payload.size == 13 &&
            (payload[0] == Constants.ACK_PUBLIC || payload[0] == Constants.ACK_PRIVATE)) { "bad receipt ack" }
        return payload[0] to IdempotencyKey.fromBytes12(payload, 1)
    }

    fun encodePublicAck(acked: IdempotencyKey): ByteArray =
        encodeReceiptAck(Constants.ACK_PUBLIC, acked)

    fun decodePublicAck(payload: ByteArray): IdempotencyKey {
        val (kind, key) = decodeReceiptAck(payload)
        require(kind == Constants.ACK_PUBLIC) { "bad public ack" }
        return key
    }
}

/** Tiny growable byte buffer to keep encoders concise. */
private class ByteArrayBuilder {
    private val out = java.io.ByteArrayOutputStream(64)
    val size: Int get() = out.size()
    fun append(b: ByteArray) { out.write(b) }
    fun toByteArray(): ByteArray = out.toByteArray()
}
