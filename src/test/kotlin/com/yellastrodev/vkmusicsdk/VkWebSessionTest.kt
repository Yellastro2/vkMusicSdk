package com.yellastrodev.vkmusicsdk

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Проверяет жизненный цикл web-сессии на локальном HTTP-сервере без реальных cookies и аккаунта. */
class VkWebSessionTest {
    /** Истёкший токен обновляется после восстановления, ротация сохраняется, cookies не уходят в audio API. */
    @Test fun expiredSessionRefreshesAndKeepsCookiesOutOfApi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(webToken("new-token").addHeader("Set-Cookie", "p=new-p; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setBody("""{"response":1}"""))
            var saved: VkWebSession? = null
            client(server, session("expired-token", 1), onUpdated = { saved = it }).use { client ->
                client.request("audio.search")
                val refresh = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/?act=web_token", refresh.path)
                assertEquals("POST", refresh.method)
                assertEquals("p=test-p; remixsid=test-sid", refresh.getHeader("Cookie"))
                assertEquals("test-browser", refresh.getHeader("User-Agent"))
                assertEquals("https://vk.ru", refresh.getHeader("Origin"))
                assertEquals("version=1&app_id=6287487", refresh.body.readUtf8())
                val api = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertNull(api.getHeader("Cookie"))
                assertTrue(api.body.readUtf8().contains("access_token=new-token"))
                assertEquals("new-p", saved!!.p)
                assertEquals("test-sid", saved!!.remixsid)
                assertEquals("new-token", client.webSession!!.accessToken)
            }
        }
    }

    /** Годный сохранённый токен используется без обновления и без фоновых запросов. */
    @Test fun validRestoredSessionDoesNotRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":1}"""))
            client(server, session("saved-token", 4_102_444_800)).use { client ->
                assertEquals(0, server.requestCount)
                client.request("audio.search")
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/method/audio.search", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("access_token=saved-token"))
                assertTrue(body.contains("client_id=6287487"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    /** Десятиминутный запас проверяется при запросе, даже если прежний токен пока не истёк. */
    @Test fun nearlyExpiredTokenRefreshesBeforeApi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(webToken("fresh-token"))
            server.enqueue(MockResponse().setBody("""{"response":1}"""))
            client(server, session("nearly-expired", System.currentTimeMillis() / 1_000 + 540)).use { client ->
                client.request("audio.search")
                assertEquals("/?act=web_token", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
                assertEquals("/method/audio.search", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            }
        }
    }

    /** Отвергает смену владельца при обновлении, сохраняя исходный снимок сессии. */
    @Test fun refreshCannotChangeAccount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"type":"okay","data":{"access_token":"other-account","expires":4102444800,"user_id":43}}"""))
            client(server, session()).use { client ->
                try {
                    client.request("audio.search")
                    fail("Обновление не должно менять владельца сессии")
                } catch (error: VkApiException) { assertEquals(5, error.code) }
                assertEquals(42L, client.webSession!!.userId)
                assertEquals("", client.webSession!!.accessToken)
                assertEquals(1, server.requestCount)
            }
        }
    }

    /** Даже без тротлинга параллельные вызовы разделяют одно обновление токена. */
    @Test fun concurrentCallsRefreshOnlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(webToken("shared-token"))
            repeat(3) { server.enqueue(MockResponse().setBody("""{"response":1}""")) }
            var updates = 0
            client(server, session(), onUpdated = { updates++ }).use { client ->
                coroutineScope { List(3) { async(Dispatchers.IO) { client.request("audio.search") } }.forEach { it.await() } }
                assertEquals(1, updates)
                assertEquals(4, server.requestCount)
            }
        }
    }

    /** Код 5 допускает ровно одно обновление и один повтор, затем выходит без цикла. */
    @Test fun rejectedTokenRetriesOnlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"error":{"error_code":5}}"""))
            server.enqueue(webToken("replacement-token"))
            server.enqueue(MockResponse().setBody("""{"error":{"error_code":5}}"""))
            client(server, session("revoked-token", 4_102_444_800)).use { client ->
                try {
                    client.request("audio.search")
                    fail("Повторный код 5 должен завершать запрос")
                } catch (error: VkApiException) { assertEquals(5, error.code) }
                assertEquals(3, server.requestCount)
            }
        }
    }

    /** Отозванные cookies прекращают запрос до музыкального API и не попадают в сообщение ошибки. */
    @Test fun revokedCookiesDoNotSendApiRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"type":"error","error_info":"unauthorized","error_code":5}"""))
            client(server, session()).use { client ->
                try {
                    client.request("audio.search")
                    fail("Отозванная браузерная сессия должна требовать нового входа")
                } catch (error: VkApiException) {
                    assertEquals(5, error.code)
                    assertFalse(error.message.orEmpty().contains("test-p"))
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    /** Не использует обновлённый токен, если его защищённое сохранение не удалось. */
    @Test fun failedPersistenceStopsApiRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(webToken("unsaved-token"))
            client(server, session(), onUpdated = { throw IOException("Ошибка хранилища") }).use { client ->
                try {
                    client.request("audio.search")
                    fail("Нельзя продолжать после ошибки сохранения")
                } catch (_: IOException) { }
                assertEquals("", client.webSession!!.accessToken)
                assertEquals(1, server.requestCount)
            }
        }
    }

    /** Обновление и следующий API-запрос выдерживают общий минимальный интервал. */
    @Test fun refreshAndApiShareThrottle() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(webToken("throttled-token"))
            server.enqueue(MockResponse().setBody("""{"response":1}"""))
            client(server, session(), interval = 400).use { client ->
                coroutineScope {
                    val call = async(Dispatchers.IO) { client.request("audio.search") }
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    call.await()
                }
            }
        }
    }

    /** Cookie-запрос не следует за перенаправлением и не раскрывает cookies другому серверу. */
    @Test fun webTokenDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { destination ->
                destination.start()
                server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/")))
                client(server, session()).use { client ->
                    try {
                        client.authenticateWebSession()
                        fail("Перенаправление должно быть отклонено")
                    } catch (_: IOException) { }
                    assertEquals(1, server.requestCount)
                    assertEquals(0, destination.requestCount)
                }
            }
        }
    }

    /** Создаёт синтетическую cookie-сессию, не содержащую настоящих секретов. */
    private fun session(token: String = "", expires: Long = 0): VkWebSession =
        VkWebSession("test-p", "test-sid", "test-browser", token, expires, 42)

    /** Возвращает Unix-срок до 2100 года, чтобы тест не зависел от текущей даты. */
    private fun webToken(token: String): MockResponse = MockResponse().setBody(
        """{"type":"okay","data":{"access_token":"$token","expires":4102444800,"user_id":42}}""")

    /** Подменяет только HTTP-адреса и интервал, сохраняя настоящий протокол SDK. */
    private fun client(server: MockWebServer, session: VkWebSession, interval: Long = 0,
        onUpdated: (VkWebSession) -> Unit = {}): VkApiClient = VkApiClient(
        accessToken = session.accessToken, apiBase = server.url("/method/").toString(),
        minRequestIntervalMs = interval, webSession = session, onWebSessionUpdated = onUpdated,
        webTokenUrl = server.url("/?act=web_token").toString(),
    )
}
