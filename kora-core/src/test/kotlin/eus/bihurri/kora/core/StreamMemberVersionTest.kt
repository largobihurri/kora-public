package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.StreamDirection
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration

/**
 * Stream calls ([KoraNode.callSending], [KoraNode.callReceiving]) and [KoraNode.awaitProvider] of a member follow the
 * member's version like [KoraNode.call]: a consumer built against [Blobs] at 1.1 uses the stream operations that exist
 * since 1.0 on a provider still at 1.0, and is refused the one added in 1.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamMemberVersionTest {
    /** [Blobs] one minor later: the same operations since 1.0, plus an upload added in 1.1. */
    private object BlobsV11 : Capability(Blobs.id, CapabilityVersion(1, 1)) {
        val Put: OperationSpec<String, Long> = operation(
            "put", String.serializer(), Long.serializer(), Blobs.Write, "Stores bytes.", OperationEffect.WRITE,
            StreamDirection.UPLOAD,
        )
        val Get: OperationSpec<String, String> = operation(
            "get", String.serializer(), String.serializer(), Blobs.Read, "Reads bytes.", OperationEffect.READ,
            StreamDirection.DOWNLOAD,
        )
        val Drop: OperationSpec<String, Unit> =
            operation("drop", String.serializer(), Unit.serializer(), Blobs.Write, "Forgets.", OperationEffect.WRITE)
        val Append: OperationSpec<String, Long> = operation(
            "append", String.serializer(), Long.serializer(), Blobs.Write, "Appends bytes.", OperationEffect.WRITE,
            StreamDirection.UPLOAD, since = CapabilityVersion(1, 1),
        )
    }

    private val network = InMemoryPeerNetwork()
    private val providerEndpoint = network.endpoint("provider")
    private val consumerEndpoint = network.endpoint("consumer")
    private val store = BlobStore()

    /** A provider at [Blobs] 1.0, connected, and the consumer that sees it as a peer. */
    private fun consumer(): KoraNode {
        val provider = KoraNode("provider", peerDirectory = providerEndpoint, transport = providerEndpoint)
            .also(store::provideOn)
        val consumer = KoraNode("consumer", peerDirectory = consumerEndpoint, transport = consumerEndpoint)
        providerEndpoint.connect(provider)
        consumerEndpoint.connect(consumer)
        return consumer
    }

    @Test
    fun `a 1_1 consumer uploads through a 1_0 member of a 1_0 provider`() = runTest {
        val consumer = consumer()

        val read = consumer.callSending(BlobsV11.Put, "x", ByteStream(3, TrackedSource("abc")))

        assertEquals(3L, read)
        assertEquals(mapOf("x" to "abc"), store.content)
    }

    @Test
    fun `a 1_1 consumer downloads through a 1_0 member of a 1_0 provider`() = runTest {
        val consumer = consumer()
        store.content["x"] = "abc"

        consumer.callReceiving(BlobsV11.Get, "x").use { assertEquals("x" to "abc", it.output to it.bytes.text()) }
    }

    @Test
    fun `a node at 1_0 serves its own 1_1 consumer a 1_0 stream member locally`() = runTest {
        val node = KoraNode("local").also(store::provideOn)
        store.content["x"] = "abc"

        node.callReceiving(BlobsV11.Get, "x").use { assertEquals("abc", it.bytes.text()) }
        assertEquals(2L, node.callSending(BlobsV11.Put, "y", ByteStream(2, TrackedSource("de"))))
    }

    @Test
    fun `a stream member added in 1_1 is still refused by a 1_0 provider`() = runTest {
        val consumer = consumer()
        val source = TrackedSource("abc")

        val failure = thrownBy<KoraException> { consumer.callSending(BlobsV11.Append, "x", ByteStream(3, source)) }

        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, failure.error.code)
        assertTrue(source.closed)
        assertEquals(emptyList<Caller>(), store.callers)
    }

    @Test
    fun `awaitProvider of a member finds a provider at the member's version`() = runTest(UnconfinedTestDispatcher()) {
        val consumer = consumer()

        assertTrue(consumer.awaitProvider(BlobsV11.Put, Duration.ZERO))
        assertTrue(consumer.awaitProvider(BlobsV11.Put, Duration.ZERO, Target.Peer("provider")))
        assertFalse(consumer.awaitProvider(BlobsV11.Put, Duration.ZERO, Target.Local))
        assertFalse(consumer.awaitProvider(BlobsV11.Append, Duration.ZERO))
        assertFalse(consumer.awaitProvider(BlobsV11, Duration.ZERO), "the whole capability still needs a 1.1 provider")
    }
}
