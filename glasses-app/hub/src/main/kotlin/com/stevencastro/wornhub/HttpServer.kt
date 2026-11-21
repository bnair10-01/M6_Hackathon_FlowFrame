package com.stevencastro.wornhub

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A parsed HTTP request line plus headers. Pure, so it's unit-tested. */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
) {
    /** Token from "Authorization: Bearer …" or the `t` query parameter. */
    val token: String? get() = headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: query["t"]

    companion object {
        /** Reads the request head (up to the blank line). Returns null on a malformed request. */
        fun read(input: InputStream): HttpRequest? {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (++total > 16_384) return null
                if (c == '\n'.code) {
                    val s = line.toString().trimEnd('\r')
                    line.clear()
                    if (s.isEmpty()) break
                    lines.add(s)
                } else {
                    line.append(c.toChar())
                }
            }
            return parse(lines)
        }

        fun parse(lines: List<String>): HttpRequest? {
            val first = lines.firstOrNull()?.split(' ') ?: return null
            if (first.size < 2) return null
            val target = first[1]
            val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val headers = lines.drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            return HttpRequest(first[0].uppercase(), path, query, headers)
        }
    }
}

/** A byte range resolved against a file length (inclusive end). */
data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1

    companion object {
        /**
         * Parses a single "bytes=a-b", "bytes=a-" or "bytes=-n" range. Returns
         * null for no/invalid range (serve the whole file) and throws
         * [IllegalArgumentException] for an unsatisfiable one.
         */
        fun parse(header: String?, fileLength: Long): ByteRange? {
            if (header == null || !header.startsWith("bytes=") || ',' in header) return null
            val spec = header.removePrefix("bytes=").trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-').trim()
            val range = when {
                a.isEmpty() && b.isNotEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    ByteRange((fileLength - n).coerceAtLeast(0), fileLength - 1)
                }
                a.isNotEmpty() -> {
                    val start = a.toLongOrNull() ?: return null
                    val end = if (b.isEmpty()) fileLength - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(fileLength - 1)
                    ByteRange(start, end)
                }
                else -> return null
            }
            require(range.start < fileLength && range.start <= range.end) { "unsatisfiable" }
            return range
        }
    }
}

/** Constant-time token comparison. */
fun tokenMatches(given: String?, expected: String): Boolean =
    given != null && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

/**
 * Minimal HTTP/1.1 server: one thread per connection, GET only, one request
 * per connection (Connection: close). Handlers write their own response.
 */
class HttpServer(
    private val port: Int,
    private val token: () -> String,
    private val handle: (HttpRequest, Response) -> Unit,
) {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null

    fun start() {
        val socket = ServerSocket(port)
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                pool.execute { serve(client) }
            }
        }
        Log.i(TAG, "HTTP on :$port")
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = 15_000
            val out = s.getOutputStream().buffered()
            val response = Response(out)
            try {
                val req = HttpRequest.read(BufferedInputStream(s.getInputStream())) ?: return
                if (req.method != "GET" && req.method != "HEAD") return response.text(405, "Method not allowed")
                if (!tokenMatches(req.token, token())) return response.text(401, "Unauthorized")
                s.soTimeout = 0 // streams can be long-lived
                handle(req, response)
            } catch (e: SocketException) {
                // Client went away.
            } catch (e: Exception) {
                Log.w(TAG, "HTTP error", e)
                runCatching { response.text(500, "Error") }
            } finally {
                runCatching { out.flush() }
            }
        }
    }

    class Response(val out: OutputStream) {
        fun head(status: Int, type: String, length: Long?, extra: Map<String, String> = emptyMap()) {
            val sb = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            sb.append("Content-Type: $type\r\n")
            length?.let { sb.append("Content-Length: $it\r\n") }
            sb.append("Cache-Control: no-store\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
        }

        fun bytes(status: Int, type: String, body: ByteArray) {
            head(status, type, body.size.toLong())
            out.write(body)
        }

        fun text(status: Int, body: String) = bytes(status, "text/plain; charset=utf-8", body.toByteArray())

        fun json(body: String) = bytes(200, "application/json", body.toByteArray())

        /** Sends a file, honouring a Range header (needed for video seeking). */
        fun file(file: File, type: String, rangeHeader: String?) {
            val length = file.length()
            val range = try {
                ByteRange.parse(rangeHeader, length)
            } catch (e: IllegalArgumentException) {
                head(416, "text/plain", 0, mapOf("Content-Range" to "bytes */$length"))
                return
            }
            val start = range?.start ?: 0
            val count = range?.length ?: length
            if (range != null) {
                head(206, type, count, mapOf("Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes ${range.start}-${range.end}/$length"))
            } else {
                head(200, type, length, mapOf("Accept-Ranges" to "bytes"))
            }
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var left = count
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 401 -> "Unauthorized"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 503 -> "Service Unavailable"
            else -> "Error"
        }
    }

    companion object {
        private const val TAG = "WornHub"
    }
}
