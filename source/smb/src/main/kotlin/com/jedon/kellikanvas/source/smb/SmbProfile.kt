package com.jedon.kellikanvas.source.smb

import com.jedon.kellikanvas.model.SourceProfileId

data class SmbProfile(
    val id: SourceProfileId,
    val host: String,
    val port: Int = HouseholdNasDefaults.PORT,
    val share: String,
    val domain: String = "",
    val username: String,
) {
    init {
        require(host.isNotBlank() && !host.contains('\n')) { "SMB host must be a single non-blank line" }
        require(port in 1..65535) { "SMB port out of range" }
        require(share.isNotBlank() && !share.contains('/') && !share.contains('\\')) {
            "SMB share must be a single path segment"
        }
        require(!domain.contains('\n')) { "SMB domain must be single-line" }
        require(username.isNotBlank() && !username.contains('\n')) { "SMB username required" }
    }

    override fun toString(): String = "SmbProfile(id=$id, host=$host, port=$port, share=$share, domain=<redacted>, username=<redacted>)"
}

data class SmbCredentials(
    val username: String,
    val password: CharArray,
    val domain: String = "",
) {
    fun clear() {
        password.fill('\u0000')
    }

    override fun equals(other: Any?): Boolean = other is SmbCredentials &&
        username == other.username &&
        domain == other.domain &&
        password.contentEquals(other.password)

    override fun hashCode(): Int = listOf(username, domain, password.contentHashCode()).hashCode()

    override fun toString(): String = "SmbCredentials(username=<redacted>, domain=<redacted>)"
}
