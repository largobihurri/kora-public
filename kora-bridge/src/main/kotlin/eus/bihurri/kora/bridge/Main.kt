package eus.bihurri.kora.bridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

/** Command line of `kora-bridge`; see [parse]. */
internal class BridgeOptions(
    val serial: String,
    val applicationId: String,
    val adb: String = "adb",
    val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        val USAGE = """
            Usage: kora-bridge --serial <adb-serial> --package <applicationId> [--adb <path>] [--timeout <seconds>]

            MCP stdio server for the Kora node of an Android app, reached through adb. Reads one JSON-RPC message per
            line on stdin and writes one per line on stdout; logs go to stderr.

              --serial   adb serial of the device or emulator (see `adb devices`)
              --package  applicationId of the app; its gate is content://<applicationId>.kora
              --adb      adb executable (default: adb, from PATH)
              --timeout  seconds to wait for one adb call (default: $DEFAULT_TIMEOUT_SECONDS)
        """.trimIndent()

        /** Parses [args]; throws [IllegalArgumentException] with a message for the user when they are unusable. */
        fun parse(args: List<String>): BridgeOptions {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val name = args[index]
                require(name in FLAGS) { "Unknown argument '$name'" }
                require(index + 1 < args.size) { "Missing value for $name" }
                require(name !in values) { "$name given twice" }
                values[name] = args[index + 1]
                index += 2
            }
            val serial = requireNotNull(values["--serial"]) { "--serial is required" }
            val applicationId = requireNotNull(values["--package"]) { "--package is required" }
            val timeout = values["--timeout"]?.let { text ->
                text.toLongOrNull()?.takeIf { it in 1..MAX_TIMEOUT_SECONDS } ?: throw IllegalArgumentException("--timeout must be 1-$MAX_TIMEOUT_SECONDS seconds")
            } ?: DEFAULT_TIMEOUT_SECONDS
            return BridgeOptions(serial, applicationId, values["--adb"] ?: "adb", timeout)
        }

        private val FLAGS = setOf("--serial", "--package", "--adb", "--timeout")
        private const val MAX_TIMEOUT_SECONDS = 600L
    }
}

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(BridgeOptions.USAGE)
        return
    }
    val options = try {
        BridgeOptions.parse(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println("kora-bridge: ${e.message}\n\n${BridgeOptions.USAGE}")
        exitProcess(2)
    }
    val channel = try {
        AdbDeviceChannel(ProcessAdbRunner(options.adb, options.timeoutSeconds), options.serial, options.applicationId)
    } catch (e: IllegalArgumentException) {
        System.err.println("kora-bridge: ${e.message}")
        exitProcess(2)
    }
    // stdout carries the protocol and nothing else; encoding is fixed to UTF-8 whatever the platform default is.
    val stdout = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, Charsets.UTF_8)
    val stdin = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    System.err.println("kora-bridge: ${options.applicationId} on ${options.serial} through ${options.adb}")
    McpBridge(channel, stdin, stdout) { System.err.println("kora-bridge: $it") }.run()
}
