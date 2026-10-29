package eus.bihurri.kora.wear

/** A device of the Data Layer that advertises the Kora capability. */
internal data class DataNode(val id: String, val isNearby: Boolean)

/**
 * Port: the few Data Layer operations the link needs, so its logic runs on the JVM in tests. The real
 * implementation is [GmsDataLayer].
 */
internal interface DataLayer {
    /** Reachable devices (other than this one) that advertise the Kora capability. Throws when unavailable. */
    suspend fun reachableNodes(): List<DataNode>

    /** Sends one message; returns when the platform accepted it. Throws when the device cannot be reached. */
    suspend fun send(nodeId: String, path: String, data: ByteArray)

    /** Calls [onChange] whenever the set of reachable devices advertising the capability may have changed. */
    fun addCapabilityListener(onChange: () -> Unit): AutoCloseable
}
