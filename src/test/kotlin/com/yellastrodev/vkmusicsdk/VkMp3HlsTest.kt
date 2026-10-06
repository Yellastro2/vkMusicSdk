package com.yellastrodev.vkmusicsdk

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Проверяет desktop MP3 HLS, старый TS-кеш, AES, offline-bundle и повреждённые пакеты без живого VK. */
class VkMp3HlsTest {
    /** Исходный plaintext остаётся в кеше, а HTTP отдаёт MP3 с соответствующими расширением/MIME/Range. */
    @Test fun decryptsBeforeDemuxAndKeepsTsCache() {
        val mp3 = mp3Frames()
        val ts = transportStream(mp3)
        val key = ByteArray(16) { 7 }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val encrypted = cipher.doFinal(ts)
        val cache = mutableMapOf<String, ByteArray>()
        var segmentReads = 0
        VkHlsRelay({ url, _ -> when (URL(url).path) {
            "/media.m3u8" -> ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" +
                "#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n").toByteArray()
            "/key" -> key
            else -> { segmentReads++; encrypted }
        } }, true).use { relay ->
            relay.useAudioCache(object : VkAudioCache {
                /** Возвращает ранее сохранённый TS, включая повторный HTTP GET. */
                override fun read(key: String): ByteArray? = cache[key]
                /** Запоминает точные байты исходного plaintext. */
                override fun write(key: String, bytes: ByteArray) { cache[key] = bytes }
            })
            val playlist = URL(relay.open("https://fixture.invalid/media.m3u8", "1_2")).readText()
            assertFalse(playlist.contains("EXT-X-KEY"))
            val segment = URL(playlist.lineSequence().first { it.startsWith("http://") })
            assertTrue(segment.path.endsWith(".mp3"))
            val response = segment.openConnection() as HttpURLConnection
            try {
                assertEquals("audio/mpeg", response.contentType)
                assertEquals(mp3.size, response.contentLength)
                assertArrayEquals(mp3, response.inputStream.use { it.readBytes() })
            } finally { response.disconnect() }
            assertArrayEquals(ts, cache.values.single())
            assertArrayEquals(mp3, segment.readBytes())
            assertEquals(1, segmentReads)
            val range = segment.openConnection() as HttpURLConnection
            try {
                range.setRequestProperty("Range", "bytes=0-7")
                assertEquals(206, range.responseCode)
                assertEquals("bytes 0-7/${mp3.size}", range.getHeaderField("Content-Range"))
                assertArrayEquals(mp3.copyOfRange(0, 8), range.inputStream.use { it.readBytes() })
            } finally { range.disconnect() }
        }
    }

    /** Bundle сохраняет переносимый TS; desktop открывает его без сети, прежний Android-режим отдаёт TS. */
    @Test fun preservesBundlesAndDefaultMode() {
        val mp3 = mp3Frames()
        val ts = transportStream(mp3)
        val directory = Files.createTempDirectory("vk-mp3-hls-test").toFile()
        try {
            val root = VkHlsRelay({ url, _ -> if (url.endsWith("m3u8"))
                "#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray() else ts }, true)
                .use { it.downloadAudio("https://fixture.invalid/media.m3u8", true, directory, "1_2") }
            assertArrayEquals(ts, directory.listFiles()!!.single { it.extension == "ts" }.readBytes())
            for (desktop in listOf(false, true)) {
                VkHlsRelay({ _, _ -> error("Сеть запрещена") }, desktop).use { relay ->
                    val playlist = URL(relay.openSavedAudio(root)).readText()
                    val segment = URL(playlist.lineSequence().first { it.startsWith("http://") })
                    assertEquals(if (desktop) ".mp3" else ".ts", "." + segment.path.substringAfterLast('.'))
                    assertArrayEquals(if (desktop) mp3 else ts, segment.readBytes())
                }
            }
        } finally { directory.deleteRecursively() }
    }

    /** Повреждения continuity, границ PES и MP3-кадров отклоняются до HTTP-ответа. */
    @Test fun rejectsIncompleteMediaAndLostPackets() {
        val ts = transportStream(mp3Frames())
        assertThrows(IllegalArgumentException::class.java) { VkMpegTsMp3.extract(ts.copyOf(ts.size - 1)) }
        val lostPacket = ts.copyOfRange(0, 188) + ts.copyOfRange(376, ts.size)
        assertThrows(IllegalArgumentException::class.java) { VkMpegTsMp3.extract(lostPacket) }
        assertThrows(IllegalArgumentException::class.java) { VkMpegTsMp3.extract(transportStream(mp3Frames().copyOf(600))) }
        assertArrayEquals(mp3Frames(), VkMpegTsMp3.extract(mp3Frames()))
    }

    /** Первый сегмент VK содержит ID3v2.4 перед MP3; метаданные должны сохраниться без изменения. */
    @Test fun preservesLeadingId3Metadata() {
        val tagged = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 5) + ByteArray(5) + mp3Frames()
        assertArrayEquals(tagged, VkMpegTsMp3.extract(transportStream(tagged)))
        val truncatedTag = tagged.copyOf().also { it[9] = 127; it[8] = 127 }
        assertThrows(IllegalArgumentException::class.java) { VkMpegTsMp3.extract(truncatedTag) }
    }

    /** Создаёт полные MPEG-1 Layer III кадры 128kbps/44100Hz с синтетической полезной нагрузкой. */
    private fun mp3Frames(): ByteArray = ByteArray(417 * 3).also { bytes ->
        for (start in bytes.indices step 417) {
            bytes[start] = 0xff.toByte()
            bytes[start + 1] = 0xfb.toByte()
            bytes[start + 2] = 0x90.toByte()
        }
    }

    /** Упаковывает MP3 в конечный PES и TS-пакеты с adaptation padding и счётчиком continuity. */
    private fun transportStream(mp3: ByteArray): ByteArray {
        val pesSize = mp3.size + 3
        val pes = byteArrayOf(0, 0, 1, 0xc0.toByte(), (pesSize shr 8).toByte(), pesSize.toByte(), 0x80.toByte(), 0, 0) + mp3
        val output = ByteArrayOutputStream()
        var offset = 0
        var counter = 0
        while (offset < pes.size) {
            val count = minOf(184, pes.size - offset)
            val packet = ByteArray(188) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = (if (offset == 0) 0x41 else 0x01).toByte()
            packet[2] = 0
            packet[3] = ((if (count == 184) 0x10 else 0x30) or (counter++ % 16)).toByte()
            val start = if (count == 184) 4 else {
                packet[4] = (183 - count).toByte()
                if (count < 183) packet[5] = 0
                188 - count
            }
            pes.copyInto(packet, start, offset, offset + count)
            output.write(packet)
            offset += count
        }
        return output.toByteArray()
    }
}
