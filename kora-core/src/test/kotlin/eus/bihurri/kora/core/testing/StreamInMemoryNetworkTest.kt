package eus.bihurri.kora.core.testing

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.core.BlobStore
import eus.bihurri.kora.core.Blobs
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.TrackedSource
import eus.bihurri.kora.core.text
import eus.bihurri.kora.core.thrownBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [InMemoryPeerNetwork] carries streams beside the envelopes, and can be built without them. */
class StreamInMemoryNetworkTest {
    /** CBOR that records the serial name of every value it encodes or decodes. */
    private class RecordingCodec : KoraCodec {
        val seen = mutableListOf<String>()

        override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
            CborCodec.encode(serializer, value).also { seen += "encode ${serializer.descriptor.serialName.substringAfterLast('.')}" }

        override fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
            CborCodec.decode(deserializer, bytes).also { seen += "decode ${deserializer.descriptor.serialName.substringAfterLast('.')}" }
    }

    private val store = BlobStore()
    private val app = Caller(CallerKind.PEER, "app")

    private fun nodes(network: InMemoryPeerNetwork): KoraNode {
        val filesEndpoint = network.endpoint("files")
        val appEndpoint = network.endpoint("app")
        filesEndpoint.connect(KoraNode("files", peerDirectory = filesEndpoint, transport = filesEndpoint).also(store::provideOn))
        return KoraNode("app", peerDirectory = appEndpoint, transport = appEndpoint).also(appEndpoint::connect)
    }

    @Test
    fun `streams cross beside envelopes that round trip through the codec`() = runTest {
        val codec = RecordingCodec()
        val caller = nodes(InMemoryPeerNetwork(codec))
        val source = TrackedSource("photo")
        val bytes = ByteStream(5, source)

        val read = caller.callSending(Blobs.Put, "p", bytes)
        val envelopesOfUpload = codec.seen.toList()
        val received = caller.callReceiving(Blobs.Get, "p")

        assertEquals(5L, read)
        assertSame(bytes, store.uploads.single(), "the stream object passes through")
        assertEquals(1, source.closes)
        assertEquals(listOf("encode KoraRequest", "decode KoraRequest", "encode KoraResponse", "decode KoraResponse"), envelopesOfUpload)
        assertEquals(envelopesOfUpload + envelopesOfUpload, codec.seen)
        assertEquals("p", received.output)
        assertEquals("photo", received.bytes.text())
        received.close()
        assertTrue(store.downloads.single().closed)
        assertEquals(listOf(app, app), store.callers)
    }

    @Test
    fun `a network built without streams refuses them before delivering anything`() = runTest {
        val caller = nodes(InMemoryPeerNetwork(streams = false))
        val source = TrackedSource("photo")

        val upload = thrownBy<KoraException> { caller.callSending(Blobs.Put, "p", ByteStream(5, source)) }
        val download = thrownBy<KoraException> { caller.callReceiving(Blobs.Get, "p") }

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, upload.error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, download.error.code)
        assertEquals(5, source.available(), "not a byte was read")
        assertTrue(source.closed)
        assertEquals(emptyList<Caller>(), store.callers)
    }

    @Test
    fun `a network built without streams still carries plain calls`() = runTest {
        val caller = nodes(InMemoryPeerNetwork(CborCodec, streams = false))
        store.content["p"] = "photo"

        caller.call(Blobs.Drop, "p")

        assertEquals(emptyMap<String, String>(), store.content)
        assertEquals(listOf(app), store.callers)
    }
}
