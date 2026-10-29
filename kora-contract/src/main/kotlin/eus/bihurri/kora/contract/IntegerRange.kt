package eus.bihurri.kora.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Declares that an integer property of a contract type accepts only [min]..[max] (both inclusive).
 *
 * It documents the range in the serial descriptor, without reflection, so schema generators can advertise it (the MCP
 * JSON Schema adds `"minimum"` and `"maximum"`). It does not validate: the type's `init` still checks the range,
 * and the annotation must name the same bounds, preferably through the same constants:
 * ```
 * @IntegerRange(min = MediaArtwork.MIN_EDGE_PX.toLong(), max = MediaArtwork.MAX_EDGE_PX.toLong()) public val maxEdgePx: Int
 * ```
 * Adding or removing it never changes the wire format.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class IntegerRange(val min: Long, val max: Long)
