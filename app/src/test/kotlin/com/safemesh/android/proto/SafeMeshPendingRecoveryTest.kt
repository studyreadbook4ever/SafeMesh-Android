package com.safemesh.android.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMeshPendingRecoveryTest {
    private class HarnessIo(
        private val localLinkId: String,
        private val peerIngressLinkId: String
    ) : SafeMeshNode.NodeIo {
        lateinit var peer: SafeMeshNode
        var linkUp: Boolean = true
        var acceptOutbound: Boolean = true
        var autoDeliver: Boolean = true
        var dropNextPrivateAck: Boolean = false
        val events = mutableListOf<SafeMeshNode.MeshEvent>()
        val ackAttemptKeys = mutableListOf<IdempotencyKey>()
        val publicAckBundleAttemptKeys = mutableListOf<IdempotencyKey>()
        val acceptedPacketKeys = mutableListOf<IdempotencyKey>()
        private val held = ArrayDeque<List<ByteArray>>()
        var acceptedPackets: Int = 0

        override fun sendFrameOnLink(linkId: String, frame: ByteArray): Boolean {
            return sendFramesOnLink(linkId, listOf(frame), priority = false)
        }

        override fun sendFramesOnLink(linkId: String, frames: List<ByteArray>, priority: Boolean): Boolean {
            if (!linkUp || linkId != localLinkId) return false
            val packet = decodePacket(frames)
            if (packet.header.flag == Constants.FLAG_ACK &&
                packet.payload.firstOrNull() == Constants.ACK_PRIVATE) {
                ackAttemptKeys += packet.header.idempotencyKey()
                if (dropNextPrivateAck) {
                    dropNextPrivateAck = false
                    return false
                }
            }
            if (packet.header.flag == Constants.FLAG_ACK &&
                packet.payload.firstOrNull() == Constants.ACK_BUNDLE) {
                publicAckBundleAttemptKeys += packet.header.idempotencyKey()
            }
            if (!acceptOutbound) return false
            acceptedPacketKeys += packet.header.idempotencyKey()
            val copied = frames.map { it.copyOf() }
            if (autoDeliver) {
                copied.forEach { peer.receiveFrame(peerIngressLinkId, it) }
            } else {
                held.addLast(copied)
            }
            acceptedPackets += 1
            return true
        }

        override fun activeLinkIds(): List<String> = if (linkUp) listOf(localLinkId) else emptyList()

        override fun emit(event: SafeMeshNode.MeshEvent) {
            events += event
        }

        fun takeHeld(): List<List<ByteArray>> {
            val out = held.toList()
            held.clear()
            return out
        }

        fun deliverHeld(frames: List<ByteArray>) {
            frames.forEach { peer.receiveFrame(peerIngressLinkId, it.copyOf()) }
        }

        private fun decodePacket(frames: List<ByteArray>): MeshPacket {
            val reassembler = Reassembler()
            for (frame in frames) {
                reassembler.push(frame)?.let { return MeshPacket.decode(it) }
            }
            error("packet frames did not reassemble")
        }
    }

    private data class PairHarness(
        val a: SafeMeshNode,
        val b: SafeMeshNode,
        val aIo: HarnessIo,
        val bIo: HarnessIo
    )

    private fun pairHarness(): PairHarness {
        val aIo = HarnessIo("a-b", "b-a")
        val bIo = HarnessIo("b-a", "a-b")
        val a = SafeMeshNode("A", Identity.ephemeral(), io = aIo)
        val b = SafeMeshNode("B", Identity.ephemeral(), io = bIo)
        aIo.peer = b
        bIo.peer = a
        a.onLinkUp("a-b", rxHint = 247)
        b.onLinkUp("b-a", rxHint = 247)
        return PairHarness(a, b, aIo, bIo)
    }

    @Test
    fun privateDmRecoversAfterInitialTransportRejectAndAcks() {
        val h = pairHarness()
        h.aIo.acceptOutbound = false

        h.a.sendPrivate(h.b.publicId, "hello after pending")
        assertTrue(h.bIo.events.filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>().isEmpty())

        Thread.sleep(Constants.ENCRYPTED_RETRY_INITIAL_MS + 80)
        h.aIo.acceptOutbound = true
        pump(h.a, h.b, 2_500)

        val received = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .firstOrNull { it.fromPid == h.a.publicId && it.text == "hello after pending" }
        assertNotNull("DM must deliver after a failed first enqueue recovers", received)

        val sent = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateSent>()
            .firstOrNull { it.targetPid == h.b.publicId && it.text == "hello after pending" }
        assertNotNull("sender must emit PrivateSent once the queued DM really goes out", sent)

        val acked = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>()
            .any { it.acked == sent!!.key && it.byPid == h.b.publicId }
        assertTrue("private ACK must reach the sender after recovered delivery", acked)
    }

    @Test
    fun privateAckRecoversAfterFirstAckTransportRejectWithoutChangingAckId() {
        val h = pairHarness()
        h.bIo.dropNextPrivateAck = true

        h.a.sendPrivate(h.b.publicId, "ack after pending")

        val sent = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateSent>()
            .firstOrNull { it.targetPid == h.b.publicId && it.text == "ack after pending" }
        assertNotNull("DM should be sent before testing ACK retry", sent)
        assertTrue(
            "first private ACK attempt should be intentionally dropped by the harness",
            h.aIo.events.filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>().none { it.acked == sent!!.key }
        )
        assertEquals("one private ACK attempt should have been dropped", 1, h.bIo.ackAttemptKeys.size)

        pump(h.a, h.b, 2_500)

        val acked = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>()
            .any { it.acked == sent!!.key && it.byPid == h.b.publicId }
        assertTrue("dropped private ACK must retry and reach sender", acked)
        assertEquals(
            "private ACK retry must reuse the same ACK packet id to avoid ACK relay storm",
            1,
            h.bIo.ackAttemptKeys.distinct().size
        )
    }

    @Test
    fun oneSidedPendingDoesNotBreakPeerSendingOrEstablishedSession() {
        val h = pairHarness()

        h.a.sendPrivate(h.b.publicId, "warmup")
        pump(h.a, h.b, 1_000)
        assertTrue("A should have an established session after warmup", h.a.hasSession(h.b.publicId))
        assertTrue("B should have an established session after warmup", h.b.hasSession(h.a.publicId))
        h.aIo.events.clear()
        h.bIo.events.clear()

        h.aIo.acceptOutbound = false
        h.a.sendPrivate(h.b.publicId, "temporarily pending from A")
        h.b.sendPrivate(h.a.publicId, "B still gets through")
        pump(h.a, h.b, 700)

        val reverseDelivered = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .any { it.fromPid == h.b.publicId && it.text == "B still gets through" }
        assertTrue("B->A DM must deliver while A has a pending A->B packet", reverseDelivered)
        assertTrue("A session must survive its own pending outbound", h.a.hasSession(h.b.publicId))
        assertTrue("B session must survive A's pending outbound", h.b.hasSession(h.a.publicId))

        h.aIo.acceptOutbound = true
        pump(h.a, h.b, 2_500)

        val pendingDelivered = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .any { it.fromPid == h.a.publicId && it.text == "temporarily pending from A" }
        assertTrue("A's pending DM must deliver after A outbound recovers", pendingDelivered)
    }

    @Test
    fun hardLinkDownPendingRecoversOnReconnectAndDoesNotBreakReverseDm() {
        val h = pairHarness()

        h.a.sendPrivate(h.b.publicId, "warmup before hard down")
        pump(h.a, h.b, 1_000)
        assertTrue("A should have a session before hard down", h.a.hasSession(h.b.publicId))
        assertTrue("B should have a session before hard down", h.b.hasSession(h.a.publicId))
        h.aIo.events.clear()
        h.bIo.events.clear()

        h.aIo.linkUp = false
        h.a.onLinkDown("a-b", "test hard down")
        val pendingKey = h.a.sendPrivate(h.b.publicId, "A pending across hard down")
        h.b.sendPrivate(h.a.publicId, "B still sends during A hard down")
        pump(h.a, h.b, 700)

        val reverseDelivered = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .any { it.fromPid == h.b.publicId && it.text == "B still sends during A hard down" }
        assertTrue("B->A DM must still deliver while A outbound link is down", reverseDelivered)
        assertTrue("A session must survive outbound link down", h.a.hasSession(h.b.publicId))
        assertTrue("B session must survive A outbound link down", h.b.hasSession(h.a.publicId))

        h.aIo.linkUp = true
        h.a.onLinkUp("a-b", rxHint = 247)
        pump(h.a, h.b, 2_500)

        val recovered = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .count { it.fromPid == h.a.publicId && it.text == "A pending across hard down" }
        assertEquals("A pending DM should deliver exactly once after reconnect", 1, recovered)
        assertEquals(
            "A pending packet should be accepted by the recovered writer exactly once",
            1,
            h.aIo.acceptedPacketKeys.count { it == pendingKey }
        )
    }

    @Test
    fun fullyDisconnectedPendingRecoversOnFreshLinkUpWithoutDuplicateDelivery() {
        val h = pairHarness()

        h.a.sendPrivate(h.b.publicId, "warmup before full disconnect")
        pump(h.a, h.b, 1_000)
        assertTrue(h.a.hasSession(h.b.publicId))
        assertTrue(h.b.hasSession(h.a.publicId))
        h.aIo.events.clear()
        h.bIo.events.clear()

        h.aIo.linkUp = false
        h.bIo.linkUp = false
        h.a.onLinkDown("a-b", "test full down")
        h.b.onLinkDown("b-a", "test full down")
        val pendingKey = h.a.sendPrivate(h.b.publicId, "A pending across full disconnect")
        pump(h.a, h.b, 700)
        assertTrue(h.bIo.events.filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>().isEmpty())
        assertTrue("A session should not be removed while disconnected", h.a.hasSession(h.b.publicId))
        assertTrue("B session should not be removed while disconnected", h.b.hasSession(h.a.publicId))

        h.aIo.linkUp = true
        h.bIo.linkUp = true
        h.a.onLinkUp("a-b", rxHint = 247)
        h.b.onLinkUp("b-a", rxHint = 247)
        pump(h.a, h.b, 2_500)

        val recovered = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .count { it.fromPid == h.a.publicId && it.text == "A pending across full disconnect" }
        assertEquals("full disconnect pending DM should deliver exactly once after reconnect", 1, recovered)
        assertEquals(
            "full disconnect pending packet should be accepted by the recovered writer exactly once",
            1,
            h.aIo.acceptedPacketKeys.count { it == pendingKey }
        )
    }

    @Test
    fun firstDmQueuedWithNoSessionAndNoTransportFlushesOnFirstLinkUp() {
        val h = pairHarness()
        h.aIo.linkUp = false
        h.bIo.linkUp = false
        h.a.onLinkDown("a-b", "start without transport")
        h.b.onLinkDown("b-a", "start without transport")
        h.aIo.events.clear()
        h.bIo.events.clear()

        h.a.sendPrivate(h.b.publicId, "first DM waits for transport")
        pump(h.a, h.b, 700)
        assertTrue("first DM should not deliver while there is no transport", h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .none { it.text == "first DM waits for transport" })
        assertTrue("A should not panic-create a broken session while offline", !h.a.hasSession(h.b.publicId))
        assertTrue("B should not panic-create a broken session while offline", !h.b.hasSession(h.a.publicId))

        h.aIo.linkUp = true
        h.bIo.linkUp = true
        h.a.onLinkUp("a-b", rxHint = 247)
        h.b.onLinkUp("b-a", rxHint = 247)
        pump(h.a, h.b, 3_000)

        val received = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .count { it.fromPid == h.a.publicId && it.text == "first DM waits for transport" }
        assertEquals("first DM should be delayed and delivered exactly once after first link up", 1, received)

        val sent = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateSent>()
            .firstOrNull { it.targetPid == h.b.publicId && it.text == "first DM waits for transport" }
        assertNotNull("delayed first DM should become a real PrivateSent packet", sent)
        assertTrue("delayed first DM should receive private ACK", h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>()
            .any { it.acked == sent!!.key && it.byPid == h.b.publicId })
    }

    @Test
    fun simultaneousFirstDmKexRaceConvergesAndDoesNotPoisonSession() {
        val h = pairHarness()
        h.aIo.autoDeliver = false
        h.bIo.autoDeliver = false
        h.aIo.events.clear()
        h.bIo.events.clear()

        h.a.sendPrivate(h.b.publicId, "A first during kex race")
        h.b.sendPrivate(h.a.publicId, "B first during kex race")
        assertTrue("race setup should start before either side has a session", !h.a.hasSession(h.b.publicId))
        assertTrue("race setup should start before either side has a session", !h.b.hasSession(h.a.publicId))

        drainHeld(h, rounds = 10)

        assertTrue("A session must converge after simultaneous KEX", h.a.hasSession(h.b.publicId))
        assertTrue("B session must converge after simultaneous KEX", h.b.hasSession(h.a.publicId))

        val aDelivered = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .count { it.fromPid == h.a.publicId && it.text == "A first during kex race" }
        val bDelivered = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
            .count { it.fromPid == h.b.publicId && it.text == "B first during kex race" }
        assertEquals("A's queued first DM must decrypt exactly once after KEX race", 1, aDelivered)
        assertEquals("B's queued first DM must decrypt exactly once after KEX race", 1, bDelivered)

        val aSent = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateSent>()
            .firstOrNull { it.targetPid == h.b.publicId && it.text == "A first during kex race" }
        val bSent = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.PrivateSent>()
            .firstOrNull { it.targetPid == h.a.publicId && it.text == "B first during kex race" }
        assertNotNull("A's queued DM should become a normal sent private data packet", aSent)
        assertNotNull("B's queued DM should become a normal sent private data packet", bSent)

        val aAcked = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>()
            .any { it.acked == aSent!!.key && it.byPid == h.b.publicId }
        val bAcked = h.bIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>()
            .any { it.acked == bSent!!.key && it.byPid == h.a.publicId }
        assertTrue("A's DM should get ACK after KEX race convergence", aAcked)
        assertTrue("B's DM should get ACK after KEX race convergence", bAcked)

        h.aIo.autoDeliver = true
        h.bIo.autoDeliver = true
        h.aIo.events.clear()
        h.bIo.events.clear()
        h.a.sendPrivate(h.b.publicId, "A followup after kex race")
        h.b.sendPrivate(h.a.publicId, "B followup after kex race")
        pump(h.a, h.b, 1_500)

        assertTrue(
            "A follow-up DM must still decrypt after race convergence",
            h.bIo.events.filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
                .any { it.fromPid == h.a.publicId && it.text == "A followup after kex race" }
        )
        assertTrue(
            "B follow-up DM must still decrypt after race convergence",
            h.aIo.events.filterIsInstance<SafeMeshNode.MeshEvent.PrivateMessage>()
                .any { it.fromPid == h.b.publicId && it.text == "B followup after kex race" }
        )
    }

    @Test
    fun broadcastAckIsFastBundledAndDoesNotRepeatAfterSuccess() {
        val h = pairHarness()

        val key = h.a.broadcastText("broadcast needs one fast ack")
        pump(h.a, h.b, 1_000)

        val acked = h.aIo.events
            .filterIsInstance<SafeMeshNode.MeshEvent.AckReceived>()
            .any { it.acked == key && it.byPid == h.b.publicId }
        assertTrue("broadcast ACK bundle should reach sender quickly", acked)
        assertEquals(
            "public ACK should be bundled and removed after one successful send",
            1,
            h.bIo.publicAckBundleAttemptKeys.distinct().size
        )
    }

    @Test
    fun relayForwardsDuplicatePrivatePacketAgainAfterShortSuppressWindow() {
        val relayIo = object : SafeMeshNode.NodeIo {
            var sends = 0
            var prioritySends = 0
            override fun sendFrameOnLink(linkId: String, frame: ByteArray): Boolean {
                return sendFramesOnLink(linkId, listOf(frame), priority = false)
            }

            override fun sendFramesOnLink(linkId: String, frames: List<ByteArray>, priority: Boolean): Boolean {
                if (linkId != "relay-c") return false
                sends += 1
                if (priority) prioritySends += 1
                return true
            }

            override fun activeLinkIds(): List<String> = listOf("relay-c")
            override fun emit(event: SafeMeshNode.MeshEvent) = Unit
        }
        val relay = SafeMeshNode("R", Identity.ephemeral(), io = relayIo)
        relay.onLinkUp("relay-c", rxHint = 247)
        relayIo.sends = 0
        relayIo.prioritySends = 0

        val packet = privateRelayPacket(senderId = 0x13572468, seq = 42)
        deliver(relay, "a-relay", packet)
        assertEquals(1, relayIo.sends)
        assertEquals("private relay should enter the priority queue on first sight", 1, relayIo.prioritySends)

        deliver(relay, "a-relay", packet)
        assertEquals("immediate duplicate should still be suppressed", 1, relayIo.sends)

        Thread.sleep(Constants.LINK_TX_SUPPRESS_MS + 100)
        deliver(relay, "a-relay", packet)
        assertEquals("same pending packet should relay again inside the 60s recovery window", 2, relayIo.sends)
    }

    private fun pump(a: SafeMeshNode, b: SafeMeshNode, durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            a.tick()
            b.tick()
            Thread.sleep(Constants.ACK_GOSSIP_MS)
        }
    }

    private fun drainHeld(h: PairHarness, rounds: Int) {
        repeat(rounds) {
            val aHeld = h.aIo.takeHeld()
            val bHeld = h.bIo.takeHeld()
            aHeld.forEach { h.aIo.deliverHeld(it) }
            bHeld.forEach { h.bIo.deliverHeld(it) }
            h.a.tick()
            h.b.tick()
            Thread.sleep(Constants.ACK_GOSSIP_MS)
        }
    }

    private fun deliver(node: SafeMeshNode, ingress: String, packet: MeshPacket) {
        val frames = Fragmenter().fragment(packet.encode(), 247)
        frames.forEach { node.receiveFrame(ingress, it) }
    }

    private fun privateRelayPacket(senderId: Int, seq: Long): MeshPacket {
        val payload = byteArrayOf(Constants.PVT_DATA, Constants.AEAD_AES_256_GCM) + ByteArray(32) { it.toByte() }
        val now = System.currentTimeMillis()
        val header = Header(
            flag = Constants.FLAG_PRIVATE,
            ttl = Constants.MAX_TTL,
            payloadLen = payload.size,
            timestampMs = now,
            senderId = senderId,
            epoch = Constants.epoch10s(now),
            seq = seq
        )
        return MeshPacket(header, payload)
    }
}
