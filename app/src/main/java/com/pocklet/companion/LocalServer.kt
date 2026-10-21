package com.pocklet.companion

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val MAX_HEADER = 2048
private const val MAX_BODY = 1024

/** Log 20 request terakhir (tanpa isi). */
object RequestLog {
    data class Entry(val time: String, val line: String, val status: Int)

    private val entries = ArrayDeque<Entry>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String, status: Int) {
        entries.addFirst(Entry(fmt.format(Date()), line, status))
        while (entries.size > 20) entries.removeLast()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()
}

class LocalServer(private val ctx: Context) {

    companion object {
        const val PORT = 8765
    }

    private class TooLarge : Exception()

    private class Conversation {
        private val turns = ArrayDeque<Pair<String, String>>()
        private var lastUsed = 0L

        @Synchronized
        fun snapshot(): List<Pair<String, String>> {
            touch()
            return turns.toList()
        }

        @Synchronized
        fun add(question: String, answer: String) {
            touch()
            turns.addLast(question to answer)
            while (turns.size > 10) turns.removeFirst()
        }

        private fun touch() {
            val now = System.currentTimeMillis()
            if (now - lastUsed > 10 * 60_000L) turns.clear()
            lastUsed = now
        }
    }

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)
    private val conversation = Conversation()

    fun start() {
        if (server != null) return
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 16)
        }
        server = ss
        thread(name = "pocklet-accept", isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    val sock = ss.accept()
                    pool.execute { handle(sock) }
                } catch (e: IOException) {
                    if (ss.isClosed) break
                }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 5000
            var status = 500
            var body = "error"
            var line = "-"
            try {
                if (!isLan(s.inetAddress)) {
                    status = 403
                    body = "forbidden"
                } else {
                    val input = BufferedInputStream(s.getInputStream())
                    val head = readHead(input)
                    val lines = head.split("\r\n")
                    val parts = lines[0].split(" ")
                    val method = parts.getOrElse(0) { "" }
                    val path = parts.getOrElse(1) { "" }.substringBefore('?')
                    line = "$method $path"

                    val headers = HashMap<String, String>()
                    for (h in lines.drop(1)) {
                        val i = h.indexOf(':')
                        if (i > 0) headers[h.substring(0, i).trim().lowercase(Locale.US)] = h.substring(i + 1).trim()
                    }
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    if (len < 0 || len > MAX_BODY) throw TooLarge()
                    val reqBody = ByteArray(len)
                    readFully(input, reqBody)

                    val res = dispatch(method, path, headers, reqBody)
                    status = res.first
                    body = res.second
                }
            } catch (e: TooLarge) {
                status = 413
                body = "too large"
            } catch (e: SocketTimeoutException) {
                status = 408
                body = "timeout"
            } catch (e: Exception) {
                status = 500
                body = "error"
            }
            RequestLog.add(line, status)
            try {
                write(s.getOutputStream(), status, body)
            } catch (e: IOException) {
                // klien sudah menutup koneksi
            }
        }
    }

    private fun dispatch(
        method: String,
        path: String,
        headers: Map<String, String>,
        reqBody: ByteArray
    ): Pair<Int, String> {
        if (method == "GET" && path == "/health") return 200 to "ok"

        if (!tokenOk(headers["x-token"] ?: "")) return 401 to "unauthorized"

        return when {
            method == "GET" && path == "/notifs" -> 200 to notifsBody()
            method == "POST" && path == "/ask" -> ask(reqBody)
            else -> 404 to "not found"
        }
    }

    private fun notifsBody(): String {
        val sb = StringBuilder()
        for (n in NotifStore.latest(8)) {
            sb.append(n.id).append('\t')
                .append(clean(n.app, 12)).append('\t')
                .append(clean(n.title, 24)).append('\t')
                .append(clean(n.text, 80)).append('\n')
        }
        return sb.toString()
    }

    private fun ask(reqBody: ByteArray): Pair<Int, String> {
        val question = String(reqBody, Charsets.UTF_8).trim()
        if (question.isEmpty() || question.length > 300) return 400 to "bad request"

        val key = Prefs.apiKey(ctx)
        val model = runBlocking { Prefs.modelNow(ctx) }
        return try {
            val answer = clean(Ollama.chat(key, model, question, conversation.snapshot()), 1200, keepLines = true)
            conversation.add(question, answer)
            200 to answer
        } catch (e: SocketTimeoutException) {
            504 to "timeout"
        } catch (e: OllamaException) {
            503 to clean(e.message ?: "ollama error", 80)
        } catch (e: IOException) {
            503 to "gagal menghubungi ollama"
        }
    }

    private fun tokenOk(given: String): Boolean {
        val expected = Prefs.token(ctx)
        return MessageDigest.isEqual(
            given.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun isLan(addr: InetAddress): Boolean {
        val b = addr.address
        if (b.size != 4) return false
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        return a == 10 || (a == 172 && c in 16..31) || (a == 192 && c == 168)
    }

    private fun readHead(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
            if (buf.size() > MAX_HEADER) throw TooLarge()
        }
        return buf.toString(StandardCharsets.US_ASCII.name())
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun write(out: OutputStream, status: Int, body: String) {
        val payload = body.toByteArray(StandardCharsets.US_ASCII)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: text/plain; charset=us-ascii\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(payload)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        413 -> "Payload Too Large"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Internal Server Error"
    }
}

/** Membersihkan teks ke ASCII. Field notifikasi: TAB/CR/LF jadi spasi. */
fun clean(s: String, max: Int, keepLines: Boolean = false): String {
    val sb = StringBuilder()
    for (c in s) {
        if (sb.length >= max) break
        when {
            keepLines && c == '\n' -> sb.append('\n')
            keepLines && c == '\r' -> {}
            c == '\t' || c == '\r' || c == '\n' -> sb.append(' ')
            c.code > 127 -> sb.append('?')
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
