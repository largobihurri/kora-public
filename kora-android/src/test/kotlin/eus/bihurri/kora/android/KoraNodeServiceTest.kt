package eus.bihurri.kora.android

import android.content.Intent
import android.os.Binder
import android.os.ParcelFileDescriptor
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraStreamCallback
import eus.bihurri.kora.android.ipc.IKoraNode
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.files.FileIdRequest
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.files.UploadFileRequest
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.testing.provideStubbed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The pieces that need a real `Service` and `Binder`: manifest wiring, binding, and the calling uid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KoraNodeServiceTest {
    @After
    fun tearDown() {
        KoraAndroid.uninstall()
        ShadowBinder.reset()
    }

    private fun node(): KoraNode = KoraAndroid.install(RuntimeEnvironment.getApplication()) {
        provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { } }
    }

    private fun bind(): IKoraNode {
        val service = Robolectric.buildService(KoraNodeService::class.java).create().get()
        return IKoraNode.Stub.asInterface(service.onBind(Intent(KoraAndroid.ACTION_NODE)))
    }

    @Test
    fun `the library manifest exports the service for the node action`() {
        val app = RuntimeEnvironment.getApplication()
        val resolved = app.packageManager.queryIntentServices(Intent(KoraAndroid.ACTION_NODE), 0)
        assertEquals(listOf(KoraNodeService::class.java.name), resolved.map { it.serviceInfo.name })
        assertTrue(resolved.single().serviceInfo.exported)
    }

    @Test
    fun `the node is published only after its providers are registered`() {
        var visibleWhileConfiguring = true
        val node = KoraAndroid.install(RuntimeEnvironment.getApplication()) {
            visibleWhileConfiguring = KoraAndroid.current() != null
            provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { } }
        }
        assertEquals(false, visibleWhileConfiguring)
        assertEquals(listOf("media.playback"), KoraAndroid.current()?.node?.describe()?.capabilities?.map { it.id })
        assertEquals(node, KoraAndroid.node)
    }

    @Test
    fun `install hands its streamIdleTimeout to the Binder transport and to the service`() {
        KoraAndroid.install(RuntimeEnvironment.getApplication(), streamIdleTimeout = 3.seconds) {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { } }
        }
        bind()

        val runtime = KoraAndroid.current()!!
        assertEquals(3.seconds, runtime.transport.streamIdleTimeout)
        assertEquals(3.seconds, runtime.server!!.streamIdleTimeout)
    }

    @Test
    fun `without an installed node the service refuses to bind`() {
        val service = Robolectric.buildService(KoraNodeService::class.java).create().get()
        assertNull(service.onBind(Intent(KoraAndroid.ACTION_NODE)))
    }

    @Test
    fun `the app itself is served`() {
        node()
        val remote = bind()

        assertEquals(KoraAndroid.PROTOCOL_VERSION, remote.protocolVersion())
        val descriptor = CborCodec.decode(NodeDescriptor.serializer(), remote.describe())
        assertEquals(RuntimeEnvironment.getApplication().packageName, descriptor.nodeId)
        assertEquals(listOf("media.playback"), descriptor.capabilities.map { it.id })
    }

    @Test
    fun `a call is answered through the callback`() {
        node()
        val remote = bind()
        val answered = CountDownLatch(1)
        val response = arrayOfNulls<ByteArray>(1)
        val request = CborCodec.encode(
            KoraRequest.serializer(),
            KoraRequest("r1", MediaPlayback.id.value, MediaPlayback.version, MediaPlayback.Play.name, MediaPlayback.Play.encodeInput(CborCodec, eus.bihurri.kora.contract.NoInput)),
        )

        remote.call(request, object : eus.bihurri.kora.android.ipc.IKoraCallback.Stub() {
            override fun onResponse(bytes: ByteArray) {
                response[0] = bytes
                answered.countDown()
            }
        })

        assertTrue(answered.await(5, TimeUnit.SECONDS))
        assertTrue(CborCodec.decode(KoraResponse.serializer(), response[0]!!) is KoraResponse.Success)
    }

    @Test
    fun `a caller from an unknown uid is refused`() {
        node()
        val remote = bind()
        ShadowBinder.setCallingUid(Binder.getCallingUid() + 1_234)

        try {
            remote.describe()
            fail("describe must refuse an unknown caller")
        } catch (expected: SecurityException) {
            assertNotNull(expected)
        }
    }

    @Test
    fun `the adb shell is identified but the default policy refuses its calls`() {
        node()
        val remote = bind()
        ShadowBinder.setCallingUid(2_000)

        assertEquals(RuntimeEnvironment.getApplication().packageName, CborCodec.decode(NodeDescriptor.serializer(), remote.describe()).nodeId)

        val answered = CountDownLatch(1)
        val response = arrayOfNulls<ByteArray>(1)
        val request = CborCodec.encode(
            KoraRequest.serializer(),
            KoraRequest("r2", MediaPlayback.id.value, MediaPlayback.version, MediaPlayback.Play.name, MediaPlayback.Play.encodeInput(CborCodec, eus.bihurri.kora.contract.NoInput)),
        )
        remote.call(request, object : eus.bihurri.kora.android.ipc.IKoraCallback.Stub() {
            override fun onResponse(bytes: ByteArray) {
                response[0] = bytes
                answered.countDown()
            }
        })

        assertTrue(answered.await(5, TimeUnit.SECONDS))
        val failure = CborCodec.decode(KoraResponse.serializer(), response[0]!!) as KoraResponse.Failure
        assertEquals(eus.bihurri.kora.contract.ErrorCode.UNAUTHORIZED, failure.error.code)
    }

    /** A descriptor that records being closed, over a real (Robolectric) one. */
    private class RecordingDescriptor(wrapped: ParcelFileDescriptor) : ParcelFileDescriptor(wrapped) {
        @Volatile
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun `stream calls from an unknown uid are refused through their callbacks and the descriptor is closed`() {
        node()
        val remote = bind()
        ShadowBinder.setCallingUid(Binder.getCallingUid() + 1_234)
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = RecordingDescriptor(pipe[0])
        val upload = CborCodec.encode(
            KoraRequest.serializer(),
            KoraRequest("u1", FilesCapability.id.value, FilesCapability.version, FilesCapability.Upload.name, FilesCapability.Upload.encodeInput(CborCodec, UploadFileRequest("a.txt", "text/plain", 3))),
        )
        val download = CborCodec.encode(
            KoraRequest.serializer(),
            KoraRequest("d1", FilesCapability.id.value, FilesCapability.version, FilesCapability.Download.name, FilesCapability.Download.encodeInput(CborCodec, FileIdRequest("f1"))),
        )
        val answered = CountDownLatch(2)
        val uploadAnswer = arrayOfNulls<ByteArray>(1)
        val downloadAnswer = arrayOfNulls<Any>(3)

        remote.callSending(upload, readEnd, 3, object : IKoraCallback.Stub() {
            override fun onResponse(bytes: ByteArray) {
                uploadAnswer[0] = bytes
                answered.countDown()
            }
        })
        remote.callReceiving(download, object : IKoraStreamCallback.Stub() {
            override fun onResponse(response: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long) {
                downloadAnswer[0] = response
                downloadAnswer[1] = bytes
                downloadAnswer[2] = sizeBytes
                answered.countDown()
            }
        })

        assertTrue(answered.await(5, TimeUnit.SECONDS))
        val uploadFailure = CborCodec.decode(KoraResponse.serializer(), uploadAnswer[0]!!) as KoraResponse.Failure
        assertEquals(eus.bihurri.kora.contract.ErrorCode.UNAUTHORIZED, uploadFailure.error.code)
        assertTrue(readEnd.closed)
        val downloadFailure = CborCodec.decode(KoraResponse.serializer(), downloadAnswer[0] as ByteArray) as KoraResponse.Failure
        assertEquals(eus.bihurri.kora.contract.ErrorCode.UNAUTHORIZED, downloadFailure.error.code)
        assertNull(downloadAnswer[1])
        assertEquals(0L, downloadAnswer[2])
        pipe.forEach { it.close() }
    }
}
