package com.yellastrodev.vkmusicsdk

import java.net.URL
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Проверяет объединение чтений и отмену попытки без закрытия зарегистрированной очереди. */
class VkRelayRequestsTest {
    /** Два одновременных GET не создают две загрузки и получают одинаковые байты. */
    @Test fun simultaneousRequestsShareOneDownload() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val joined = CountDownLatch(1)
        val calls = AtomicInteger()
        VkHlsRelay { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            byteArrayOf(1, 2, 3)
        }.use { relay ->
            relay.onDiagnostic { if (it.startsWith("[joinVkResource]")) joined.countDown() }
            val uri = relay.openDeferredAudio(false, "https://media.example/private.mp3?token=secret") { error("Seed") }
            val first = CompletableFuture.supplyAsync { URL(uri).readBytes() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val second = CompletableFuture.supplyAsync { URL(uri).readBytes() }
                assertTrue(joined.await(2, TimeUnit.SECONDS))
                release.countDown()
                assertArrayEquals(byteArrayOf(1, 2, 3), first.get(2, TimeUnit.SECONDS))
                assertArrayEquals(byteArrayOf(1, 2, 3), second.get(2, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    }

    /** Сброс прерывает runBlocking-резолвер; следующий GET того же корня остаётся рабочим. */
    @Test fun resetCancelsResolverAndKeepsQueue() {
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
        VkHlsRelay { _, _ -> byteArrayOf(9, 8) }.use { relay ->
            relay.onDiagnostic { diagnostics.add(it) }
            relay.onError { diagnostics.add(it) }
            val uri = relay.openDeferredAudio(false) {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.awaitCancellation() }
                }
                "https://private.example/hidden-path?token=secret"
            }
            val parsed = URL(uri)
            val reader = CompletableFuture.supplyAsync {
                Socket(parsed.host, parsed.port).use { socket ->
                    socket.soTimeout = 3_000
                    socket.getOutputStream().write("GET ${parsed.path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            relay.cancelPendingRequests()
            reader.get(2, TimeUnit.SECONDS)
            assertArrayEquals(byteArrayOf(9, 8), URL(uri).readBytes())
            assertEquals(2, calls.get())
            assertTrue(diagnostics.any { it.startsWith("[cancelVkRequests]") })
            assertFalse(diagnostics.any { it.contains("hidden-path") || it.contains("token=") || it.contains("secret") })
        }
    }

    /** Ошибка получает этап/время, не раскрывает message и не мешает повтору того же ресурса. */
    @Test fun failedResourceIsRetriedAndLogsSafeStage() {
        val calls = AtomicInteger()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        VkHlsRelay { _, _ ->
            if (calls.incrementAndGet() == 1) throw java.net.SocketTimeoutException("token=secret /private-path")
            byteArrayOf(3, 4)
        }.use { relay ->
            relay.onError { errors.add(it) }
            val uri = relay.openDeferredAudio(false, "https://private.example/private-path?token=secret") { error("Seed") }
            assertThrows(java.io.IOException::class.java) { URL(uri).readBytes() }
            assertArrayEquals(byteArrayOf(3, 4), URL(uri).readBytes())
            assertTrue(errors.any { it.contains("этап=") && it.contains("время=") && it.contains("SocketTimeoutException") })
            assertFalse(errors.any { it.contains("secret") || it.contains("private-path") })
        }
    }
}
