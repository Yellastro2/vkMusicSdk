package com.yellastrodev.vkmusicsdk

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OAuth или браузерный API: web-refresh по необходимости, последовательный тротлинг и диагностический запрет HTTP. */
class VkApiClient(
    private val accessToken: String,
    private val apiBase: String = "https://api.vk.com/method/",
    private val version: String = "5.199",
    private val minRequestIntervalMs: Long = 0,
    private val requestsEnabled: Boolean = true,
    webSession: VkWebSession? = null,
    private val onWebSessionUpdated: (VkWebSession) -> Unit = {},
    private val webTokenUrl: String = "https://login.vk.ru/?act=web_token",
) : Closeable {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(webSession == null).followSslRedirects(webSession == null).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val requestMutex = Mutex()
    private var lastRequestStartedNanos: Long? = null
    @Volatile private var currentWebSession = webSession

    /** Последний снимок для защищённого сохранения; OAuth-клиент возвращает null. */
    val webSession: VkWebSession? get() = currentWebSession

    init {
        require(accessToken.isNotBlank() || webSession != null) { "Нужен токен VK" }
        require(minRequestIntervalMs >= 0) { "Интервал запросов VK не может быть отрицательным" }
    }

    /** Блокирует API в диагностическом режиме; иначе опционально ограничивает частоту и параллелизм. */
    suspend fun request(method: String, parameters: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            check(requestsEnabled) { "Запросы VK API отключены для диагностики авторизации" }
            if (minRequestIntervalMs == 0L && currentWebSession == null) return@withContext executeRequest(method, parameters)
            requestMutex.withLock {
                refreshWebToken(force = false)
                try {
                    executeRequest(method, parameters)
                } catch (error: VkApiException) {
                    if (error.code != 5 || currentWebSession == null) throw error
                    refreshWebToken(force = true)
                    executeRequest(method, parameters)
                }
            }
        }

    /** Получает первый веб-токен без вызовов музыкального API; повторный вход не запускает фоновых таймеров. */
    suspend fun authenticateWebSession(): VkWebSession = withContext(Dispatchers.IO) {
        check(requestsEnabled) { "Запросы VK API отключены для диагностики авторизации" }
        requestMutex.withLock {
            check(currentWebSession != null) { "Браузерная сессия VK отсутствует" }
            refreshWebToken(force = false)
            checkNotNull(currentWebSession)
        }
    }

    /** Ограничивает старты как web_token, так и API одним общим интервалом; ошибки тоже учитываются. */
    private suspend fun awaitRequestTurn() {
        if (minRequestIntervalMs > 0) lastRequestStartedNanos?.let { previous ->
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - previous)
            val remainingMs = minRequestIntervalMs - elapsedMs
            if (remainingMs > 0) delay(remainingMs)
        }
        currentCoroutineContext().ensureActive()
        if (minRequestIntervalMs > 0) lastRequestStartedNanos = System.nanoTime()
    }

    /** Обновляет истекающий токен через cookies, сохраняет ротацию до публикации нового снимка. */
    private suspend fun refreshWebToken(force: Boolean) {
        val session = currentWebSession ?: return
        if (!force && session.accessToken.isNotBlank() && session.expiresAt - System.currentTimeMillis() / 1_000 > 600) return
        awaitRequestTurn()
        val request = Request.Builder().url(webTokenUrl)
            .header("User-Agent", session.userAgent)
            .header("Origin", "https://vk.ru").header("Referer", "https://vk.ru/")
            .header("Cookie", "p=${session.p}; remixsid=${session.remixsid}")
            .post(FormBody.Builder().add("version", "1").add("app_id", "6287487").build()).build()
        val (text, cookies) = http.newCall(request).awaitResponse { response ->
            if (response.code == 401 || response.code == 403) throw VkApiException(5)
            if (!response.isSuccessful) throw IOException("VK HTTP ${response.code}")
            (response.body?.string() ?: throw IOException("Пустой ответ VK")) to
                Cookie.parseAll(response.request.url, response.headers)
        }
        val root = try { json.parseToJsonElement(text).jsonObject }
            catch (_: Exception) { throw IOException("Некорректный ответ авторизации VK") }
        if (root["type"]?.jsonPrimitive?.contentOrNull == "error") {
            val code = root["error_code"]?.jsonPrimitive?.intOrNull
            val unauthorized = root["error_info"]?.jsonPrimitive?.contentOrNull == "unauthorized"
            if (unauthorized || code == 5) throw VkApiException(5)
            throw IOException("VK отклонил обновление веб-токена")
        }
        val updated = try {
            check(root["type"]?.jsonPrimitive?.contentOrNull == "okay")
            val data = root.getValue("data").jsonObject
            val token = data.getValue("access_token").jsonPrimitive.content
            val expires = data.getValue("expires").jsonPrimitive.long
            check(token.isNotBlank() && expires > System.currentTimeMillis() / 1_000)
            VkWebSession(
                p = cookies.lastOrNull { it.name == "p" }?.value ?: session.p,
                remixsid = cookies.lastOrNull { it.name == "remixsid" }?.value ?: session.remixsid,
                userAgent = session.userAgent, accessToken = token, expiresAt = expires,
                userId = data["user_id"]?.jsonPrimitive?.longOrNull ?: session.userId,
            )
        } catch (_: Exception) { throw IOException("Неполный ответ авторизации VK") }
        if (session.userId != null && updated.userId != session.userId) throw VkApiException(5)
        onWebSessionUpdated(updated)
        currentWebSession = updated
    }

    /** Отправляет токен в теле POST; ожидание ответа и HTTP отменяются вместе с coroutine. */
    private suspend fun executeRequest(method: String, parameters: Map<String, String>): JsonElement {
        require(method.matches(Regex("[a-zA-Z]+\\.[a-zA-Z]+")))
        awaitRequestTurn()
        val body = FormBody.Builder().apply {
            parameters.forEach { (key, value) -> add(key, value) }
            add("access_token", currentWebSession?.accessToken ?: accessToken)
            add("v", version)
            add("lang", "ru")
            if (currentWebSession != null) add("client_id", "6287487")
        }.build()
        val call = http.newCall(Request.Builder().url("$apiBase$method")
            .apply { currentWebSession?.let { header("User-Agent", it.userAgent) } }
            .post(body).build())
        val text = call.awaitText()
        val root = json.parseToJsonElement(text).jsonObject
        root["error"]?.jsonObject?.let { error ->
            throw VkApiException(error["error_code"]?.jsonPrimitive?.intOrNull ?: -1)
        }
        return root["response"] ?: throw IOException("VK вернул ответ без response")
    }

    /** Ищет аудио по механике rawSearchAudio из vk-audio. */
    suspend fun search(query: String, offset: Int = 0, count: Int = 50): VkAudioPage =
        json.decodeFromJsonElement(request("audio.search", mapOf(
            "q" to query, "offset" to offset.toString(), "count" to count.coerceIn(1, 100).toString(),
        )))

    /** Возвращает подсказки audio.getSearchSuggestions из vk-audio. */
    suspend fun getSearchSuggestions(query: String): List<String> {
        val response = request("audio.getSearchSuggestions", mapOf("query" to query)).jsonObject
        return json.decodeFromJsonElement(response["suggestions"] ?: JsonArray(emptyList()))
    }

    /** Получает свежие URL аудио, включая access_key при наличии. */
    suspend fun getById(ids: List<String>): List<VkAudio> {
        require(ids.isNotEmpty() && ids.size <= 100)
        val response = request("audio.getById", mapOf("audios" to ids.joinToString(",")))
        val items = if (response is JsonArray) response else response.jsonObject["items"] ?: JsonArray(emptyList())
        return json.decodeFromJsonElement(items)
    }

    /** Возвращает сырой каталог с blocks, audios и playlists, как rawGetSections. */
    suspend fun getAudioCatalog(ownerId: Long? = null): JsonElement = request("catalog.getAudio",
        buildMap { put("need_blocks", "1"); ownerId?.let { put("owner_id", it.toString()) } })

    /** Загружает раздел каталога с токеном следующей страницы. */
    suspend fun getSection(sectionId: String, startFrom: String? = null): JsonElement =
        request("catalog.getSection", buildMap {
            put("section_id", sectionId); startFrom?.let { put("start_from", it) }
        })

    /** Добавляет аудио в музыку пользователя (аналог rawAdd из vk-audio). */
    suspend fun add(audio: VkAudio): JsonElement = request("audio.add", mapOf(
        "owner_id" to audio.ownerId.toString(), "audio_id" to audio.id.toString(),
    ))

    /** Удаляет конкретный экземпляр из музыки пользователя (аналог rawDelete). */
    suspend fun delete(ownerId: Long, audioId: Long): JsonElement = request("audio.delete", mapOf(
        "owner_id" to ownerId.toString(), "audio_id" to audioId.toString(),
    ))

    /** Читает все страницы «Моих треков»; отсутствие album_id отделяет коллекцию от плейлистов. */
    suspend fun getMyTracks(ownerId: Long): List<VkAudio> {
        val result = mutableListOf<VkAudio>()
        var offset = 0
        while (true) {
            val page: VkAudioPage = json.decodeFromJsonElement(request("audio.get", mapOf(
                "owner_id" to ownerId.toString(), "offset" to offset.toString(), "count" to "200",
            )))
            result.addAll(page.items)
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.count) break
        }
        return result.distinctBy { it.fullId }
    }

    /** Возвращает ID добавленного экземпляра: новый формат vk-audio items либо старый числовой response. */
    suspend fun addToMyTracks(audio: VkAudio, userId: Long): VkAudio {
        val response = add(audio)
        val item = (response as? JsonObject)?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
        val newId = item?.get("new_audio_id")?.jsonPrimitive?.longOrNull
            ?: (response as? JsonPrimitive)?.longOrNull
        val owner = item?.get("new_owner_id")?.jsonPrimitive?.longOrNull ?: userId
        require(newId != null && newId > 0 && owner == userId) { "VK не подтвердил добавление аудио" }
        return audio.copy(id = newId, ownerId = owner, accessKey = null)
    }

    /** Удаляет личный экземпляр и проверяет audio_ids нового формата либо response=1 старого API. */
    suspend fun removeFromMyTracks(audio: VkAudio) {
        val response = delete(audio.ownerId, audio.id)
        val ids = (response as? JsonObject)?.get("audio_ids") as? JsonArray
        require(ids?.any { it.jsonPrimitive.content == audio.fullId } == true ||
            (response as? JsonPrimitive)?.intOrNull == 1) { "VK не подтвердил удаление аудио" }
    }

    /** Обходит все страницы плейлистов по примеру vkpymusic. */
    suspend fun getPlaylists(ownerId: Long): List<VkPlaylist> {
        val result = mutableListOf<VkPlaylist>()
        var offset = 0
        while (true) {
            val page = request("audio.getPlaylists", mapOf("owner_id" to ownerId.toString(),
                "offset" to offset.toString(), "count" to "100")).jsonObject
            val items: List<VkPlaylist> = json.decodeFromJsonElement(page["items"] ?: JsonArray(emptyList()))
            result.addAll(items)
            offset += items.size
            if (items.isEmpty() || offset >= (page["count"]?.jsonPrimitive?.intOrNull ?: offset)) break
        }
        return result.distinctBy { "${it.ownerId}_${it.id}" }
    }

    /** Определяет владельца текущего токена, не требуя хранения OAuth callback/user_id. */
    suspend fun getCurrentUserId(): Long = request("users.get").jsonArray.first().jsonObject
        .getValue("id").jsonPrimitive.long

    /** Загружает актуальную метадату плейлиста, в том числе права и обложку. */
    suspend fun getPlaylistById(ownerId: Long, playlistId: Long, accessKey: String? = null): VkPlaylist =
        json.decodeFromJsonElement(request("audio.getPlaylistById", buildMap {
            put("owner_id", ownerId.toString()); put("playlist_id", playlistId.toString())
            accessKey?.takeIf(String::isNotBlank)?.let { put("access_key", it) }
        }))

    /** Создаёт пустой плейлист; VK самостоятельно задаёт его настройки доступности. */
    suspend fun createPlaylist(ownerId: Long, title: String): VkPlaylist {
        require(title.trim().isNotEmpty())
        return json.decodeFromJsonElement(request("audio.createPlaylist", mapOf(
            "owner_id" to ownerId.toString(), "title" to title.trim(),
        )))
    }

    /** Удаляет указанный плейлист, не вызывая удаление его аудиозаписей из фонотеки. */
    suspend fun deletePlaylist(playlist: VkPlaylist) {
        requireMutationResult(request("audio.deletePlaylist", mapOf(
            "owner_id" to playlist.ownerId.toString(), "playlist_id" to playlist.id.toString(),
        )))
    }

    /** Добавляет source-id аудио в плейлист; ключ закрытого аудио передаётся вместе с id. */
    suspend fun addToPlaylist(playlist: VkPlaylist, audio: VkAudio) {
        requireMutationResult(request("audio.addToPlaylist", mapOf(
            "owner_id" to playlist.ownerId.toString(), "playlist_id" to playlist.id.toString(),
            "audio_ids" to audio.requestId,
        )))
    }

    /** Удаляет аудио только из состава плейлиста, сохраняя личную коллекцию пользователя. */
    suspend fun removeFromPlaylist(playlist: VkPlaylist, audio: VkAudio) {
        requireMutationResult(request("audio.removeFromPlaylist", mapOf(
            "owner_id" to playlist.ownerId.toString(), "playlist_id" to playlist.id.toString(),
            "audio_ids" to audio.fullId,
        )))
    }

    /** Не считает response=0/null или явный success=0 успешной записью. */
    private fun requireMutationResult(response: JsonElement) {
        require(response != JsonNull && (response as? JsonPrimitive)?.content != "0" &&
            (response as? JsonPrimitive)?.content != "false" &&
            (response as? JsonObject)?.get("success")?.jsonPrimitive?.content !in listOf("0", "false")) {
            "VK не подтвердил изменение плейлиста"
        }
    }

    /** Обходит все страницы аудио выбранного плейлиста, передавая его access_key. */
    suspend fun getPlaylistTracks(playlist: VkPlaylist): List<VkAudio> {
        val result = mutableListOf<VkAudio>()
        val ownerId = playlist.original?.ownerId ?: playlist.ownerId
        val playlistId = playlist.original?.playlistId ?: playlist.id
        val accessKey = playlist.original?.accessKey ?: playlist.accessKey
        var offset = 0
        while (true) {
            val page: VkAudioPage = json.decodeFromJsonElement(request("audio.get", buildMap {
                put("owner_id", ownerId.toString()); put("album_id", playlistId.toString())
                put("offset", offset.toString()); put("count", "200")
                accessKey?.let { put("access_key", it) }
            }))
            result.addAll(page.items)
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.count) break
        }
        return result
    }

    /** Возвращает страницу рекомендаций, не меняя плейлисты пользователя. */
    suspend fun getRecommendations(offset: Int = 0): VkAudioPage = json.decodeFromJsonElement(
        request("audio.getRecommendations", mapOf("offset" to offset.toString(), "count" to "100")))

    /** Проверяет именно доступ к музыкальному API до сохранения OAuth-сессии. */
    suspend fun validateMusicAccess() { search("Музыка", count = 1) }

    /** Освобождает соединения SDK; клиент должен жить столько же, сколько его сессия. */
    override fun close() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        /** Прежний UA только для медиаресурсов relay; API-запросы его не используют. */
        const val USER_AGENT = "VKAndroidApp/4.13.1-1206 (Android 4.4.3; SDK 19; armeabi; ; ru)"
    }
}

/** Асинхронно читает тело ответа, сохраняя отмену запроса и безопасные ошибки. */
internal suspend fun Call.awaitText(): String = awaitResponse { response ->
    if (!response.isSuccessful) throw IOException("VK HTTP ${response.code}")
    response.body?.string() ?: throw IOException("Пустой ответ VK")
}

/** Обрабатывает закрываемый HTTP-ответ без потери отмены и без URL в сетевой ошибке. */
internal suspend fun <T> Call.awaitResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        /** Возвращает сетевую ошибку без URL, который может содержать приватные параметры. */
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(IOException("Не удалось связаться с VK"))
        }
        /** Закрывает response после обработки и передаёт результат либо безопасную ошибку. */
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    val value = read(it)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    })
}
