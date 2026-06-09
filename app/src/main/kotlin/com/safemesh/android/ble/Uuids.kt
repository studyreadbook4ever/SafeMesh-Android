package com.safemesh.android.ble

import java.util.UUID

/**
 * Wire UUIDs — MUST equal Rust `types.rs::SAFEMESH_*` exactly, otherwise
 * Android and Linux nodes can't discover each other.
 */
object Uuids {
    val SERVICE: UUID  = UUID.fromString("7c42f100-8f30-4b7a-bd4f-6d8e8f4a0001")
    val FAST: UUID     = UUID.fromString("7c42f101-8f30-4b7a-bd4f-6d8e8f4a0001")  // broadcast/ACK/notify
    val RELIABLE: UUID = UUID.fromString("7c42f102-8f30-4b7a-bd4f-6d8e8f4a0001")  // control/DM/KEX
    val CCCD: UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")  // standard CCCD
}
