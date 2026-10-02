package com.yellastrodev.vkmusicsdk

/** Необязательное хранилище готового аудио; вызывается на HTTP-worker, без зависимости от приложения. */
interface VkAudioCache {
    /** Возвращает проверенные байты либо null при отсутствии/повреждении записи. */
    fun read(key: String): ByteArray?

    /** Атомарно сохраняет полностью загруженный и расшифрованный ресурс. */
    fun write(key: String, bytes: ByteArray)
}
