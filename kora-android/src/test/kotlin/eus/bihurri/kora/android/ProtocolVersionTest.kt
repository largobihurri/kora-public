package eus.bihurri.kora.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtocolVersionTest {
    @Test
    fun `this app speaks protocol 2, streams start at 2 and peers are accepted from 1`() {
        assertEquals(2, KoraAndroid.PROTOCOL_VERSION)
        assertEquals(2, KoraAndroid.STREAMS_PROTOCOL_VERSION)
        assertEquals(1, KoraAndroid.MIN_PEER_PROTOCOL_VERSION)
    }

    @Test
    fun `a peer on protocol 1 is accepted, as is any later version`() {
        assertNull(peerProtocolRefusal(1))
        assertNull(peerProtocolRefusal(2))
        assertNull(peerProtocolRefusal(3))
    }

    @Test
    fun `a peer below protocol 1 is refused`() {
        assertEquals("Peer speaks protocol 0, which is older than 1", peerProtocolRefusal(0))
        assertNotNull(peerProtocolRefusal(-1))
    }
}
