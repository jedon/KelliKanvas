package com.jedon.kellikanvas.platform.update

import java.net.URI

object UpdateLimits {
    const val PACKAGE_NAME = "com.jedon.kellikanvas"
    const val METADATA_MAX_BYTES = 64 * 1024
    const val APK_MAX_BYTES = 500L * 1024L * 1024L
    const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
}

class UpdateRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

data class UpdateManifest(
    val schema: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: URI,
    val checksumUrl: URI,
    val sizeBytes: Long,
    val sha256: String,
) {
    companion object {
        private val field = Regex("\"[A-Za-z][A-Za-z0-9]*\"\\s*:\\s*(\"[^\"\\\\]*\"|-?\\d+)")
        private val numberField = Regex("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*(-?\\d+)")
        private val stringField = Regex("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*\"([^\"\\\\]*)\"")

        fun parse(bytes: ByteArray): UpdateManifest {
            if (bytes.size > UpdateLimits.METADATA_MAX_BYTES) throw UpdateRejected("manifest exceeds 64 KiB")
            val json = bytes.toString(Charsets.UTF_8).trim()
            if (!json.startsWith("{") || !json.endsWith("}")) throw UpdateRejected("malformed manifest")
            val entries = json.substring(1, json.lastIndex).split(",")
            if (entries.isEmpty() || entries.any { !field.matches(it.trim()) }) {
                throw UpdateRejected("malformed manifest")
            }
            val numbers = uniqueFields(numberField, json)
            val strings = uniqueFields(stringField, json)
            return try {
                UpdateManifest(
                    schema = numbers.required("schema").toInt(),
                    packageName = strings.required("packageName"),
                    versionCode = numbers.required("versionCode").toLong(),
                    versionName = strings.required("versionName"),
                    apkUrl = URI(strings.required("apkUrl")),
                    checksumUrl = URI(strings.required("checksumUrl")),
                    sizeBytes = numbers.required("sizeBytes").toLong(),
                    sha256 = strings.required("sha256").lowercase(),
                )
            } catch (error: Exception) {
                if (error is UpdateRejected) throw error
                throw UpdateRejected("malformed manifest", error)
            }
        }

        private fun uniqueFields(regex: Regex, json: String): Map<String, String> {
            val pairs = regex.findAll(json).map { it.groupValues[1] to it.groupValues[2] }.toList()
            if (pairs.map { it.first }.distinct().size != pairs.size) throw UpdateRejected("duplicate manifest field")
            return pairs.toMap()
        }

        private fun Map<String, String>.required(name: String) = this[name]
            ?: throw UpdateRejected("missing manifest field: $name")
    }
}
