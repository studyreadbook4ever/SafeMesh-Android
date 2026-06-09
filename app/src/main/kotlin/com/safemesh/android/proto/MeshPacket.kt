package com.safemesh.android.proto

/** Header + payload. Encode to a byte stream and pass to the Fragmenter. */
data class MeshPacket(val header: Header, val payload: ByteArray) {
    fun encode(): ByteArray {
        val hdr = header.copy(payloadLen = payload.size).encode()
        return hdr + payload
    }
    companion object {
        fun decode(src: ByteArray): MeshPacket {
            val h = Header.decode(src, 0)
            val need = Constants.HEADER_LEN + h.payloadLen
            require(src.size == need) { "bad packet length: got ${src.size}, need $need" }
            val payload = src.copyOfRange(Constants.HEADER_LEN, src.size)
            return MeshPacket(h, payload)
        }
    }
}
