package eus.bihurri.kora.contract

import kotlinx.serialization.Serializable

/**
 * What one node tells its peers about itself: who it is and which capabilities it provides.
 * [nodeId] is stable per app (e.g. the application id) and [displayName] is for humans.
 *
 * [koraVersion] is the Kora library the node was built with ([KoraBuild.VERSION]); null for a peer built before the
 * field existed. It is for logs and diagnostics only: nobody may decide compatibility from it. Compatibility is the
 * business of the capability versions in [capabilities].
 */
@Serializable
public data class NodeDescriptor(
    public val nodeId: String,
    public val displayName: String,
    public val capabilities: List<CapabilityDescriptor> = emptyList(),
    public val koraVersion: String? = null,
)

/** One provided capability: its raw [id] string (see [CapabilityId]) and the [version] the provider implements. */
@Serializable
public data class CapabilityDescriptor(public val id: String, public val version: CapabilityVersion)
