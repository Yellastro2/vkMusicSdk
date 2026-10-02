package com.yellastrodev.vkmusicsdk

import java.net.URL
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Проверяет сохранение полного графа, отсутствие секретов и offline-воспроизведение новым relay. */
class VkSavedAudioTest {
    /** Master/AES-bundle после перезапуска читается без единого сетевого обращения. */
    @Test fun savedMasterAndEncryptedAudioPlayWithoutNetwork() {
        val directory = Files.createTempDirectory("vk-saved-fixture").toFile()
        try {
            val plain = byteArrayOf(1, 2, 3, 4)
            val key = ByteArray(16) { 8 }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            val encrypted = cipher.doFinal(plain)
            var progress = 0L
            val root = VkHlsRelay { url, _ -> when (URL(url).path) {
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nmedia.m3u8?token=private\n".toByteArray()
                "/media.m3u8" -> ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key?token=private\"\n" +
                    "#EXTINF:4,\nsegment?token=private\n#EXT-X-ENDLIST\n").toByteArray()
                "/key" -> key
                else -> encrypted
            } }.use { relay -> relay.downloadAudio("https://media.example/master.m3u8", true, directory, "1_2") { bytes, _ -> progress = bytes } }
            assertEquals(plain.size.toLong(), progress)
            directory.listFiles()!!.filter { it.extension == "m3u8" }.forEach {
                val playlist = it.readText()
                assertFalse(playlist.contains("http"))
                assertFalse(playlist.contains("private"))
                assertFalse(playlist.contains("EXT-X-KEY"))
            }
            VkHlsRelay { _, _ -> error("Offline не должен обращаться к сети") }.use { relay ->
                val master = URL(relay.openSavedAudio(root)).readText()
                val media = URL(master.lineSequence().first { it.startsWith("http://") }).readText()
                val uri = media.lineSequence().first { it.startsWith("http://") }
                assertArrayEquals(plain, URL(uri).readBytes())
                val range = URL(uri).openConnection() as java.net.HttpURLConnection
                try {
                    range.setRequestProperty("Range", "bytes=1-2")
                    assertEquals(206, range.responseCode)
                    assertArrayEquals(byteArrayOf(2, 3), range.inputStream.use { it.readBytes() })
                } finally { range.disconnect() }
            }
        } finally { directory.deleteRecursively() }
    }

    /** Прямой файл также сохраняется, а выход из локального каталога отклоняется. */
    @Test fun progressiveAudioAndUnsafeLocalPlaylist() {
        val directory = Files.createTempDirectory("vk-saved-direct").toFile()
        try {
            val root = VkHlsRelay { _, _ -> byteArrayOf(7, 8) }.use {
                it.downloadAudio("https://media.example/a.mp3", false, directory, "1_2")
            }
            VkHlsRelay { _, _ -> error("Сеть запрещена") }.use {
                assertArrayEquals(byteArrayOf(7, 8), URL(it.openSavedAudio(root)).readBytes())
                var available: java.io.File? = null
                val queued = it.openDeferredAudio(false, audioId = "1_2", savedAudio = { available }) {
                    error("Постоянный файл должен исключить даже резолвинг VK")
                }
                available = root
                assertArrayEquals(byteArrayOf(7, 8), URL(queued).readBytes())
                val unsafe = java.io.File(directory, "unsafe.m3u8").apply { writeText("#EXTM3U\n../outside.ts\n#EXT-X-ENDLIST\n") }
                assertThrows(java.io.IOException::class.java) { URL(it.openSavedAudio(unsafe)).readBytes() }
            }
        } finally { directory.deleteRecursively() }
    }

    /** Незавершённый playlist не экспортируется как готовый offline-трек. */
    @Test fun livePlaylistIsRejected() {
        val directory = Files.createTempDirectory("vk-saved-live").toFile()
        try {
            VkHlsRelay { _, _ -> "#EXTM3U\n#EXTINF:4,\nsegment.ts\n".toByteArray() }.use {
                assertThrows(IllegalArgumentException::class.java) {
                    it.downloadAudio("https://media.example/live.m3u8", true, directory, "1_2")
                }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }
}
