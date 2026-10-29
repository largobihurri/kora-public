package eus.bihurri.kora.bridge

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What an adb invocation printed (standard output and standard error together) and how it ended. */
internal class AdbResult(val exitCode: Int, val output: String)

/** Port: runs adb with [args] (everything after the executable). The implementation is a process; tests use a fake. */
internal fun interface AdbRunner {
    /** @throws DeviceException when adb cannot run or does not finish in time. */
    fun run(args: List<String>): AdbResult
}

/** Runs the real adb as a child process with a time limit; the bridge's own stdin is never handed to it. */
internal class ProcessAdbRunner(private val executable: String, private val timeoutSeconds: Long) : AdbRunner {
    override fun run(args: List<String>): AdbResult {
        val process = try {
            ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw DeviceException("Cannot run '$executable': ${e.message}. Install adb or pass --adb <path>.")
        }
        // adb shell would otherwise read the MCP messages meant for the bridge; give it an empty stdin.
        process.outputStream.close()
        var output = ByteArray(0)
        val reader = thread(isDaemon = true, name = "adb-output") { output = process.inputStream.readBytes() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(KILL_GRACE_MILLIS)
            throw DeviceException("adb did not answer within $timeoutSeconds s (device unreachable or the app is stuck)")
        }
        reader.join()
        return AdbResult(process.exitValue(), output.decodeToString())
    }

    private companion object {
        const val KILL_GRACE_MILLIS = 2_000L
    }
}

/**
 * Port of the bridge loop: delivers one JSON-RPC message to the phone's Kora node and returns its answer, or null
 * when the message was a notification. Implementations block until the device answers.
 */
internal fun interface DeviceChannel {
    /** @throws DeviceException when the message could not be delivered or the gate refused it. */
    fun send(message: String): String?
}

/** The device could not be reached or refused the call. [code] is the gate's wire error code when it refused. */
internal class DeviceException(message: String, val code: String? = null) : Exception(message)

/**
 * [DeviceChannel] over `adb -s <serial> shell content call --uri content://<package>.kora --method kora.mcp --arg b64:...`
 * (see [Framing] for the encoding). adb gives the call the identity of the shell, so the app's master switch and its
 * grants for `shell` decide what the MCP client may do; nothing listens on the network.
 */
internal class AdbDeviceChannel(
    private val adb: AdbRunner,
    private val serial: String,
    private val applicationId: String,
) : DeviceChannel {
    init {
        require(serial.isNotBlank() && !serial.startsWith("-") && serial.none { it.isWhitespace() }) { "Invalid adb serial: '$serial'" }
        require(APPLICATION_ID.matches(applicationId)) { "Invalid application id: '$applicationId'" }
    }

    override fun send(message: String): String? {
        val argument = Framing.encode(message)
        if (argument.length > MAX_ARGUMENT_CHARS) {
            throw DeviceException(
                "Message of ${message.encodeToByteArray().size} bytes is too large for 'adb shell' (the command line is limited to ~128 KiB)",
                code = "payload_too_large",
            )
        }
        val result = adb.run(
            listOf(
                "-s", serial, "shell", "content", "call",
                "--uri", "content://$applicationId.kora",
                "--method", Framing.METHOD,
                "--arg", argument,
            ),
        )
        if (result.exitCode != 0) {
            throw DeviceException("adb failed (exit ${result.exitCode}): ${firstLine(result.output)}")
        }
        return when (val outcome = Framing.parse(result.output)) {
            is Framing.Outcome.Answer -> outcome.message
            is Framing.Outcome.Refused -> throw DeviceException("The app refused the call: ${outcome.code}: ${outcome.message}", outcome.code)
            is Framing.Outcome.Unreadable ->
                throw DeviceException("Unexpected answer from the app (is $applicationId installed with Kora?): ${outcome.detail}")
        }
    }

    private fun firstLine(text: String) = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MAX_DETAIL)

    companion object {
        /** `adb shell` joins the arguments into one command line, which the kernel limits to 128 KiB; stay well under. */
        const val MAX_ARGUMENT_CHARS = 100_000

        private const val MAX_DETAIL = 300

        /** Dotted Java package segments: the only characters that reach the remote shell inside the content URI. */
        val APPLICATION_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
