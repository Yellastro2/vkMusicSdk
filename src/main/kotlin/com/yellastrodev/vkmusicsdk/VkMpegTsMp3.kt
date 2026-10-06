package com.yellastrodev.vkmusicsdk

import java.io.ByteArrayOutputStream

/** Снимает TS/PES-обёртку единственной MP3-дорожки VK без декодирования и изменения аудиокадров. */
internal object VkMpegTsMp3 {
    /** Проверяет пакеты/непрерывность PES; неподдерживаемый или повреждённый поток явно отклоняется. */
    fun extract(bytes: ByteArray): ByteArray {
        if (bytes.isNotEmpty() && unsigned(bytes[0]) != 0x47) {
            validateFrames(bytes)
            return bytes
        }
        require(bytes.isNotEmpty() && bytes.size % 188 == 0) { "Неполный MPEG-TS VK" }
        val audio = ByteArrayOutputStream(bytes.size)
        var audioPid = -1
        var remaining = 0
        var lastCounter = -1
        var lastPacket = -1
        for (packet in bytes.indices step 188) {
            require(unsigned(bytes[packet]) == 0x47) { "Нарушена синхронизация MPEG-TS VK" }
            val flags = unsigned(bytes[packet + 1])
            val control = unsigned(bytes[packet + 3])
            require(flags and 0x80 == 0 && control and 0xc0 == 0) { "Повреждённый или зашифрованный TS VK" }
            val adaptation = (control shr 4) and 3
            require(adaptation != 0) { "Некорректный заголовок TS VK" }
            var payload = packet + 4
            if (adaptation and 2 != 0) payload += 1 + unsigned(bytes[payload])
            val end = packet + 188
            require(payload <= end) { "Некорректный adaptation field TS VK" }
            if (adaptation and 1 == 0 || payload == end) continue
            val pid = ((flags and 0x1f) shl 8) or unsigned(bytes[packet + 2])
            val startsPes = flags and 0x40 != 0
            val audioPes = startsPes && end - payload >= 4 &&
                bytes[payload] == 0.toByte() && bytes[payload + 1] == 0.toByte() &&
                bytes[payload + 2] == 1.toByte() && unsigned(bytes[payload + 3]) in 0xc0..0xdf
            if (audioPes) {
                require(audioPid == -1 || audioPid == pid) { "Несколько аудиодорожек TS VK" }
                audioPid = pid
            }
            if (pid != audioPid) continue
            val counter = control and 0x0f
            if (lastCounter == counter) {
                require(lastPacket >= 0 && (0 until 188).all { bytes[lastPacket + it] == bytes[packet + it] }) {
                    "Повторный TS-пакет VK содержит другие данные"
                }
                continue
            }
            val discontinuity = adaptation and 2 != 0 && unsigned(bytes[packet + 4]) > 0 &&
                unsigned(bytes[packet + 5]) and 0x80 != 0
            require(lastCounter < 0 || discontinuity || counter == (lastCounter + 1) % 16) {
                "Пропущен TS-пакет аудио VK"
            }
            lastCounter = counter
            lastPacket = packet
            if (startsPes) {
                require(audioPes && remaining <= 0 && end - payload >= 9) { "Неполный PES аудио VK" }
                require(unsigned(bytes[payload + 6]) and 0xc0 == 0x80) { "Неподдерживаемый PES аудио VK" }
                val pesSize = (unsigned(bytes[payload + 4]) shl 8) or unsigned(bytes[payload + 5])
                val headerSize = 9 + unsigned(bytes[payload + 8])
                require(payload + headerSize <= end && (pesSize == 0 || pesSize >= headerSize - 6)) {
                    "Некорректная длина PES аудио VK"
                }
                remaining = if (pesSize == 0) -1 else pesSize - (headerSize - 6)
                payload += headerSize
            }
            val count = if (remaining < 0) end - payload else minOf(remaining, end - payload)
            audio.write(bytes, payload, count)
            if (remaining >= 0) remaining -= count
        }
        require(audioPid >= 0 && remaining <= 0) { "Не найдена полная аудиодорожка TS VK" }
        return audio.toByteArray().also(::validateFrames)
    }

    /** Сохраняет начальный ID3 VK и проверяет границы Layer III кадров; AAC и свободный bitrate не поддерживаются. */
    private fun validateFrames(bytes: ByteArray) {
        val rates = intArrayOf(44100, 48000, 32000)
        val highBitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val lowBitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        var offset = 0
        if (bytes.size >= 10 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) {
            val version = unsigned(bytes[3])
            require(version in 2..4 && (6..9).all { unsigned(bytes[it]) < 128 }) { "Некорректный ID3 VK" }
            var size = 0
            for (index in 6..9) size = (size shl 7) or unsigned(bytes[index])
            offset = 10 + size + if (version == 4 && unsigned(bytes[5]) and 0x10 != 0) 10 else 0
            require(offset <= bytes.size) { "Неполный ID3 VK" }
        }
        var frames = 0
        while (offset + 4 <= bytes.size) {
            val second = unsigned(bytes[offset + 1])
            val third = unsigned(bytes[offset + 2])
            val version = (second shr 3) and 3
            val bitrateIndex = third shr 4
            val rateIndex = (third shr 2) and 3
            require(unsigned(bytes[offset]) == 0xff && second and 0xe0 == 0xe0 &&
                (second shr 1) and 3 == 1 && version != 1 && bitrateIndex in 1..14 && rateIndex < 3) {
                "VK desktop ожидает MPEG Layer III; другой кодек или повреждённый кадр"
            }
            val bitrate = (if (version == 3) highBitrates else lowBitrates)[bitrateIndex]
            val rate = rates[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
            val length = (if (version == 3) 144000 else 72000) * bitrate / rate + ((third shr 1) and 1)
            require(length >= 4 && offset + length <= bytes.size) { "Неполный MP3-кадр VK" }
            offset += length
            frames++
        }
        require(frames >= 1 && offset == bytes.size) { "Неполный MP3-сегмент VK" }
    }

    /** Читает байт заголовка как беззнаковое число. */
    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xff
}
