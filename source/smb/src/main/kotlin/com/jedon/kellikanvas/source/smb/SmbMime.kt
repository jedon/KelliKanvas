package com.jedon.kellikanvas.source.smb

object SmbMime {
    private val PHOTO_EXTENSIONS =
        mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "bmp" to "image/bmp",
        )

    fun mimeForFileName(name: String): String? {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return PHOTO_EXTENSIONS[ext]
    }

    fun isPhotoFileName(name: String): Boolean = mimeForFileName(name) != null
}
