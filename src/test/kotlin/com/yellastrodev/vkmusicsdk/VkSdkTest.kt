package com.yellastrodev.vkmusicsdk

import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Проверки ручного OAuth (включая callback без state), API и loopback HLS без аккаунта VK. */
class VkSdkTest {
    /** Callback принимается только для начатой операции и доверенного redirect origin. */
    @Test fun oauthChecksStateAndOrigin() {
        assertEquals("test-token", VkOAuth.parseRedirect(
            "https://oauth.vk.com/blank.html#access_token=test-token&state=abc&expires_in=0", "abc"))
        assertThrows(IllegalArgumentException::class.java) {
            VkOAuth.parseRedirect("https://oauth.vk.com/blank.html#access_token=t&state=other", "abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VkOAuth.parseRedirect("https://example.org/blank.html#access_token=t&state=abc", "abc")
        }
    }

    /** Ручной формат из LavaSrc допускает отсутствие state и переносы длинного URL. */
    @Test fun manualCallbackAcceptsMissingStateAndWrappedUrl() {
        val parsed = VkOAuth.parseManualRedirect(
            "\"https://oauth.vk.com/blank.html#access_token=test-\ntoken&expires_in=0&user_id=42\"", "abc")
        assertEquals("test-token", parsed.accessToken)
        assertFalse(parsed.stateVerified)
        val alias = VkOAuth.parseManualRedirect(
            "https://oauth.vk.ru/blank.html#access_token=test-token&state=abc", "abc")
        assertTrue(alias.stateVerified)
    }

    /** Причины отказа различимы и не включают URL или токен даже при повреждении URI. */
    @Test fun callbackFailuresAreSpecificAndSafe() {
        val cases = listOf(
            "https://oauth.vk.com/blank.html" to VkRedirectFailure.MissingFragment,
            "https://oauth.vk.com/blank.html#expires_in=0" to VkRedirectFailure.MissingToken,
            "https://oauth.vk.com/blank.html#access_token=test-token&state=other" to VkRedirectFailure.StateMismatch,
            "https://oauth.vk.com/blank.html#access_token=test-token%GG" to VkRedirectFailure.InvalidUrl,
            "https://oauth.vk.com/blank.html#error=access_denied&error_description=test-token" to VkRedirectFailure.AuthorizationDenied,
            "https://oauth.vk.com@example.org/blank.html#access_token=test-token" to VkRedirectFailure.WrongRedirect,
        )
        cases.forEach { (value, reason) ->
            val error = assertThrows(VkRedirectException::class.java) {
                VkOAuth.parseManualRedirect(value, "abc")
            }
            assertEquals(reason, error.reason)
            assertFalse(error.message.orEmpty().contains("test-token"))
            assertNull(error.cause)
        }
    }

    /** Поиск сохраняет отрицательный owner_id, неизвестные поля и m3u8 URL. Токен идёт в POST. */
    @Test fun searchPreservesIdentityAndTokenStaysOutOfUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"count":1,"items":[{"id":12,"owner_id":-42,"artist":"Артист","title":"Трек","url":"https://media.example/a.m3u8","unknown":true}]}}"""))
            VkApiClient("test-token", server.url("/method/").toString()).use { client ->
                val track = client.search("Трек & музыка").items.single()
                assertEquals("-42_12", track.fullId)
                assertEquals("https://media.example/a.m3u8", track.url)
                val request = server.takeRequest()
                assertEquals("/method/audio.search", request.path)
                assertEquals("POST", request.method)
                assertTrue(request.body.readUtf8().contains("access_token=test-token"))
            }
        }
    }

    /** API error=5 остаётся типизированной ошибкой без request_params/token в exception. */
    @Test fun apiErrorsDoNotLeakToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"error":{"error_code":5,"error_msg":"test-token","request_params":[{"value":"test-token"}]}}"""))
            VkApiClient("test-token", server.url("/method/").toString()).use { client ->
                try {
                    client.search("query")
                    fail("Ожидалась ошибка VK")
                } catch (error: VkApiException) {
                    assertEquals(5, error.code)
                    assertFalse(error.message.orEmpty().contains("test-token"))
                }
            }
        }
    }

    /** Полная выдача плейлистов обходит offset и сохраняет access_key. */
    @Test fun playlistsTraverseAllPages() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"count":2,"items":[{"id":1,"owner_id":42,"access_key":"key"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"count":2,"items":[{"id":2,"owner_id":42}]}}"""))
            VkApiClient("t", server.url("/method/").toString()).use { client ->
                val playlists = client.getPlaylists(42)
                assertEquals(listOf(1L, 2L), playlists.map { it.id })
                assertEquals("key", playlists.first().accessKey)
                server.takeRequest()
                assertTrue(server.takeRequest().body.readUtf8().contains("offset=1"))
            }
        }
    }

    /** Relay расшифровывает sequence-IV и explicit-IV после смены ключа; seek читает plaintext range. */
    @Test fun hlsDecryptsRotatedKeysAndServesRanges() {
        val keyA = ByteArray(16) { it.toByte() }
        val keyB = ByteArray(16) { (it + 32).toByte() }
        val ivA = ByteBuffer.allocate(16).putLong(0).putLong(7).array()
        val ivB = ByteArray(16) { 1 }
        val segmentA = "первый сегмент".toByteArray()
        val segmentB = "второй сегмент".toByteArray()
        val base = "https://media.example/path/index.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key-a"
            #EXTINF:5,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key-b",IV=0x01010101010101010101010101010101
            #EXTINF:5,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val fixtures = mapOf(base to playlist,
            "https://media.example/path/key-a" to keyA,
            "https://media.example/path/key-b" to keyB,
            "https://media.example/path/a.ts" to encrypt(segmentA, keyA, ivA),
            "https://media.example/path/b.ts" to encrypt(segmentB, keyB, ivB))
        VkHlsRelay { url, _ -> fixtures.getValue(url) }.use { relay ->
            val local = relay.open(base)
            val rewritten = URL(local).readText()
            assertFalse(rewritten.contains("EXT-X-KEY"))
            assertFalse(rewritten.contains("media.example"))
            val segments = rewritten.lineSequence().filter { it.startsWith("http://") }.toList()
            assertArrayEquals(segmentA, URL(segments[0]).readBytes())
            assertArrayEquals(segmentB, URL(segments[1]).readBytes())
            val connection = URL(segments[0]).openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("Range", "bytes=2-5")
                assertEquals(206, connection.responseCode)
                assertArrayEquals(segmentA.copyOfRange(2, 6), connection.inputStream.use { it.readBytes() })
            } finally { connection.disconnect() }
            assertEquals(rewritten, URL(local).readText())
        }
    }

    /** Master, init map и implicit BYTERANGE проходят через реальные HTTP-адреса relay. */
    @Test fun hlsResolvesMasterAndByteRanges() {
        val base = "https://media.example/master.m3u8"
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nsub/media.m3u8\n"
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:5
            #EXT-X-MAP:URI="init.bin",BYTERANGE="4@0"
            #EXTINF:5,
            #EXT-X-BYTERANGE:4@4
            segments.bin
            #EXTINF:5,
            #EXT-X-BYTERANGE:4
            segments.bin
            #EXT-X-ENDLIST
        """.trimIndent()
        val bytes = ByteArray(12) { it.toByte() }
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        VkHlsRelay { url, range ->
            when (url) {
                base -> master.toByteArray()
                "https://media.example/sub/media.m3u8" -> media.toByteArray()
                else -> {
                    ranges.add(range)
                    val parts = requireNotNull(range).removePrefix("bytes=").split('-')
                    bytes.copyOfRange(parts[0].toInt(), parts[1].toInt() + 1)
                }
            }
        }.use { relay ->
            val masterText = URL(relay.open(base)).readText()
            val variant = masterText.lineSequence().first { it.startsWith("http://") }
            val mediaText = URL(variant).readText()
            assertFalse(mediaText.contains("BYTERANGE"))
            val map = Regex("URI=\"([^\"]+)\"").find(mediaText)!!.groupValues[1]
            assertArrayEquals(bytes.copyOfRange(0, 4), URL(map).readBytes())
            val segments = mediaText.lineSequence().filter { it.startsWith("http://") }.toList()
            assertArrayEquals(bytes.copyOfRange(4, 8), URL(segments[0]).readBytes())
            assertArrayEquals(bytes.copyOfRange(8, 12), URL(segments[1]).readBytes())
            assertEquals(listOf("bytes=0-3", "bytes=4-7", "bytes=8-11"), ranges)
        }
    }

    /** Фикстуры имитируют стандартный HLS AES-128 с PKCS7. */
    private fun encrypt(bytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }
}
