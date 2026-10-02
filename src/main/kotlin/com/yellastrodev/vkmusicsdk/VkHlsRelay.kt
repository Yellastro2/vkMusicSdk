package com.yellastrodev.vkmusicsdk

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loopback HLS для JVM/Android: переписывает URI и расшифровывает AES-128 сегменты.
 * JavaFX получает обычный HLS без EXT-X-KEY. Медиа не перекодируется и не сохраняется.
 * Нюансы sequence-IV, смены ключей и BYTERANGE сверены с vkpymusic/m3u8converter.py.
 */
class VkHlsRelay internal constructor(
    private val fetchOverride: ((String, String?) -> ByteArray)?,
) : Closeable {
    /** Создаёт relay с HTTPS-транспортом; fixture-транспорт используется только тестами. */
    constructor() : this(null)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "vk-hls-resource").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val resources = ConcurrentHashMap<String, Resource>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val playlists = ConcurrentHashMap<String, String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    private var reportError: (String) -> Unit = {}
    private val acceptor = Thread({ acceptConnections() }, "vk-hls-accept").apply {
        isDaemon = true
        start()
    }

    /** Ресурс HLS, опционально зашифрованный и ограниченный byte range. */
    private data class Resource(
        val url: String,
        val playlist: Boolean = false,
        val range: String? = null,
        val keyUrl: String? = null,
        val iv: ByteArray? = null,
    )

    /** Регистрирует HTTPS-плейлист с непредсказуемым локальным URL. */
    fun open(url: String): String {
        check(!closed)
        require(URI(url).scheme == "https") { "VK должен вернуть HTTPS-ссылку аудио" }
        return register(Resource(url, playlist = true))
    }

    /** Настраивает безопасную диагностику до open: передаёт этап/тип ошибки без URL и ключей. */
    fun onError(reporter: (String) -> Unit) { reportError = reporter }

    /** Даёт ресурсу непрозрачный адрес; подписанные upstream URL не попадают в плеер. */
    private fun register(resource: Resource): String {
        val path = "/${UUID.randomUUID()}${if (resource.playlist) ".m3u8" else ".ts"}"
        resources[path] = resource
        return "http://127.0.0.1:${server.localPort}$path"
    }

    /** Принимает только loopback-соединения, передавая каждое отдельному worker. */
    private fun acceptConnections() {
        while (!closed) {
            try {
                val socket = server.accept()
                sockets.add(socket)
                workers.execute { serve(socket) }
            } catch (_: IOException) {
                if (!closed) close()
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                break
            }
        }
    }

    /** Обслуживает GET/HEAD, включая range по уже расшифрованному сегменту. */
    private fun serve(socket: Socket) {
        try {
            socket.use { connection ->
                connection.soTimeout = 10_000
                val reader = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = reader.readLine()?.split(' ') ?: return
                if (request.size < 2) return
                val headers = mutableMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    headerBytes += line.length
                    if (headerBytes > 16_384) return
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val output = connection.getOutputStream()
                val resource = resources[request[1]]
                if (resource == null || request[0] !in setOf("GET", "HEAD")) {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                try {
                    val bytes = if (resource.playlist) playlists.getOrPut(resource.url) {
                        rewrite(resource.url, fetch(resource).toString(Charsets.UTF_8))
                    }.toByteArray(Charsets.UTF_8)
                    else readMedia(resource)
                    val range = headers["range"]?.takeIf { !resource.playlist }
                        ?.let { parseClientRange(it, bytes.size) }
                    val content = if (range != null) bytes.copyOfRange(range.first, range.last + 1) else bytes
                    val response = buildString {
                        append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                        append("Content-Type: ${if (resource.playlist) "application/vnd.apple.mpegurl" else "video/mp2t"}\r\n")
                        append("Content-Length: ${content.size}\r\nAccept-Ranges: bytes\r\n")
                        range?.let { append("Content-Range: bytes ${it.first}-${it.last}/${bytes.size}\r\n") }
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(response.toByteArray(Charsets.US_ASCII))
                    if (request[0] == "GET") output.write(content)
                } catch (error: Exception) {
                    runCatching { reportError("[serveVkHls] Ошибка ресурса VK HLS: ${error.javaClass.simpleName}") }
                    output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        } catch (_: IOException) {
            // Плеер может закрыть соединение при seek или смене трека.
        } finally {
            sockets.remove(socket)
        }
    }

    /** Загружает ресурс; byte range применяется к ciphertext до расшифровки. */
    private fun fetch(resource: Resource): ByteArray {
        require(URI(resource.url).scheme == "https") { "Неподдерживаемая схема HLS" }
        fetchOverride?.let { return it(resource.url, resource.range) }
        val request = Request.Builder().url(resource.url).header("User-Agent", VkApiClient.USER_AGENT)
        resource.range?.let { request.header("Range", it) }
        return http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("VK HLS HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("Пустой сегмент VK")
            if (resource.range != null && response.code != 206) {
                val range = parseClientRange(resource.range, bytes.size)
                bytes.copyOfRange(range.first, range.last + 1)
            } else bytes
        }
    }

    /** Расшифровывает AES-CBC с PKCS7; ключи хранятся только в памяти relay. */
    private fun readMedia(resource: Resource): ByteArray {
        val bytes = fetch(resource)
        val keyUrl = resource.keyUrl ?: return bytes
        val key = keys.getOrPut(keyUrl) { fetch(Resource(keyUrl)) }
        require(key.size == 16) { "Некорректный ключ VK HLS" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(requireNotNull(resource.iv)))
        val decoded = cipher.doFinal(bytes)
        val padding = decoded.lastOrNull()?.toInt()?.and(0xff) ?: return decoded
        if (padding !in 1..16) return decoded
        require(decoded.takeLast(padding).all { (it.toInt() and 0xff) == padding }) { "Некорректный padding VK HLS" }
        return decoded.copyOf(decoded.size - padding)
    }

    /** Переписывает master/media playlist; убирает ключи и upstream BYTERANGE после обработки. */
    internal fun rewrite(base: String, playlist: String): String {
        require(playlist.trimStart().startsWith("#EXTM3U")) { "Некорректный HLS VK" }
        var sequence = 0L
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var range: String? = null
        var nextIsPlaylist = false
        val offsets = mutableMapOf<String, Long>()
        return buildString {
            playlist.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                        sequence = line.substringAfter(':').toLong()
                        appendLine(line)
                    }
                    line.startsWith("#EXT-X-KEY:") -> {
                        val attrs = attributes(line)
                        val method = attrs["METHOD"]
                        require(method == "NONE" || (method == "AES-128" && attrs["KEYFORMAT"].let { it == null || it == "identity" })) {
                            "Неподдерживаемое шифрование VK HLS"
                        }
                        keyUrl = if (method == "NONE") null else resolve(base, requireNotNull(attrs["URI"]))
                        keyIv = attrs["IV"]?.let(::parseIv)
                    }
                    line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':')
                    line.startsWith("#EXT-X-MAP:") -> {
                        val attrs = attributes(line)
                        val url = resolve(base, requireNotNull(attrs["URI"]))
                        require(keyUrl == null || keyIv != null) { "Зашифрованный init map требует IV" }
                        val local = register(Resource(url, range = upstreamRange(attrs["BYTERANGE"], url, offsets),
                            keyUrl = keyUrl, iv = keyIv))
                        appendLine("#EXT-X-MAP:URI=\"$local\"")
                    }
                    line.startsWith("#EXT-X-STREAM-INF:") -> { nextIsPlaylist = true; appendLine(line) }
                    line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                        appendLine(Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                            "URI=\"${register(Resource(resolve(base, match.groupValues[1]), playlist = true))}\""
                        })
                    }
                    line.isNotBlank() && !line.startsWith('#') -> {
                        val url = resolve(base, line)
                        val iv = if (keyUrl == null) null else keyIv ?: ByteBuffer.allocate(16).putLong(0).putLong(sequence).array()
                        appendLine(register(Resource(url, nextIsPlaylist, upstreamRange(range, url, offsets), keyUrl, iv)))
                        if (!nextIsPlaylist) sequence++
                        nextIsPlaylist = false
                        range = null
                    }
                    else -> appendLine(line)
                }
            }
        }
    }

    /** Разбирает атрибуты HLS, сохраняя запятые внутри кавычек URI. */
    private fun attributes(line: String): Map<String, String> =
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':'))
            .associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** Разрешает относительный адрес относительно фактического playlist URL. */
    private fun resolve(base: String, path: String): String = URI(base).resolve(path).toString()

    /** Переводит HLS length@offset в HTTP Range с учётом implicit offset того же ресурса. */
    private fun upstreamRange(value: String?, url: String, offsets: MutableMap<String, Long>): String? {
        if (value == null) return null
        val length = value.substringBefore('@').toLong()
        val start = value.substringAfter('@', "").toLongOrNull() ?: offsets[url] ?: 0
        require(length > 0 && start >= 0)
        offsets[url] = start + length
        return "bytes=$start-${start + length - 1}"
    }

    /** Разбирает явный IV в 16 байт, дополняя ведущие нули. */
    private fun parseIv(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        require(hex.length == 32)
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Ограничивает диапазон размером текущего сегмента, поддерживает suffix/open range. */
    private fun parseClientRange(value: String, size: Int): IntRange {
        require(value.startsWith("bytes=") && !value.contains(',') && size > 0)
        val fields = value.removePrefix("bytes=").split('-', limit = 2)
        require(fields.size == 2)
        val start = fields[0].toIntOrNull() ?: (size - fields[1].toInt()).coerceAtLeast(0)
        val end = if (fields[0].isEmpty()) size - 1 else fields[1].toIntOrNull()?.coerceAtMost(size - 1) ?: size - 1
        require(start in 0 until size && end >= start)
        return start..end
    }

    /** Закрывает listener, активные сокеты и upstream; безопасен при повторном вызове. */
    override fun close() {
        if (closed) return
        closed = true
        server.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        resources.clear()
        keys.clear()
        playlists.clear()
    }
}
