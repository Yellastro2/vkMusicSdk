package com.yellastrodev.vkmusicsdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Метадата аудио VK; идентификатор всегда включает owner_id, в том числе отрицательный. */
@Serializable
data class VkAudio(
    val id: Long,
    @SerialName("owner_id") val ownerId: Long,
    val artist: String = "",
    val title: String = "",
    val duration: Long = 0,
    val url: String = "",
    @SerialName("access_key") val accessKey: String? = null,
    @SerialName("main_artists") val mainArtists: List<VkArtist> = emptyList(),
    val thumb: VkThumbnail? = null,
    val album: VkAlbum? = null,
    val like: Boolean = false,
) {
    val fullId: String get() = "${ownerId}_$id"
    val requestId: String get() = accessKey?.takeIf(String::isNotBlank)?.let { "${fullId}_$it" } ?: fullId
    val artistNames: List<String> get() = mainArtists.map(VkArtist::name).ifEmpty { listOf(artist) }
    val coverUrl: String? get() = (thumb ?: album?.thumb)?.url
}

/** Артист из main_artists; в старых ответах доступна только строка artist. */
@Serializable
data class VkArtist(val name: String = "", val id: String = "")

/** Набор размеров обложки VK, используемый аудио и альбомами. */
@Serializable
data class VkThumbnail(
    @SerialName("photo_600") val photo600: String? = null,
    @SerialName("photo_300") val photo300: String? = null,
    @SerialName("photo_270") val photo270: String? = null,
    @SerialName("photo_135") val photo135: String? = null,
) {
    val url: String? get() = listOf(photo600, photo300, photo270, photo135).firstOrNull { !it.isNullOrBlank() }
}

/** Альбом из ответа audio.search/getById. */
@Serializable
data class VkAlbum(val id: Long = 0, val title: String = "", val thumb: VkThumbnail? = null)

/** Плейлист VK, включая ключ доступа к закрытому списку. */
@Serializable
data class VkPlaylist(
    val id: Long,
    @SerialName("owner_id") val ownerId: Long,
    val title: String = "",
    val count: Int = 0,
    @SerialName("access_key") val accessKey: String? = null,
)

/** Страница аудио; count обозначает размер всей выдачи, а не текущей страницы. */
@Serializable
data class VkAudioPage(val count: Int = 0, val items: List<VkAudio> = emptyList())

/** Ошибка API с безопасным текстом: ответ сервера и токен в message не попадают. */
class VkApiException(val code: Int) : Exception("VK API: код ошибки $code")
