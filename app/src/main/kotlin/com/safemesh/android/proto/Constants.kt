package com.safemesh.android.proto

/**
 * Wire constants — every value here is a 1:1 mirror of
 * `protocol_test/.../src/types.rs`. Changing one side without the other
 * breaks Android <-> Linux interop. Comments cite the Rust source name.
 */
object Constants {
    const val PATCH_VERSION: Byte = 2
    const val HEADER_LEN: Int = 24                   // bytes
    const val MAX_TTL: Int = 7
    const val DEFAULT_RX: Int = 255                  // "unknown / no local cap"
    const val SAFE_DEFAULT_GATT_RX: Int = 20         // ATT MTU 23 - 3 opcode

    // Periodic timers (ms) — match types.rs.
    const val BROADCAST_REPLAY_MS: Long = 60_000
    const val LAZY_CLEANUP_GRACE_MS: Long = 15_000
    const val LAZY_CLEANUP_MS: Long = BROADCAST_REPLAY_MS + LAZY_CLEANUP_GRACE_MS
    const val ENCRYPTED_RELAY_SPOOL_MS: Long = LAZY_CLEANUP_MS
    const val ENCRYPTED_RETRY_INITIAL_MS: Long = 180
    const val ENCRYPTED_RETRY_MAX_MS: Long = 1_500
    const val LINK_TX_SUPPRESS_MS: Long = ENCRYPTED_RETRY_MAX_MS
    const val SEEN_EXPIRY_MS: Long = 180_000
    const val ACK_GOSSIP_MS: Long = 150
    const val ACK_REPLAY_MS: Long = 5_000
    const val CONTROL_PERIOD_MS: Long = 2_000
    const val DISCONNECTED_PEER_GRACE_MS: Long = 75_000

    // Flag (top 2 bits of byte 0).
    const val FLAG_BROADCAST: Int = 0b00
    const val FLAG_CONTROL: Int   = 0b01
    const val FLAG_ACK: Int       = 0b10
    const val FLAG_PRIVATE: Int   = 0b11

    // Local flags advertised in control gossip (16-bit BE).
    const val LOCAL_FLAG_AES_GCM: Int = 1 shl 0
    const val LOCAL_FLAG_BATTERY_OPT_DISABLED: Int = 1 shl 1
    const val LOCAL_FLAG_POWER_SAVE: Int = 1 shl 2
    const val LOCAL_FLAG_PRIVATE_RELAY_ALLOWED: Int = 1 shl 3

    // Broadcast payload type byte (first byte after header).
    const val BCAST_TEXT: Byte       = 0x01
    const val BCAST_DISCONNECT: Byte = 0x02

    // Private payload type byte.
    const val PVT_KEX_INIT: Byte = 0x01
    const val PVT_KEX_RESP: Byte = 0x02
    const val PVT_DATA: Byte     = 0x03

    // ACK payload type byte.
    const val ACK_PUBLIC: Byte  = 0x01
    const val ACK_PRIVATE: Byte = 0x02
    const val ACK_BUNDLE: Byte  = 0x03

    // AEAD algorithm IDs.
    const val AEAD_AES_256_GCM: Byte         = 0x01
    const val AEAD_CHACHA20_POLY1305: Byte   = 0x02
    const val WRAP_AES_256_CBC_HMAC_SHA256: Byte = 0x11

    // Control TLV markers.
    const val CONTROL_TLV_MARKER: Byte = 0xff.toByte()
    const val TLV_LINK_METRIC: Byte    = 0x01
    const val TLV_SEEN_SUMMARY: Byte   = 0x02
    const val TLV_ACK_SUMMARY: Byte    = 0x03
    const val TLV_QUEUE_PRESSURE: Byte = 0x04

    const val MAX_ACK_BUNDLE_RECORDS: Int = 13  // 2 + 13*19 = 249 <= 255

    // 10-second epoch used inside the header (Rust codec::epoch_10s).
    fun epoch10s(timestampMs: Long): Int = ((timestampMs / 10_000) and 0xffff).toInt()
}
