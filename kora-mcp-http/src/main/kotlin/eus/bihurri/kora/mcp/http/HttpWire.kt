package eus.bihurri.kora.mcp.http

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** A request the wire code refuses; [status] is the HTTP status to answer with. */
internal class HttpException(val status: Int, message: String) : Exception(message)

/** The peer closed the connection before sending a request line: nothing to answer. */
internal class ConnectionClosed : Exception()

/** A parsed request head. Header names are lower-case; values are trimmed. The body is read separately. */
internal class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    private val headers: Map<String, List<String>>,
) {
    /** The value of a header that may appear once; more than one is a malformed (or smuggling) request. */
    fun single(name: String): String? {
        val values = headers[name] ?: return null
        if (values.size > 1) throw HttpException(400, "Duplicate $name header")
        return values.single()
    }

    /** All values of a header joined with commas, as HTTP defines for list-valued headers. */
    fun joined(name: String): String? = headers[name]?.joinToString(",")

    fun has(name: String): Boolean = name in headers
}

/**
 * The little HTTP/1.1 this server speaks: one request per connection, a body only with `Content-Length`, no
 * chunked request bodies, no pipelining. Everything outside that is refused rather than guessed at, because a
 * parser that tries to be lenient is where request-smuggling bugs live.
 */
internal object HttpWire {
    /** Request line plus headers. */
    const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_HEADERS = 64
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val METHOD = Regex("[A-Z]{1,16}")
    private val DIGITS = Regex("[0-9]{1,10}")

    /** Reads up to the blank line that ends the head and parses it. */
    fun readHead(input: InputStream): HttpRequest {
        val bytes = ByteArray(MAX_HEAD_BYTES)
        var length = 0
        while (true) {
            val next = input.read()
            if (next < 0) throw if (length == 0) ConnectionClosed() else EOFException("Closed inside the request head")
            if (length == bytes.size) throw HttpException(431, "Request head too large")
            bytes[length++] = next.toByte()
            if (length >= 4 && bytes[length - 1] == LF && bytes[length - 2] == CR && bytes[length - 3] == LF && bytes[length - 4] == CR) break
        }
        val lines = String(bytes, 0, length - 4, Charsets.ISO_8859_1).split("\r\n")
        val (method, target, version) = parseRequestLine(lines.first())
        if (lines.size - 1 > MAX_HEADERS) throw HttpException(431, "Too many headers")
        val headers = HashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            if (line.any { it == '\r' || it == '\n' || it == '\u0000' }) throw HttpException(400, "Illegal character in a header")
            if (line.startsWith(" ") || line.startsWith("\t")) throw HttpException(400, "Folded headers are not supported")
            val colon = line.indexOf(':')
            val name = if (colon > 0) line.substring(0, colon) else ""
            if (!TOKEN.matches(name)) throw HttpException(400, "Malformed header")
            headers.getOrPut(name.lowercase()) { ArrayList(1) } += line.substring(colon + 1).trim(' ', '\t')
        }
        return HttpRequest(method, target, version, headers)
    }

    private fun parseRequestLine(line: String): Triple<String, String, String> {
        if (line.any { it == '\r' || it == '\n' || it == '\u0000' }) throw HttpException(400, "Malformed request line")
        val parts = line.split(' ')
        if (parts.size != 3 || !METHOD.matches(parts[0])) throw HttpException(400, "Malformed request line")
        val (method, target, version) = parts
        if (version != "HTTP/1.1" && version != "HTTP/1.0") {
            throw HttpException(if (version.startsWith("HTTP/")) 505 else 400, "Unsupported HTTP version")
        }
        if (!target.startsWith("/")) throw HttpException(400, "Only origin-form request targets are supported")
        return Triple(method, target, version)
    }

    /** The body length announced by `Content-Length`, validated; the caller decides what to do about its size. */
    fun contentLength(request: HttpRequest): Long {
        if (request.has("transfer-encoding")) {
            if (request.has("content-length")) throw HttpException(400, "Both Transfer-Encoding and Content-Length")
            throw HttpException(411, "Content-Length is required; chunked request bodies are not supported")
        }
        val raw = request.single("content-length") ?: throw HttpException(411, "Content-Length is required")
        if (!DIGITS.matches(raw)) throw HttpException(400, "Invalid Content-Length")
        return raw.toLong()
    }

    /** Reads exactly [length] bytes. */
    fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("Closed inside the body")
            read += n
        }
        return body
    }

    /** Writes a complete response and closes the exchange (`Connection: close`). */
    fun write(out: OutputStream, status: Int, headers: List<Pair<String, String>>, body: ByteArray) {
        val head = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        100 -> "Continue"
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        406 -> "Not Acceptable"
        411 -> "Length Required"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        417 -> "Expectation Failed"
        421 -> "Misdirected Request"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        505 -> "HTTP Version Not Supported"
        else -> "Status"
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
}
