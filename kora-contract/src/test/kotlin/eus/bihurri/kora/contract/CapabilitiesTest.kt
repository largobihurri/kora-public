package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilitiesTest {
    @Test
    fun `registry lists the six built-ins`() {
        assertEquals(
            setOf("media.library", "media.playback", "media.history", "media.artwork", "fitness.session", "location"),
            Capabilities.all.map { it.id.value }.toSet(),
        )
        assertEquals(
            mapOf(
                "media.library" to 0, "media.playback" to 0, "media.history" to 0, "media.artwork" to 0, "fitness.session" to 0,
                "location" to 1,
            ),
            Capabilities.all.associate { it.id.value to it.version.minor },
        )
        assertTrue(Capabilities.all.all { it.version.major == 1 })
    }

    @Test
    fun `lookup by id and by wire string`() {
        assertSame(MediaPlayback, Capabilities.find(CapabilityId("media.playback")))
        assertSame(MediaLibrary, Capabilities.find("media.library"))
        assertSame(MediaHistory, Capabilities.find("media.history"))
        assertSame(MediaArtwork, Capabilities.find("media.artwork"))
        assertSame(FitnessSession, Capabilities.find("fitness.session"))
        assertSame(LocationCapability, Capabilities.find("location"))
        assertNull(Capabilities.find("media.unknown"))
        assertNull(Capabilities.find("Not A Valid Id"))
    }

    @Test
    fun `lookup by major finds the built-in only at its own major`() {
        assertSame(MediaPlayback, Capabilities.find(CapabilityId("media.playback"), 1))
        assertSame(LocationCapability, Capabilities.find("location", 1))
        assertNull(Capabilities.find("location", 0))
        assertNull(Capabilities.find("location", 2))
        assertNull(Capabilities.find("media.unknown", 1))
        assertNull(Capabilities.find("Not A Valid Id", 1))
        assertSame(Capabilities.registry.all, Capabilities.all)
    }

    @Test
    fun `a registry holds one definition per id and major, and find without a major returns the newest`() {
        val v1 = object : Capability(CapabilityId("test.thing"), CapabilityVersion(1, 0)) {}
        val v1Dot1 = object : Capability(CapabilityId("test.thing"), CapabilityVersion(1, 1)) {}
        val v2 = object : Capability(CapabilityId("test.thing"), CapabilityVersion(2, 0)) {}
        val other = object : Capability(CapabilityId("test.other"), CapabilityVersion(1, 0)) {}

        val registry = CapabilityRegistry(listOf(v1, v2, other))

        assertSame(v1, registry.find("test.thing", 1))
        assertSame(v2, registry.find(CapabilityId("test.thing"), 2))
        assertSame(v2, registry.find("test.thing"))
        assertSame(other, registry.find(CapabilityId("test.other")))
        assertNull(registry.find("test.thing", 3))
        assertEquals(listOf(v1, v2, other), registry.all)
        assertThrows(IllegalArgumentException::class.java) { CapabilityRegistry(listOf(v1, v1Dot1)) }
    }

    @Test
    fun `no capability repeats an operation or event name`() {
        for (capability in Capabilities.all) {
            val operations = capability.operations.map { it.name }
            val events = capability.events.map { it.name }
            assertEquals(operations.distinct(), operations, "operations of ${capability.id}")
            assertEquals(events.distinct(), events, "events of ${capability.id}")
        }
    }

    @Test
    fun `capability ids are unique`() {
        assertEquals(Capabilities.all.size, Capabilities.all.map { it.id }.distinct().size)
    }

    @Test
    fun `every spec has a description and a scope under its capability namespace`() {
        for (capability in Capabilities.all) {
            val namespace = capability.id.value
            capability.operations.forEach {
                assertTrue(it.description.isNotBlank(), "${capability.id}.${it.name} description")
                assertTrue(it.scope.value.startsWith("$namespace."), "${capability.id}.${it.name} scope ${it.scope}")
            }
            capability.events.forEach {
                assertTrue(it.description.isNotBlank(), "${capability.id}.${it.name} description")
                assertTrue(it.scope.value.startsWith("$namespace."), "${capability.id}.${it.name} scope ${it.scope}")
            }
        }
    }

    @Test
    fun `declared specs are the registered ones`() {
        assertSame(MediaPlayback.SeekTo, MediaPlayback.findOperation("seek_to"))
        assertSame(MediaPlayback.NowPlayingChanged, MediaPlayback.findEvent("now_playing_changed"))
        assertNotNull(LocationCapability.findOperation("get_last_fix"))
        assertNull(LocationCapability.findOperation("nope"))
        assertEquals(listOf("get_last_fix", "configure_stream"), LocationCapability.operations.map { it.name })
        assertEquals(listOf("fix_changed", "fixes_batched"), LocationCapability.events.map { it.name })
        assertEquals(
            listOf("play", "pause", "toggle", "next", "previous", "seek_to", "play_playlist", "play_tracks", "get_state"),
            MediaPlayback.operations.map { it.name },
        )
        assertEquals(listOf("playback_state_changed", "now_playing_changed"), MediaPlayback.events.map { it.name })
    }

    @Test
    fun `media history is a single read operation with no event`() {
        assertEquals(listOf("list_plays"), MediaHistory.operations.map { it.name })
        assertEquals(emptyList<String>(), MediaHistory.events.map { it.name })
        assertEquals(Scope("media.history.read"), MediaHistory.ListPlays.scope)
        assertEquals(CapabilityDescriptor("media.history", CapabilityVersion(1, 0)), MediaHistory.descriptor())
    }

    @Test
    fun `media artwork is a single read operation with its own scope and no event`() {
        assertEquals(listOf("get_artwork"), MediaArtwork.operations.map { it.name })
        assertEquals(emptyList<String>(), MediaArtwork.events.map { it.name })
        assertEquals(Scope("media.artwork.read"), MediaArtwork.ReadScope)
        assertEquals(Scope("media.artwork.read"), MediaArtwork.GetArtwork.scope)
        assertEquals(CapabilityDescriptor("media.artwork", CapabilityVersion(1, 0)), MediaArtwork.descriptor())
    }

    @Test
    fun `playback separates read and control scopes`() {
        assertEquals(Scope("media.playback.control"), MediaPlayback.Play.scope)
        assertEquals(Scope("media.playback.read"), MediaPlayback.GetState.scope)
        assertEquals(Scope("media.playback.read"), MediaPlayback.PlaybackStateChanged.scope)
        assertEquals(Scope("media.library.read"), MediaLibrary.SearchTracks.scope)
        assertEquals(Scope("fitness.session.read"), FitnessSession.GetActiveSession.scope)
        assertEquals(Scope("location.read"), LocationCapability.FixChanged.scope)
    }

    @Test
    fun `specs know their owning capability`() {
        for (capability in Capabilities.all) {
            assertTrue(capability.operations.all { it.capabilityId == capability.id && it.capabilityVersion == capability.version })
            assertTrue(capability.events.all { it.capabilityId == capability.id && it.capabilityVersion == capability.version })
        }
        assertEquals(CapabilityId("media.playback"), MediaPlayback.SeekTo.capabilityId)
    }

    @Test
    fun `capability descriptor advertises id and version`() {
        assertEquals(CapabilityDescriptor("media.library", CapabilityVersion(1, 0)), MediaLibrary.descriptor())
    }
}
