package com.yellastrodev.vkmusicsdk

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Implicit OAuth Маруси: ручной callback и строгий автоматический callback с безопасными причинами отказа. */
object VkOAuth {
    const val CLIENT_ID = "6463690"
    const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

    /** Создаёт случайный state, который нужно сохранить до проверки callback. */
    fun newState(): String = UUID.randomUUID().toString()

    /** Открывает тот же OAuth-клиент, чей токен поддерживает VK Music по рецепту LavaSrc. */
    fun authorizationUrl(state: String): String =
        "https://oauth.vk.com/authorize?client_id=$CLIENT_ID&scope=1073737727" +
            "&redirect_uri=$REDIRECT_URI&display=page&response_type=token&revoke=1" +
            "&state=${URLEncoder.encode(state, "UTF-8")}"

    /** Возвращает токен из вручную вставленного callback; отсутствующий state допустим только в этом сценарии. */
    fun parseRedirect(value: String, expectedState: String): String =
        parseManualRedirect(value, expectedState).accessToken

    /** Автоматический callback требует state; исключение ручной вставки здесь не применяется. */
    fun parseAutomaticRedirect(value: String, expectedState: String): VkManualRedirect {
        val parsed = parseManualRedirect(value, expectedState)
        if (!parsed.stateVerified) throw VkRedirectException(VkRedirectFailure.MissingState)
        return parsed
    }

    /** Опознаёт только доверенную HTTPS-страницу callback, включая запрет userInfo и нестандартного порта. */
    fun isRedirectUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in setOf("oauth.vk.com", "oauth.vk.ru") &&
            uri.path == "/blank.html" && uri.userInfo == null && uri.port in setOf(-1, 443)
    } catch (_: Exception) { false }

    /**
     * Проверяет HTTPS origin/path и присутствующий state. Для явной ручной вставки допускает
     * отсутствие state (формат ссылки из LavaSrc); музыкальный доступ проверяется до сохранения.
     * payload VK ID не является access_token и отклоняется отдельной безопасной причиной.
     * Ошибки содержат только фиксированные причины, никогда исходный URL или URI exception.
     */
    fun parseManualRedirect(value: String, expectedState: String): VkManualRedirect {
        if (expectedState.isBlank()) throw VkRedirectException(VkRedirectFailure.NoPendingLogin)
        val normalized = value.trim().removeSurrounding("\"").replace("\r", "").replace("\n", "")
        val uri = try { URI(normalized) } catch (_: Exception) {
            throw VkRedirectException(VkRedirectFailure.InvalidUrl)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase() !in setOf("oauth.vk.com", "oauth.vk.ru") ||
            uri.path != "/blank.html" || uri.userInfo != null || uri.port !in setOf(-1, 443)) {
            throw VkRedirectException(VkRedirectFailure.WrongRedirect)
        }
        val fragment = uri.rawFragment?.takeIf(String::isNotBlank)
            ?: throw VkRedirectException(VkRedirectFailure.MissingFragment)
        val params = mutableMapOf<String, String>()
        try {
            fragment.split('&').filter(String::isNotBlank).forEach { field ->
                val parts = field.split('=', limit = 2)
                val key = URLDecoder.decode(parts[0], "UTF-8")
                if (params.containsKey(key)) throw VkRedirectException(VkRedirectFailure.InvalidParameters)
                params[key] = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
        } catch (error: VkRedirectException) {
            throw error
        } catch (_: Exception) {
            throw VkRedirectException(VkRedirectFailure.InvalidParameters)
        }
        if (params.containsKey("error")) throw VkRedirectException(VkRedirectFailure.AuthorizationDenied)
        val returnedState = params["state"]
        if (returnedState != null && returnedState != expectedState) {
            throw VkRedirectException(VkRedirectFailure.StateMismatch)
        }
        val token = params["access_token"]?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
            ?: throw VkRedirectException(if (params.containsKey("payload")) VkRedirectFailure.VkIdPayload
                else VkRedirectFailure.MissingToken)
        return VkManualRedirect(token, stateVerified = returnedState != null)
    }
}

/** Результат ручной вставки; обычный класс не выводит accessToken в автоматически созданном toString. */
class VkManualRedirect(val accessToken: String, val stateVerified: Boolean)

/** Фиксированные безопасные причины отказа, пригодные для UI и диагностики. */
enum class VkRedirectFailure(val description: String) {
    NoPendingLogin("Сначала откройте вход в VK кнопкой в этой карточке"),
    InvalidUrl("Адрес некорректен. Скопируйте полный URL из адресной строки браузера"),
    WrongRedirect("Нужен адрес https://oauth.vk.com/blank.html или https://oauth.vk.ru/blank.html после входа"),
    MissingFragment("В ссылке нет части после #. Скопируйте адрес целиком после завершения входа"),
    InvalidParameters("Параметры ссылки повреждены. Скопируйте адрес из браузера заново"),
    AuthorizationDenied("VK отказал в авторизации. Откройте вход заново"),
    StateMismatch("Ссылка относится к предыдущему входу. Используйте адрес из последней открытой страницы"),
    MissingState("VK не вернул проверочный параметр входа. Откройте вход заново или используйте ручной вход через браузер"),
    MissingToken("В ссылке нет access_token. Завершите вход и скопируйте итоговый адрес страницы"),
    VkIdPayload("VK вернул промежуточную ссылку VK ID вместо токена VK Music. Попробуйте включить «Версия для ПК» в браузере и открыть вход заново. Нужна итоговая ссылка с access_token"),
}

/** Ошибка без исходного URL, токена, OAuth error_description и небезопасного cause. */
class VkRedirectException(val reason: VkRedirectFailure) : IllegalArgumentException(reason.description)
