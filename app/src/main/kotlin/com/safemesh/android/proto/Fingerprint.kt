package com.safemesh.android.proto

/**
 * Human-readable identity fingerprint derived from `public_id`.
 *
 * SECURITY INVARIANT — the fingerprint tag MUST be rendered from the
 * system-held binary `public_id` (an Int = SHA-256(static X25519 pubkey)[0..4];
 * see [Crypto.publicIdFromIdentity]), and NEVER parsed out of the user-editable
 * nickname. A user can type "#a1b2" into their nick, but that is just nick text;
 * the tag below is computed from the key-derived public_id and is shown as a
 * separate, visually-distinct token. This is what defeats nickname impersonation
 * (problem P1) the way the professor suggested — a Blizzard/Riot-style name#tag.
 *
 * Display convention mirrors the Rust side `format!("{:08x}", public_id)`
 * (safemesh_cli.rs / error.rs), so a node shows the same identifier on Linux CLI
 * and Android.
 *
 * Known limits to harden before this is a real security claim
 * (see docs/fingerprint-security-analysis.md):
 *  - [short] (16-bit) is for screen density only; use [full] (32-bit) for any
 *    actual identity matching / verification.
 *  - public_id is only 32 bits, so ~2^32 key-grinding can forge a colliding tag.
 *    A hardened build should expose a longer fingerprint (>= 64-bit).
 *  - The displayed tag is only trustworthy once the system re-derives a REMOTE
 *    peer's public_id from that peer's 32-byte identity key. That requires the
 *    key to travel in control gossip / KEX, which this mockup does not do yet —
 *    today a remote public_id is self-asserted.
 */
object Fingerprint {

    /** Short display tag from the top 16 bits, e.g. "#a1b2". Density only. */
    fun short(publicId: Int): String =
        "#%04x".format((publicId ushr 16) and 0xFFFF)

    /** Full 32-bit tag, e.g. "#a1b2c3d4". Use this for verification / matching. */
    fun full(publicId: Int): String =
        "#%08x".format(publicId)

    /** "nick#a1b2" — nick + short tag; the tag always comes from public_id, never the nick. */
    fun nameWithShortTag(nick: String, publicId: Int): String =
        nick + short(publicId)
}
