package com.yellastrodev.vkmusicsdk

import java.io.Closeable
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.Protocol
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loopback HLS для JVM/Android: переписывает URI и расшифровывает AES-128 сегменты.
 * JavaFX получает обычный HLS без EXT-X-KEY. Корни очереди могут получать URL лениво.
 * Медиа не перекодируется; необязательный кеш сохраняет готовые сегменты, без AES-ключей.
 * Диагностика ответа показывает MIME и признаки контейнера без дампа медиаданных.
 * Опциональный desktop-режим снимает TS/PES с MP3 перед HTTP-ответом; кеш/bundle сохраняют исходный plaintext.
 * Явная загрузка экспортирует полный локальный bundle, который новый relay открывает без сети.
 * Параллельные запросы объединяются; сброс попытки отменяет незавершённые HTTP и ожидания.
 * HLS-запрос и отсутствие данных ограничены 20с; общий бюджет затыка контролирует плеер.
 * Нюансы sequence-IV, смены ключей и BYTERANGE сверены с vkpymusic/m3u8converter.py.
 */
class VkHlsRelay internal constructor(
    private val fetchOverride: ((String, String?) -> ByteArray)?,
    private val mp3HlsSegments: Boolean,
) : Closeable {
    /** Создаёт HTTPS relay; JavaFX может запрашивать MP3-сегменты вместо MP3-в-TS. */
    constructor(mp3HlsSegments: Boolean = false) : this(null, mp3HlsSegments)
    /** Сохраняет прежний fixture-конструктор без преобразования сегментов. */
    internal constructor(fetchOverride: (String, String?) -> ByteArray) : this(fetchOverride, false)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "vk-hls-resource").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .eventListenerFactory { call -> NetworkEvents(call.request().tag(NetworkTrace::class.java)) }.build()
    private val resources = ConcurrentHashMap<String, Resource>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val playlists = ConcurrentHashMap<String, String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    private var reportError: (String) -> Unit = {}
    private var reportDiagnostic: (String) -> Unit = {}
    private val requestCounter = AtomicLong()
    private val generation = AtomicLong()
    private val requestGeneration = ThreadLocal<Long>()
    private val pendingLock = Any()
    private val pending = ConcurrentHashMap<String, FutureTask<ByteArray>>()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private var audioCache: VkAudioCache? = null
    private val acceptor = Thread({ acceptConnections() }, "vk-hls-accept").apply {
        isDaemon = true
        start()
    }

    /** Исходный ресурс HLS и флаг снятия TS-обёртки только при отдаче desktop-плееру. */
    private data class Resource(
        val url: String,
        val playlist: Boolean = false,
        val range: String? = null,
        val keyUrl: String? = null,
        val iv: ByteArray? = null,
        val deferred: DeferredAudio? = null,
        val contentType: String = "video/mp2t",
        val audioId: String? = null,
        val cacheKey: String? = null,
        val localFile: File? = null,
        val savedAudio: (() -> File?)? = null,
        val mp3Segment: Boolean = false,
    )

    /** Объединяет HEAD/GET одного открытия; при повторном запуске позже обновляет media URL. */
    private class DeferredAudio(private val resolver: () -> String, initialUrl: String?) {
        private var url = initialUrl
        private var resolvedAt = if (initialUrl == null) 0L else System.nanoTime()
        /** Резолвер выполняется только на HTTP-worker, без блокирования потока UI. */
        @Synchronized fun getUrl(): String {
            val now = System.nanoTime()
            if (url == null || now - resolvedAt >= TimeUnit.SECONDS.toNanos(30)) {
                val fresh = resolver()
                require(URI(fresh).scheme == "https") { "VK должен вернуть HTTPS-ссылку аудио" }
                url = fresh
                resolvedAt = now
            }
            return requireNotNull(url)
        }
    }

    /** Регистрирует HTTPS-плейлист с непредсказуемым локальным URL. */
    fun open(url: String, audioId: String? = null): String {
        check(!closed)
        require(URI(url).scheme == "https") { "VK должен вернуть HTTPS-ссылку аудио" }
        return register(Resource(url, playlist = true, audioId = audioId))
    }

    /** Регистрирует ленивый трек; перед сетью проверяет savedAudio, включая сохранение после создания очереди. */
    fun openDeferredAudio(isHls: Boolean, initialUrl: String? = null, audioId: String? = null,
        savedAudio: (() -> File?)? = null, resolver: () -> String): String {
        check(!closed)
        initialUrl?.let { require(URI(it).scheme == "https") }
        return register(Resource("", playlist = isHls,
            deferred = DeferredAudio(resolver, initialUrl), contentType = "audio/mpeg", audioId = audioId, savedAudio = savedAudio))
    }

    /** Подключает постоянный кеш до регистрации корней; ошибки диска не прерывают воспроизведение. */
    fun useAudioCache(cache: VkAudioCache?) { audioCache = cache }

    /** Открывает bundle без изменения файлов; desktop получает MP3 вместо сохранённых TS-сегментов. */
    fun openSavedAudio(root: File): String {
        require(root.isFile && root.length() > 0)
        return register(Resource("", playlist = root.extension == "m3u8", localFile = root,
            contentType = if (root.extension == "mp3") "audio/mpeg" else "video/mp2t",
            mp3Segment = mp3HlsSegments && root.extension == "ts"))
    }

    /** Сохраняет конечный HLS-граф с plaintext-сегментами либо прямой файл; URL/ключи на диск не записываются. */
    fun downloadAudio(url: String, isHls: Boolean, directory: File, audioId: String,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }): File {
        require(URI(url).scheme == "https")
        directory.mkdirs()
        val saved = mutableMapOf<Resource, File>()
        var downloaded = 0L
        onProgress(0, null)
        /** Обходит также master/rendition; ограничивает глубину и число файлов повреждённого графа. */
        fun save(resource: Resource, depth: Int): File {
            require(depth <= 8 && saved.size < 10_000) { "Слишком большой HLS VK" }
            saved[resource]?.let { return it }
            val file = File(directory, "resource-${saved.size}.${if (resource.playlist) "m3u8" else if (resource.contentType == "audio/mpeg") "mp3" else "ts"}")
            saved[resource] = file
            if (resource.playlist) {
                val original = fetch(resource).toString(Charsets.UTF_8)
                require(original.contains("#EXT-X-ENDLIST") || original.contains("#EXT-X-STREAM-INF:") || original.contains("#EXT-X-MEDIA:")) {
                    "Нельзя сохранить незавершённый HLS VK"
                }
                val rewritten = rewrite(resource.url, original, audioId)
                val local = Regex("http://127\\.0\\.0\\.1:${server.localPort}/[A-Za-z0-9.-]+").replace(rewritten) { match ->
                    val child = requireNotNull(resources[URI(match.value).path])
                    save(child, depth + 1).name
                }
                require(!Regex("(?i)https?://|#EXT-X-(SESSION-)?KEY:").containsMatchIn(local)) {
                    "HLS VK содержит неподдерживаемый внешний ресурс"
                }
                file.writeText(local, Charsets.UTF_8)
            } else {
                val bytes = readMedia(resource)
                require(bytes.isNotEmpty()) { "Пустое аудио VK" }
                file.writeBytes(bytes)
                downloaded += bytes.size
                onProgress(downloaded, null)
            }
            return file
        }
        val root = save(Resource(url, playlist = isHls, audioId = audioId, contentType = "audio/mpeg"), 0)
        require(downloaded > 0)
        onProgress(downloaded, downloaded)
        return root
    }

    /** Переписывает только относительные ссылки внутри сохранённого каталога, не разрешая выход из него. */
    private fun rewriteSaved(root: File): String {
        val directory = root.parentFile.canonicalFile
        /** Превращает имя локального ресурса в непрозрачный HTTP URI. */
        fun local(path: String): String {
            require(!path.contains('/') && !path.contains('\\') && !path.contains(':'))
            val file = File(directory, path).canonicalFile
            require(file.parentFile == directory && file.isFile)
            return openSavedAudio(file)
        }
        return root.readLines(Charsets.UTF_8).joinToString("\n", postfix = "\n") { line ->
            if (line.isNotBlank() && !line.startsWith('#')) local(line.trim())
            else Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${local(it.groupValues[1])}\"" }
        }
    }

    /** Настраивает безопасную диагностику до open: передаёт этап/тип ошибки без URL и ключей. */
    fun onError(reporter: (String) -> Unit) { reportError = reporter }

    /** Настраивает подробные события без URL/токенов; отдельно от предупреждений. */
    fun onDiagnostic(reporter: (String) -> Unit) { reportDiagnostic = reporter }

    /** Передаёт диагностическое событие без влияния ошибки логгера на воспроизведение. */
    private fun diagnostic(message: String) { runCatching { reportDiagnostic(message) } }

    /** Одна HTTP-загрузка с безопасным числовым id и последним этапом сетевого соединения. */
    private class NetworkTrace(val id: Long) { @Volatile var phase = "соединение" }

    /** Запоминает последний этап OkHttp, не записывая адреса, заголовки или содержимое запросов. */
    private class NetworkEvents(private val trace: NetworkTrace?) : EventListener() {
        /** Отмечает начало разрешения DNS. */
        override fun dnsStart(call: Call, domainName: String) { trace?.phase = "DNS" }
        /** Отмечает установку TCP-соединения. */
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { trace?.phase = "TCP" }
        /** Отмечает TLS handshake. */
        override fun secureConnectStart(call: Call) { trace?.phase = "TLS" }
        /** После соединения ожидает заголовки ответа. */
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            trace?.phase = "заголовки"
        }
        /** Отмечает ожидание ответа, включая повторное использование соединения. */
        override fun requestHeadersEnd(call: Call, request: Request) { trace?.phase = "заголовки" }
    }

    /** Отменяет только текущие чтения; зарегистрированная очередь, готовый кеш и offline-bundle сохраняются. */
    fun cancelPendingRequests() {
        synchronized(pendingLock) {
            generation.incrementAndGet()
            val count = pending.size
            pending.values.forEach { it.cancel(true) }
            pending.clear()
            activeCalls.forEach { it.cancel() }
            http.dispatcher.cancelAll()
            sockets.forEach { runCatching { it.close() } }
            diagnostic("[cancelVkRequests] Чтения VK отменены: ресурсов=$count")
        }
    }

    /** Не позволяет обработчику отменённой попытки запустить новую загрузку после сброса. */
    private fun ensureCurrentRequest() {
        if (closed || Thread.currentThread().isInterrupted || requestGeneration.get()?.let { it != generation.get() } == true) {
            throw InterruptedIOException("Чтение VK отменено")
        }
    }

    /** Один worker выполняет загрузку, остальные ожидают тот же результат; ошибочный результат не кешируется. */
    private fun sharedLoad(key: String, loader: () -> ByteArray): ByteArray {
        val task = FutureTask<ByteArray> { ensureCurrentRequest(); loader() }
        val existing = synchronized(pendingLock) {
            ensureCurrentRequest()
            pending.putIfAbsent(key, task)
        }
        val selected = existing ?: task
        if (existing == null) task.run() else diagnostic("[joinVkResource] Ожидаем уже выполняющееся чтение VK")
        try { return selected.get() }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: IOException("Ошибка чтения VK")) }
        finally { if (existing == null) pending.remove(key, task) }
    }

    /** Даёт непрозрачный адрес с расширением фактического ответа, нужным HLS-парсеру JavaFX. */
    private fun register(resource: Resource): String {
        check(!closed)
        val path = "/${UUID.randomUUID()}${if (resource.playlist) ".m3u8" else if (resource.deferred != null || resource.mp3Segment || resource.contentType == "audio/mpeg") ".mp3" else ".ts"}"
        resources[path] = resource
        return "http://127.0.0.1:${server.localPort}$path"
    }

    /** Принимает только loopback-соединения, передавая каждое отдельному worker. */
    private fun acceptConnections() {
        while (!closed) {
            try {
                val socket = server.accept()
                val acceptedAt = System.nanoTime()
                val acceptedGeneration = generation.get()
                sockets.add(socket)
                try {
                    workers.execute {
                        requestGeneration.set(acceptedGeneration)
                        try { serve(socket, acceptedAt) } finally { requestGeneration.remove() }
                    }
                } catch (error: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                    throw error
                }
            } catch (_: IOException) {
                if (!closed) close()
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                break
            }
        }
    }

    /** Обслуживает GET/HEAD; desktop-сегмент преобразуется после plaintext-кеша, до Range/Content-Length. */
    private fun serve(socket: Socket, acceptedAt: Long) {
        val requestId = requestCounter.incrementAndGet()
        val startedAt = acceptedAt
        diagnostic("[serveVkHls] Запрос $requestId принят worker: ожидание=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAt)}мс")
        var stage = "запрос плеера"
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
                    ensureCurrentRequest()
                    stage = "постоянное хранение"
                    val savedFile = resource.savedAudio?.invoke()
                    stage = "получение ссылки VK"
                    diagnostic("[resolveVkAudio] Запрос $requestId: получаем ссылку либо постоянный файл")
                    val resolved = if (savedFile != null) resource.copy(url = "", deferred = null,
                        playlist = savedFile.extension == "m3u8", localFile = savedFile)
                    else resource.deferred?.let { deferred ->
                        val url = sharedLoad("resolve:${request[1]}") { deferred.getUrl().toByteArray(Charsets.UTF_8) }
                        resource.copy(url = url.toString(Charsets.UTF_8), deferred = null)
                    } ?: resource
                    stage = if (resolved.playlist) "HLS-плейлист" else "аудио/кеш/расшифровка"
                    diagnostic("[serveVkHls] Запрос $requestId: метод=${request[0]}, ресурс=$stage, локальный=${resolved.localFile != null}")
                    val identity = "${resolved.audioId}:${resolved.localFile ?: resolved.url}:${resolved.range}:${resolved.keyUrl}:${resolved.iv?.joinToString(",")}"
                    val originalBytes = sharedLoad("resource:${resolved.playlist}:$identity") {
                        if (resolved.playlist) playlists.getOrPut("${resolved.audioId}:${resolved.localFile ?: resolved.url}") {
                            resolved.localFile?.let(::rewriteSaved)
                                ?: rewrite(resolved.url, fetch(resolved).toString(Charsets.UTF_8), resolved.audioId)
                        }.toByteArray(Charsets.UTF_8) else readMedia(resolved)
                    }
                    val bytes = if (resolved.mp3Segment) {
                        stage = "снятие TS-обёртки MP3"
                        VkMpegTsMp3.extract(originalBytes).also {
                            diagnostic("[prepareVkMp3] Запрос $requestId: исходныхБайт=${originalBytes.size}, MP3-байт=${it.size}")
                        }
                    } else originalBytes
                    ensureCurrentRequest()
                    stage = "отдача плееру"
                    val range = headers["range"]?.takeIf { !resolved.playlist }
                        ?.let { parseClientRange(it, bytes.size) }
                    val content = if (range != null) bytes.copyOfRange(range.first, range.last + 1) else bytes
                    val response = buildString {
                        append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                        append("Content-Type: ${if (resolved.playlist) "application/vnd.apple.mpegurl" else if (resolved.mp3Segment) "audio/mpeg" else resolved.contentType}\r\n")
                        append("Content-Length: ${content.size}\r\nAccept-Ranges: bytes\r\n")
                        range?.let { append("Content-Range: bytes ${it.first}-${it.last}/${bytes.size}\r\n") }
                        append("Connection: close\r\n\r\n")
                    }
                    diagnostic("[replyVkMedia] Запрос $requestId: HTTP=${if (range != null) 206 else 200}, " +
                        "тип=${if (resolved.playlist) "HLS" else if (resolved.mp3Segment) "audio/mpeg" else resolved.contentType}, байт=${content.size}" +
                        if (resolved.playlist) "" else ", ${mediaSignature(bytes)}")
                    output.write(response.toByteArray(Charsets.US_ASCII))
                    if (request[0] == "GET") output.write(content)
                    diagnostic("[serveVkHls] Запрос $requestId завершён: байт=${content.size}, время=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}мс")
                } catch (error: Exception) {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    if (closed || requestGeneration.get() != generation.get() || error is InterruptedException ||
                        (error is java.util.concurrent.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException)) {
                        diagnostic("[serveVkHls] Запрос $requestId отменён: этап=$stage, время=${elapsed}мс")
                    } else runCatching { reportError("[serveVkHls] Запрос $requestId: этап=$stage, время=${elapsed}мс, ошибка=${error.javaClass.simpleName}") }
                    output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        } catch (error: IOException) {
            diagnostic("[serveVkHls] Соединение $requestId закрыто: этап=$stage, время=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}мс, тип=${error.javaClass.simpleName}")
        } finally {
            sockets.remove(socket)
        }
    }

    /** Объединяет одинаковые сетевые чтения; byte range применяется к ciphertext до расшифровки. */
    private fun fetch(resource: Resource, kind: String = if (resource.playlist) "плейлист" else "аудио"): ByteArray =
        sharedLoad("network:${resource.url}:${resource.range}") { fetchNetwork(resource, kind) }

    /** Логирует этап/байты: HLS ограничен 20с, полный прямой аудиофайл сохраняет бюджет 25с. */
    private fun fetchNetwork(resource: Resource, kind: String): ByteArray {
        ensureCurrentRequest()
        require(URI(resource.url).scheme == "https") { "Неподдерживаемая схема HLS" }
        fetchOverride?.let { return it(resource.url, resource.range) }
        val trace = NetworkTrace(requestCounter.incrementAndGet())
        val startedAt = System.nanoTime()
        var received = 0L
        diagnostic("[fetchVkResource] Запрос ${trace.id}: ресурс=$kind, host=${URI(resource.url).host}, диапазон=${resource.range != null}")
        val request = Request.Builder().url(resource.url).header("User-Agent", VkApiClient.USER_AGENT).tag(NetworkTrace::class.java, trace)
        resource.range?.let { request.header("Range", it) }
        val call = synchronized(pendingLock) {
            ensureCurrentRequest()
            http.newCall(request.build()).also {
                if (!resource.playlist && resource.contentType == "audio/mpeg") it.timeout().timeout(25, TimeUnit.SECONDS)
                activeCalls.add(it)
            }
        }
        try { return call.execute().use { response ->
            diagnostic("[fetchVkResource] Запрос ${trace.id}: HTTP=${response.code}, ожидаетсяБайт=${response.body?.contentLength()}, время=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}мс")
            if (!response.isSuccessful) throw IOException("VK HLS HTTP ${response.code}")
            trace.phase = "тело ответа"
            val body = response.body ?: throw IOException("Пустой сегмент VK")
            val bytes = body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                val collected = java.io.ByteArrayOutputStream()
                while (true) {
                    ensureCurrentRequest()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    collected.write(buffer, 0, count)
                }
                collected.toByteArray()
            }
            require(body.contentLength() < 0 || body.contentLength() == received) { "Неполный ресурс VK" }
            diagnostic("[fetchVkResource] Запрос ${trace.id} завершён: байт=$received, время=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}мс")
            if (resource.range != null && response.code != 206) {
                val range = parseClientRange(resource.range, bytes.size)
                bytes.copyOfRange(range.first, range.last + 1)
            } else bytes
        }
        } catch (error: Exception) {
            if (!closed && requestGeneration.get()?.let { it != generation.get() } != true && !call.isCanceled()) {
                runCatching { reportError("[fetchVkResource] Запрос ${trace.id}: ресурс=$kind, этап=${trace.phase}, байт=$received, время=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}мс, ошибка=${error.javaClass.simpleName}") }
            }
            throw error
        } finally { activeCalls.remove(call) }
    }

    /** Читает постоянный файл либо кеш, публикуя сетевые байты только после успешной расшифровки. */
    private fun readMedia(resource: Resource): ByteArray {
        resource.localFile?.let { return it.readBytes() }
        val cacheKey = resource.cacheKey ?: resource.audioId?.let {
            digest("vk-audio-v1:$it:direct:${stableUrl(resource.url)}")
        }
        if (cacheKey != null) {
            try {
                audioCache?.read(cacheKey)?.let { return it }
            } catch (error: Exception) {
                reportError("[readMedia] Не удалось прочитать кеш VK: ${error.javaClass.simpleName}")
            }
        }
        val bytes = decodeMedia(resource)
        if (cacheKey != null && bytes.isNotEmpty()) {
            try { audioCache?.write(cacheKey, bytes) }
            catch (error: Exception) {
                reportError("[readMedia] Не удалось сохранить кеш VK: ${error.javaClass.simpleName}")
            }
        }
        return bytes
    }

    /** Распознаёт контейнер по синхробайтам, не выводя содержимое аудио, ключей или медиассылку. */
    private fun mediaSignature(bytes: ByteArray): String {
        val ts = bytes.size >= 377 && listOf(0, 188, 376).all { (bytes[it].toInt() and 0xff) == 0x47 }
        val adts = bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xf6) == 0xf0
        val mp3 = bytes.size >= 4 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe6) == 0xe2
        val id3 = bytes.size >= 3 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()
        val mp4 = bytes.size >= 8 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) in setOf("ftyp", "styp", "moof")
        return "контейнер=${when { ts -> "MPEG-TS"; adts -> "ADTS"; mp3 -> "MP3"; id3 -> "ID3"; mp4 -> "MP4"; else -> "не распознан" }}, остаток188=${bytes.size % 188}"
    }

    /** Расшифровывает AES-CBC с PKCS7; ключи остаются только в памяти relay. */
    private fun decodeMedia(resource: Resource): ByteArray {
        val bytes = fetch(resource)
        val keyUrl = resource.keyUrl ?: return bytes
        val key = keys.getOrPut(keyUrl) { fetch(Resource(keyUrl), "AES-ключ") }
        diagnostic("[decodeVkMedia] Расшифровываем сегмент: байт=${bytes.size}")
        require(key.size == 16) { "Некорректный ключ VK HLS" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(requireNotNull(resource.iv)))
        val decoded = cipher.doFinal(bytes)
        val padding = decoded.lastOrNull()?.toInt()?.and(0xff) ?: return decoded
        if (padding !in 1..16) return decoded
        require(decoded.takeLast(padding).all { (it.toInt() and 0xff) == padding }) { "Некорректный padding VK HLS" }
        return decoded.copyOf(decoded.size - padding)
    }

    /** Переписывает HLS; desktop-регистрация media-сегментов включает снятие TS/PES при HTTP-ответе. */
    internal fun rewrite(base: String, playlist: String, audioId: String? = null): String {
        require(playlist.trimStart().startsWith("#EXTM3U")) { "Некорректный HLS VK" }
        // Подпись в query меняется; пути, тайминги, IV и раскладка сегментов определяют версию аудио.
        val layout = playlist.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            if (line.isNotBlank() && !line.startsWith('#')) stableUrl(resolve(base, line))
            else Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                "URI=\"${stableUrl(resolve(base, match.groupValues[1]))}\""
            }
        }
        val representation = audioId?.takeIf { playlist.lineSequence().any { line -> line.trim() == "#EXT-X-ENDLIST" } }
            ?.let { digest("vk-audio-v1:$it:${stableUrl(base)}:$layout") }
        var resourceIndex = 0
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
                            keyUrl = keyUrl, iv = keyIv,
                            cacheKey = representation?.let { "$it:map:${resourceIndex++}" }))
                        appendLine("#EXT-X-MAP:URI=\"$local\"")
                    }
                    line.startsWith("#EXT-X-STREAM-INF:") -> { nextIsPlaylist = true; appendLine(line) }
                    line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                        appendLine(Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                            "URI=\"${register(Resource(resolve(base, match.groupValues[1]), playlist = true, audioId = audioId))}\""
                        })
                    }
                    line.isNotBlank() && !line.startsWith('#') -> {
                        val url = resolve(base, line)
                        val iv = if (keyUrl == null) null else keyIv ?: ByteBuffer.allocate(16).putLong(0).putLong(sequence).array()
                        appendLine(register(Resource(url, nextIsPlaylist, upstreamRange(range, url, offsets), keyUrl, iv,
                            audioId = if (nextIsPlaylist) audioId else null,
                            cacheKey = if (nextIsPlaylist) null else representation?.let { "$it:segment:${resourceIndex++}" },
                            mp3Segment = mp3HlsSegments && !nextIsPlaylist)))
                        if (!nextIsPlaylist) sequence++
                        nextIsPlaylist = false
                        range = null
                    }
                    else -> appendLine(line)
                }
            }
        }
    }

    /** Убирает параметры подписи; неизвестные параметры и качество сохраняются, предпочитая безопасный cache miss. */
    private fun stableUrl(url: String): String {
        val unsigned = url.substringBefore('#')
        val query = unsigned.substringAfter('?', "").split('&').filter { parameter ->
            parameter.isNotEmpty() && parameter.substringBefore('=').lowercase() !in setOf(
                "extra", "token", "access_token", "signature", "sig", "sign", "expires", "exp", "auth",
            )
        }.joinToString("&")
        return unsigned.substringBefore('?') + if (query.isEmpty()) "" else "?$query"
    }

    /** Формирует непрозрачную версию кеша без записи исходных URL. */
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

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
        cancelPendingRequests()
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
