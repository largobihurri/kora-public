package eus.bihurri.kora.wear

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [DataLayer] over Google Play services' `CapabilityClient` and `MessageClient`. Thin on purpose: all decisions
 * live in [MessageLink]. Clients are created on first use and hold no connection of their own.
 */
internal class GmsDataLayer(private val context: Context) : DataLayer {
    private val capabilities by lazy { Wearable.getCapabilityClient(context) }
    private val messages by lazy { Wearable.getMessageClient(context) }

    override suspend fun reachableNodes(): List<DataNode> =
        capabilities.getCapability(WearProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.map { DataNode(it.id, it.isNearby) }

    override suspend fun send(nodeId: String, path: String, data: ByteArray) {
        messages.sendMessage(nodeId, path, data).await()
    }

    override fun addCapabilityListener(onChange: () -> Unit): AutoCloseable {
        val listener = CapabilityClient.OnCapabilityChangedListener { onChange() }
        capabilities.addListener(listener, WearProtocol.CAPABILITY)
        return AutoCloseable { capabilities.removeListener(listener, WearProtocol.CAPABILITY) }
    }
}

/** Suspends until the task completes; a cancelled task is a failure. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
