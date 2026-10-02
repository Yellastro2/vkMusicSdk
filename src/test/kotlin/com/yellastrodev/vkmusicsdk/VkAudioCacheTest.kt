package com.yellastrodev.vkmusicsdk

import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Проверяет кеш на границе HTTP relay: подписи, расшифровку, разделение треков и отказ диска. */
class VkAudioCacheTest {
    /** Новый relay переиспользует plaintext без загрузки ciphertext/ключа, даже после смены подписи. */
    @Test fun encryptedSegmentsSurviveRelayRestartAndSignatureRotation() {
        val cache = MemoryCache()
        val plain = byteArrayOf(1, 2, 3, 4, 5)
        val key = ByteArray(16) { 7 }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encrypted = cipher.doFinal(plain)
        var mediaRequests = 0
        var keyRequests = 0
        for (signature in listOf("first", "second")) {
            VkHlsRelay { url, _ ->
                when (URL(url).path) {
                    "/index.m3u8" -> ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key?token=$signature\"\n" +
                        "#EXTINF:4,\nsegment.ts?token=$signature\n#EXT-X-ENDLIST\n").toByteArray()
                    "/key" -> { keyRequests++; key }
                    else -> { mediaRequests++; encrypted }
                }
            }.use { relay ->
                relay.useAudioCache(cache)
                val playlist = URL(relay.open("https://media.example/index.m3u8?token=$signature", "1_2")).readText()
                val segment = playlist.lineSequence().first { it.startsWith("http://") }
                assertArrayEquals(plain, URL(segment).readBytes())
            }
        }
        assertEquals(1, mediaRequests)
        assertEquals(1, keyRequests)
        assertArrayEquals(plain, cache.entries.values.single())
    }

    /** Отличающиеся source-id и параметры качества не подменяются уже сохранённым аудио. */
    @Test fun cacheSeparatesTracksAndQuality() {
        val cache = MemoryCache()
        var downloads = 0
        for ((id, quality) in listOf("1_2" to "high", "1_3" to "high", "1_2" to "low", "1_2" to "high")) {
            VkHlsRelay { _, _ -> downloads++; byteArrayOf(9, 8, 7) }.use { relay ->
                relay.useAudioCache(cache)
                val url = relay.openDeferredAudio(false, "https://media.example/a.mp3?quality=$quality", id) {
                    error("Seed должен исключить резолвинг")
                }
                assertArrayEquals(byteArrayOf(9, 8, 7), URL(url).readBytes())
            }
        }
        assertEquals(3, downloads)
    }

    /** Init map и разные byte ranges сохраняются отдельно; смена раскладки заставляет обновить сегменты. */
    @Test fun byteRangesAndLayoutChangesAreSeparated() {
        val cache = MemoryCache()
        var downloads = 0
        val data = ByteArray(12) { it.toByte() }
        for (duration in listOf(4, 4, 5)) {
            VkHlsRelay { url, range ->
                if (url.endsWith(".m3u8")) {
                    ("#EXTM3U\n#EXT-X-MAP:URI=\"data\",BYTERANGE=\"4@0\"\n" +
                        "#EXTINF:$duration,\n#EXT-X-BYTERANGE:4@4\ndata\n" +
                        "#EXTINF:$duration,\n#EXT-X-BYTERANGE:4\ndata\n#EXT-X-ENDLIST\n").toByteArray()
                } else {
                    downloads++
                    val bounds = requireNotNull(range).removePrefix("bytes=").split('-').map { it.toInt() }
                    data.copyOfRange(bounds[0], bounds[1] + 1)
                }
            }.use { relay ->
                relay.useAudioCache(cache)
                val playlist = URL(relay.open("https://media.example/index.m3u8", "1_2")).readText()
                val map = Regex("URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
                val segments = playlist.lineSequence().filter { it.startsWith("http://") }.toList()
                assertArrayEquals(data.copyOfRange(0, 4), URL(map).readBytes())
                assertArrayEquals(data.copyOfRange(4, 8), URL(segments[0]).readBytes())
                assertArrayEquals(data.copyOfRange(8, 12), URL(segments[1]).readBytes())
            }
        }
        assertEquals(6, downloads)
    }

    /** Неполученное аудио не публикуется, а ошибки кеша не мешают успешному запросу. */
    @Test fun diskFailureFallsBackToNetwork() {
        VkHlsRelay { _, _ -> byteArrayOf(10, 20) }.use { relay ->
            relay.useAudioCache(object : VkAudioCache {
                /** Имитирует недоступность диска. */
                override fun read(key: String): ByteArray? = throw java.io.IOException()
                /** Имитирует исчерпание места. */
                override fun write(key: String, bytes: ByteArray) { throw java.io.IOException() }
            })
            val url = relay.openDeferredAudio(false, "https://media.example/a.mp3", "1_2") { error("Seed") }
            assertArrayEquals(byteArrayOf(10, 20), URL(url).readBytes())
        }
        val cache = MemoryCache()
        VkHlsRelay { _, _ -> throw java.io.IOException() }.use { relay ->
            relay.useAudioCache(cache)
            val url = relay.openDeferredAudio(false, "https://media.example/a.mp3", "1_2") { error("Seed") }
            assertThrows(java.io.IOException::class.java) { URL(url).readBytes() }
            assertTrue(cache.entries.isEmpty())
        }
    }

    /** Потокобезопасное fixture-хранилище переживает отдельные экземпляры relay. */
    private class MemoryCache : VkAudioCache {
        val entries = ConcurrentHashMap<String, ByteArray>()
        /** Возвращает полную запись. */
        override fun read(key: String): ByteArray? = entries[key]
        /** Публикует полную запись. */
        override fun write(key: String, bytes: ByteArray) { entries[key] = bytes }
    }
}
