package com.yellastrodev.vkmusicsdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

/** Проверяет реальные форматы vk-audio и пагинацию коллекции без живого аккаунта. */
class VkCollectionTest {
    /** audio.get без album_id обходит все страницы и сохраняет release_audio_id. */
    @Test fun collectionReadsEveryPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"count":2,"items":[{"id":1,"owner_id":42,"release_audio_id":"-7_9"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"count":2,"items":[{"id":2,"owner_id":42}]}}"""))
            VkApiClient("test-token", server.url("/method/").toString()).use { client ->
                val tracks = client.getMyTracks(42)
                assertEquals(listOf("42_1", "42_2"), tracks.map { it.fullId })
                assertEquals("-7_9", tracks.first().releaseAudioId)
                val first = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
                val second = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
                assertTrue(first.contains("owner_id=42"))
                assertFalse(first.contains("album_id"))
                assertTrue(second.contains("offset=1"))
            }
        }
    }

    /** Удаление получает новый личный ID из items, исходный foreign source-id не используется. */
    @Test fun mutationsUsePersonalInstance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"items_count":1,"items":[{"new_audio_id":100,"new_owner_id":42,"audio_raw_id":"-7_9"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"audio_ids":["42_100"]}}"""))
            VkApiClient("test-token", server.url("/method/").toString()).use { client ->
                val own = client.addToMyTracks(VkAudio(9, -7, accessKey = "private-key"), 42)
                assertEquals("42_100", own.fullId)
                assertNull(own.accessKey)
                client.removeFromMyTracks(own)
                server.takeRequest()
                val deleted = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
                assertTrue(deleted.contains("owner_id=42"))
                assertTrue(deleted.contains("audio_id=100"))
            }
        }
    }

    /** Пустые items, чужой владелец и неподтверждённое удаление не считаются успехом. */
    @Test fun mutationsRejectUnconfirmedResponses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"items":[]}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"items":[{"new_audio_id":100,"new_owner_id":43}]}}"""))
            server.enqueue(MockResponse().setBody("""{"response":{"audio_ids":["42_99"]}}"""))
            VkApiClient("test-token", server.url("/method/").toString()).use { client ->
                assertTrue(runCatching { client.addToMyTracks(VkAudio(9, -7), 42) }.isFailure)
                assertTrue(runCatching { client.addToMyTracks(VkAudio(9, -7), 42) }.isFailure)
                assertTrue(runCatching { client.removeFromMyTracks(VkAudio(100, 42)) }.isFailure)
            }
        }
    }
}
