package com.yellastrodev.vkmusicsdk

import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

/** Проверки ручного OAuth (включая callback без state), API и loopback HLS без аккаунта VK. */
class VkSdkTest {
    /** Диагностический запрет API отклоняет вызов до отправки HTTP, без токена в ошибке. */
    @Test fun disabledApiDoesNotSendRequests() = runBlocking {
        MockWebServer().use { server ->
            VkApiClient("test-token", server.url("/method/").toString(), requestsEnabled = false).use { client ->
                try {
                    client.request("users.get")
                    fail("API должен быть отключён")
                } catch (error: IllegalStateException) {
                    assertFalse(error.message.orEmpty().contains("test-token"))
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    /** Параллельные вызовы API ждут завершения ответа и минимального интервала между стартами. */
    @Test fun throttledRequestsAreSequentialAndSpaced() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":1}""").setBodyDelay(500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("""{"response":1}"""))
            server.enqueue(MockResponse().setBody("""{"response":1}"""))
            VkApiClient("test-token", server.url("/method/").toString(), minRequestIntervalMs = 250).use { client ->
                coroutineScope {
                    val calls = List(3) { async(Dispatchers.IO) { client.request("users.get") } }
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    // Пока первый ответ ещё передаётся, второй запрос не должен быть отправлен.
                    assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    calls.forEach { it.await() }
                }
            }
        }
    }

    /** Автоматический вход требует state; ручной сценарий сохраняет прежнюю совместимость. */
    @Test fun automaticCallbackRequiresStateAndTrustedOrigin() {
        val prefix = "https://oauth.vk.ru/blank.html#access_token=synthetic-token"
        assertTrue(VkOAuth.parseAutomaticRedirect("$prefix&state=abc", "abc").stateVerified)
        val missing = assertThrows(VkRedirectException::class.java) { VkOAuth.parseAutomaticRedirect(prefix, "abc") }
        assertEquals(VkRedirectFailure.MissingState, missing.reason)
        assertFalse(VkOAuth.parseManualRedirect(prefix, "abc").stateVerified)
        assertTrue(VkOAuth.isRedirectUrl(prefix))
        assertTrue(VkOAuth.isRedirectUrl("https://oauth.vk.com/blank.html#access_token=t"))
        assertFalse(VkOAuth.isRedirectUrl("https://oauth.vk.ru.evil.example/blank.html#access_token=t"))
        assertFalse(VkOAuth.isRedirectUrl("https://oauth.vk.ru@evil.example/blank.html#access_token=t"))
        assertFalse(VkOAuth.isRedirectUrl("http://oauth.vk.ru/blank.html"))
        assertFalse(VkOAuth.isRedirectUrl("https://oauth.vk.ru:8443/blank.html"))
        assertFalse(VkOAuth.isRedirectUrl("https://oauth.vk.ru/authorize"))
    }

    /** Android VK ID payload отклоняется отдельной причиной; промежуточный токен и профиль не попадают в ошибку. */
    @Test fun vkIdPayloadIsNotAcceptedAsMusicToken() {
        val payload = java.net.URLEncoder.encode(
            """{"type":"silent_token","token":"synthetic-secret","user":{"avatar":"https://image.example/a?quality=95&crop=1&cs=200"},"ttl":600}""", "UTF-8")
            .replace("%26", "&")
        val url = "https://oauth.vk.ru/blank.html#payload=$payload&state=abc"
        val error = assertThrows(VkRedirectException::class.java) { VkOAuth.parseManualRedirect(url, "abc") }
        assertEquals(VkRedirectFailure.VkIdPayload, error.reason)
        assertFalse(error.message.orEmpty().contains("synthetic-secret"))
        assertFalse(error.message.orEmpty().contains("image.example"))
        assertNull(error.cause)
        val stale = assertThrows(VkRedirectException::class.java) { VkOAuth.parseManualRedirect(url, "another-state") }
        assertEquals(VkRedirectFailure.StateMismatch, stale.reason)
    }

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

    /** Поиск сохраняет метадату, передаёт токен в POST и использует 5.199 без UA VK Android. */
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
                val body = request.body.readUtf8()
                assertTrue(body.contains("access_token=test-token"))
                assertTrue(body.contains("v=5.199"))
                assertFalse(request.getHeader("User-Agent").orEmpty().contains("VKAndroidApp"))
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

    /** Проверяет путь полного CRUD-сценария и точные POST-параметры, включая ключ закрытого аудио. */
    @Test fun playlistCrudUsesDedicatedMethodsAndCompoundAudioIds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":[{"id":42}]}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"id":7,"owner_id":42,"title":"Тест & музыка","permissions":{"edit":true,"delete":true}}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"id":7,"owner_id":42,"photo":{"photo_300":"https://media.example/cover.jpg"}}}"""))
            repeat(3) { server.enqueue(MockResponse().setBody("""{"response":1}""")) }
            VkApiClient("test-token", server.url("/method/").toString()).use { client ->
                assertEquals(42L, client.getCurrentUserId())
                val playlist = client.createPlaylist(42, "  Тест & музыка  ")
                assertTrue(playlist.canEdit(42))
                assertFalse(playlist.canEdit(99))
                assertEquals("https://media.example/cover.jpg", client.getPlaylistById(42, 7, "key").coverUrl)
                val audio = VkAudio(12, -5, accessKey = "audio-key")
                client.addToPlaylist(playlist, audio)
                client.removeFromPlaylist(playlist, audio)
                client.deletePlaylist(playlist)
                val expected = listOf("users.get", "audio.createPlaylist", "audio.getPlaylistById",
                    "audio.addToPlaylist", "audio.removeFromPlaylist", "audio.deletePlaylist")
                expected.forEach { method ->
                    val request = server.takeRequest()
                    assertEquals("/method/$method", request.path)
                    assertEquals("POST", request.method)
                    val body = URLDecoder.decode(request.body.readUtf8(), "UTF-8")
                    assertTrue(body.contains("access_token=test-token"))
                    when (method) {
                        "audio.createPlaylist" -> assertTrue(body.contains("title=Тест & музыка"))
                        "audio.getPlaylistById" -> assertTrue(body.contains("access_key=key"))
                        "audio.addToPlaylist" -> assertTrue(body.contains("audio_ids=-5_12_audio-key"))
                        "audio.removeFromPlaylist" -> assertTrue(body.contains("audio_ids=-5_12&"))
                    }
                }
            }
        }
    }

    /** Сохранённый чужой плейлист читается по original/access_key и не получает права изменения. */
    @Test fun followedPlaylistReadsOriginalWithPagination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"count":2,"items":[{"id":1,"owner_id":-5}]}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"count":2,"items":[{"id":2,"owner_id":-5}]}}"""))
            VkApiClient("t", server.url("/method/").toString()).use { client ->
                val playlist = VkPlaylist(7, 42, original = VkPlaylistReference(-9, 8, "key"))
                assertFalse(playlist.canEdit(42))
                assertFalse(playlist.canDelete(42))
                assertEquals(2, client.getPlaylistTracks(playlist).size)
                repeat(2) { index ->
                    val request = server.takeRequest()
                    val body = URLDecoder.decode(request.body.readUtf8(), "UTF-8")
                    assertEquals("/method/audio.get", request.path)
                    assertTrue(body.contains("owner_id=-9"))
                    assertTrue(body.contains("album_id=8"))
                    assertTrue(body.contains("access_key=key"))
                    assertTrue(body.contains("offset=$index"))
                }
            }
        }
    }

    /** Явный отказ в response не должен удалять плейлист из UI как при успешном запросе. */
    @Test fun playlistMutationRejectsFalseSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":0}"""))
            VkApiClient("t", server.url("/method/").toString()).use { client ->
                try {
                    client.deletePlaylist(VkPlaylist(7, 42))
                    fail("Ожидался отказ VK")
                } catch (_: IllegalArgumentException) { }
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

    /** Регистрация очереди не запрашивает будущие треки; HEAD/GET одного корня разделяют свежий URL. */
    @Test fun deferredQueueResolvesOnlyRequestedTrack() {
        val callsA = java.util.concurrent.atomic.AtomicInteger()
        val callsB = java.util.concurrent.atomic.AtomicInteger()
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\nsegment.ts\n#EXT-X-ENDLIST\n"
        VkHlsRelay { url, _ ->
            if (url.endsWith(".m3u8")) playlist.toByteArray() else byteArrayOf(1, 2, 3)
        }.use { relay ->
            val a = relay.openDeferredAudio(true) { callsA.incrementAndGet(); "https://media.example/a/index.m3u8" }
            val b = relay.openDeferredAudio(true) { callsB.incrementAndGet(); "https://media.example/b/index.m3u8" }
            assertEquals(0, callsA.get())
            assertEquals(0, callsB.get())
            val head = URL(a).openConnection() as HttpURLConnection
            try {
                head.requestMethod = "HEAD"
                assertEquals(200, head.responseCode)
                assertEquals("application/vnd.apple.mpegurl", head.contentType)
            } finally { head.disconnect() }
            val segment = URL(a).readText().lineSequence().first { it.startsWith("http://") }
            assertArrayEquals(byteArrayOf(1, 2, 3), URL(segment).readBytes())
            assertEquals(1, callsA.get())
            assertEquals(0, callsB.get())
            assertTrue(URL(b).readText().contains("#EXTM3U"))
            assertEquals(1, callsB.get())
        }
    }

    /** Подготовленный выбранный URL используется сразу, а ошибка другого корня не ломает очередь целиком. */
    @Test fun deferredQueueSeedAndFailureAreIsolated() {
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        VkHlsRelay { _, _ -> "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray() }.use { relay ->
            relay.onError { errors.add(it) }
            val seeded = relay.openDeferredAudio(true, "https://media.example/seed.m3u8") {
                throw IllegalStateException("Резолвер выбранного трека не должен вызываться повторно")
            }
            val failed = relay.openDeferredAudio(true) { throw java.io.IOException("https://private.example/token") }
            val connection = URL(failed).openConnection() as HttpURLConnection
            try { assertEquals(502, connection.responseCode) } finally { connection.disconnect() }
            assertTrue(errors.single().contains("IOException"))
            assertFalse(errors.single().contains("private.example"))
            assertTrue(URL(seeded).readText().contains("#EXTM3U"))
        }
    }

    /** Прямое аудио ленивой очереди сохраняет HEAD/range и правильный Content-Type. */
    @Test fun deferredProgressiveAudioSupportsRange() {
        VkHlsRelay { _, _ -> byteArrayOf(10, 20, 30, 40) }.use { relay ->
            val uri = relay.openDeferredAudio(false) { "https://media.example/a.mp3" }
            val connection = URL(uri).openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("Range", "bytes=1-2")
                assertEquals(206, connection.responseCode)
                assertEquals("audio/mpeg", connection.contentType)
                assertArrayEquals(byteArrayOf(20, 30), connection.inputStream.use { it.readBytes() })
            } finally { connection.disconnect() }
        }
    }

    /** Фикстуры имитируют стандартный HLS AES-128 с PKCS7. */
    private fun encrypt(bytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }
}
