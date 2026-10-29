package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback

/**
 * A set of capability definitions that can be looked up by id and by major version.
 *
 * Kora's own set is [Capabilities.registry]. Nodes, the JSON gate and the MCP tool generator take a registry as an
 * optional parameter, defaulting to that one; a different registry is only needed to describe capabilities Kora
 * does not ship, for example in tests that exercise two majors of one id.
 *
 * A registry holds at most one [Capability] per id **and major**, like a node holds one provider per id and major
 * (see `docs/compatibility.md`, "Two majors at once"). Within one major the registered definition is the one the
 * library ships, so its minor is the newest.
 *
 * @throws IllegalArgumentException when two definitions share an id and a major.
 */
public class CapabilityRegistry(capabilities: List<Capability>) {
    /** Every definition, in the order given. When an id has two majors, both are listed. */
    public val all: List<Capability> = capabilities.toList()

    private val byIdAndMajor: Map<Pair<CapabilityId, Int>, Capability> = all.associateBy { it.id to it.version.major }

    private val newestById: Map<CapabilityId, Capability> =
        all.groupBy { it.id }.mapValues { (_, versions) -> versions.maxBy { it.version.major } }

    init {
        require(byIdAndMajor.size == all.size) { "Duplicate capability id and major in the registry" }
    }

    /** The definition of [id] with the highest major, or null when [id] is unknown. */
    public fun find(id: CapabilityId): Capability? = newestById[id]

    /** The definition of [id] at [major], or null when that id or that major is not in the registry. */
    public fun find(id: CapabilityId, major: Int): Capability? = byIdAndMajor[id to major]

    /** Like [find] by raw wire string; null when [id] is unknown or malformed. */
    public fun find(id: String): Capability? = idOrNull(id)?.let(::find)

    /** Like [find] by id and major, with the id as a raw wire string; null when it is unknown or malformed. */
    public fun find(id: String, major: Int): Capability? = idOrNull(id)?.let { find(it, major) }

    private fun idOrNull(id: String): CapabilityId? = runCatching { CapabilityId(id) }.getOrNull()
}

/**
 * Registry of the capabilities defined by this version of Kora.
 *
 * Every built-in is at major 1 today. The lookups are major-aware so that the day a capability gains a `2.0` next to
 * its `1.x`, nodes, the JSON gate and MCP check the scopes and offer the tools of the major a request carries; use
 * the overloads with `major` whenever you know the version a request asks for.
 */
public object Capabilities {
    /** The built-in definitions as a [CapabilityRegistry], the default of everything that looks capabilities up. */
    public val registry: CapabilityRegistry =
        CapabilityRegistry(listOf(MediaLibrary, MediaPlayback, MediaHistory, MediaArtwork, FitnessSession, LocationCapability))

    /** Every built-in definition; one entry per id and major. */
    public val all: List<Capability> get() = registry.all

    /** The built-in [id] with the highest major, or null. Prefer the overload with `major` for a request. */
    public fun find(id: CapabilityId): Capability? = registry.find(id)

    /** The built-in [id] at [major], or null. */
    public fun find(id: CapabilityId, major: Int): Capability? = registry.find(id, major)

    /** Lookup by raw wire string (highest major); null when [id] is unknown or malformed. */
    public fun find(id: String): Capability? = registry.find(id)

    /** Lookup by raw wire string and major; null when [id] is unknown or malformed, or has no such major. */
    public fun find(id: String, major: Int): Capability? = registry.find(id, major)
}
