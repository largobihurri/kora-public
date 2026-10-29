package eus.bihurri.kora.contract.fixtures

import java.io.ByteArrayOutputStream

/**
 * A just-enough CBOR (RFC 8949) reader and writer to edit encoded payloads: scalars are kept byte for byte, arrays,
 * maps and tags are rebuilt, and containers are written with definite lengths. Reads both definite and indefinite
 * containers, because kotlinx.serialization writes indefinite ones.
 */
internal object CborTree {
    sealed interface Node

    class Raw(val bytes: ByteArray) : Node

    class Arr(val items: List<Node>) : Node

    class Map(val entries: List<Pair<Node, Node>>) : Node

    class Tag(val head: ByteArray, val child: Node) : Node

    fun text(value: String): Raw {
        val utf8 = value.encodeToByteArray()
        return Raw(head(3, utf8.size.toLong()) + utf8)
    }

    /** The string a definite-length text item holds, or null when [node] is anything else. */
    fun textOrNull(node: Node): String? {
        val raw = (node as? Raw)?.bytes ?: return null
        if (raw.isEmpty() || (raw[0].toInt() and 0xff) shr 5 != 3 || (raw[0].toInt() and 0x1f) == 31) return null
        val info = raw[0].toInt() and 0x1f
        val headSize = when (info) {
            in 0..23 -> 1
            24 -> 2
            25 -> 3
            26 -> 5
            else -> 9
        }
        return raw.copyOfRange(headSize, raw.size).decodeToString()
    }

    /** Parses [bytes] (one item), applies [change] and writes the result. */
    fun edit(bytes: ByteArray, change: (Node) -> Node): ByteArray {
        val reader = Reader(bytes)
        val root = reader.node()
        require(reader.position == bytes.size) { "Trailing bytes after the CBOR item" }
        return ByteArrayOutputStream().also { write(change(root), it) }.toByteArray()
    }

    private fun write(node: Node, out: ByteArrayOutputStream) {
        when (node) {
            is Raw -> out.write(node.bytes)
            is Tag -> {
                out.write(node.head)
                write(node.child, out)
            }

            is Arr -> {
                out.write(head(4, node.items.size.toLong()))
                node.items.forEach { write(it, out) }
            }

            is Map -> {
                out.write(head(5, node.entries.size.toLong()))
                node.entries.forEach { (k, v) ->
                    write(k, out)
                    write(v, out)
                }
            }
        }
    }

    private fun head(major: Int, length: Long): ByteArray {
        val m = major shl 5
        return when {
            length < 24 -> byteArrayOf((m or length.toInt()).toByte())
            length < 0x100 -> byteArrayOf((m or 24).toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf((m or 25).toByte(), (length shr 8).toByte(), length.toByte())
            else -> byteArrayOf((m or 26).toByte(), (length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        }
    }

    private class Reader(private val b: ByteArray) {
        var position = 0
            private set

        private fun peek(): Int = b[position].toInt() and 0xff

        private fun argument(info: Int): Long {
            val size = when (info) {
                in 0..23 -> return info.toLong()
                24 -> 1
                25 -> 2
                26 -> 4
                27 -> 8
                else -> error("Unsupported CBOR additional info $info")
            }
            var value = 0L
            repeat(size) { value = (value shl 8) or (b[position++].toLong() and 0xff) }
            return value
        }

        private fun atBreak(): Boolean = peek() == 0xff

        fun node(): Node {
            val start = position
            val initial = peek()
            position++
            val major = initial shr 5
            val info = initial and 0x1f
            return when (major) {
                0, 1 -> {
                    argument(info)
                    Raw(b.copyOfRange(start, position))
                }

                2, 3 -> {
                    if (info == 31) {
                        while (!atBreak()) node()
                        position++
                    } else {
                        val length = argument(info).toInt()
                        position += length
                    }
                    Raw(b.copyOfRange(start, position))
                }

                4 -> Arr(
                    if (info == 31) {
                        buildList { while (!atBreak()) add(node()); position++ }
                    } else {
                        List(argument(info).toInt()) { node() }
                    },
                )

                5 -> Map(
                    if (info == 31) {
                        buildList { while (!atBreak()) add(node() to node()); position++ }
                    } else {
                        List(argument(info).toInt()) { node() to node() }
                    },
                )

                6 -> {
                    argument(info)
                    Tag(b.copyOfRange(start, position), node())
                }

                else -> {
                    when (info) {
                        24 -> position += 1
                        25 -> position += 2
                        26 -> position += 4
                        27 -> position += 8
                    }
                    Raw(b.copyOfRange(start, position))
                }
            }
        }
    }
}
