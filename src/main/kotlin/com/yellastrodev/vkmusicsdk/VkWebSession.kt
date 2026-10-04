package com.yellastrodev.vkmusicsdk

/** Снимок браузерной сессии; expiresAt — Unix-время в секундах, секреты не выводятся в toString. */
class VkWebSession(
    val p: String,
    val remixsid: String,
    val userAgent: String,
    val accessToken: String = "",
    val expiresAt: Long = 0,
    val userId: Long? = null,
) {
    init {
        require(p.isNotBlank() && remixsid.isNotBlank()) { "Нужна браузерная сессия VK" }
        require(listOf(p, remixsid).none { value -> value.any { it == ';' || it == '\r' || it == '\n' } }) {
            "Некорректные cookies VK"
        }
        require(userAgent.isNotBlank() && userAgent.none { it == '\r' || it == '\n' }) { "Нужен User-Agent браузера VK" }
    }

    /** Исключает случайную печать cookies и токена в журналах. */
    override fun toString(): String = "VkWebSession(секреты скрыты)"
}
