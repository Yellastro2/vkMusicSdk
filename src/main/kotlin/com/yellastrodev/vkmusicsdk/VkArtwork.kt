package com.yellastrodev.vkmusicsdk

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Загрузка VK-обложек без Яндекс-токена, URL-шаблонов и proxy-настроек ЯМ. */
object VkArtwork {
    /** Загружает ограниченный объём JPEG/PNG, закрывая соединение после чтения. */
    suspend fun load(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (URI(url).scheme != "https") return@withContext null
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode !in 200..299) return@withContext null
            connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (buffer.size() + count > 5 * 1024 * 1024) return@withContext null
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: java.io.IOException) { null }
        finally { connection.disconnect() }
    }
}
