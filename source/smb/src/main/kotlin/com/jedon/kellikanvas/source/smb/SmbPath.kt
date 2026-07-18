package com.jedon.kellikanvas.source.smb

/**
 * Relative path within an SMB share. Rejects traversal and absolute forms.
 */
object SmbPath {
    fun normalize(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isEmpty() || trimmed == ".") return ""
        require(!trimmed.startsWith('/')) { "SMB path must be relative to the share" }
        require(!trimmed.contains('\u0000')) { "SMB path must not contain NUL" }
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty() || trimmed.isEmpty()) { "SMB path is empty" }
        for (segment in segments) {
            require(segment != "." && segment != "..") { "SMB path must not contain . or .." }
            require(segment.isNotBlank()) { "SMB path segment must not be blank" }
        }
        return segments.joinToString("/")
    }

    fun join(
        parent: String,
        child: String,
    ): String {
        val base = normalize(parent)
        val name = child.trim().replace('\\', '/')
        require(name.isNotEmpty() && !name.contains('/')) { "Child name must be a single segment" }
        require(name != "." && name != "..") { "Child name must not be . or .." }
        return if (base.isEmpty()) name else "$base/$name"
    }

    fun displayName(path: String): String {
        val normalized = normalize(path)
        if (normalized.isEmpty()) return ""
        return normalized.substringAfterLast('/')
    }
}
