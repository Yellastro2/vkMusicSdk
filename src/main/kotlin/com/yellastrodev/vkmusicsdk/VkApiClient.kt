package com.yellastrodev.vkmusicsdk

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Независимый token-based порт транспорта vk-audio; cookies и автоматического web-refresh нет. */
class VkApiClient(
    private val accessToken: String,
    private val apiBase: String = "https://api.vk.ru/method/",
    private val version: String = "5.282",
) : Closeable {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    init { require(accessToken.isNotBlank()) { "Нужен токен VK" } }

    /** Выполняет POST и возвращает response; отмена coroutine отменяет HTTP-запрос. */
    suspend fun request(method: String, parameters: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            require(method.matches(Regex("[a-zA-Z]+\\.[a-zA-Z]+")))
            val body = FormBody.Builder().apply {
                parameters.forEach { (key, value) -> add(key, value) }
                add("access_token", accessToken)
                add("v", version)
                add("lang", "ru")
            }.build()
            val call = http.newCall(Request.Builder().url("$apiBase$method")
                .header("User-Agent", USER_AGENT).post(body).build())
            val text = call.awaitText()
            val root = json.parseToJsonElement(text).jsonObject
            root["error"]?.jsonObject?.let { error ->
                throw VkApiException(error["error_code"]?.jsonPrimitive?.intOrNull ?: -1)
            }
            root["response"] ?: throw IOException("VK вернул ответ без response")
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

    /** Обходит все страницы аудио выбранного плейлиста, передавая его access_key. */
    suspend fun getPlaylistTracks(playlist: VkPlaylist): List<VkAudio> {
        val result = mutableListOf<VkAudio>()
        var offset = 0
        while (true) {
            val page: VkAudioPage = json.decodeFromJsonElement(request("audio.get", buildMap {
                put("owner_id", playlist.ownerId.toString()); put("album_id", playlist.id.toString())
                put("offset", offset.toString()); put("count", "200")
                playlist.accessKey?.let { put("access_key", it) }
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
        const val USER_AGENT = "VKAndroidApp/4.13.1-1206 (Android 4.4.3; SDK 19; armeabi; ; ru)"
    }
}

/** Асинхронно читает тело ответа, сохраняя отмену запроса и безопасные ошибки. */
internal suspend fun Call.awaitText(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        /** Возвращает сетевую ошибку без URL, который может содержать приватные параметры. */
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(IOException("Не удалось связаться с VK"))
        }
        /** Закрывает response и передаёт только успешное тело. */
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    if (!it.isSuccessful) throw IOException("VK HTTP ${it.code}")
                    val text = it.body?.string() ?: throw IOException("Пустой ответ VK")
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    })
}
