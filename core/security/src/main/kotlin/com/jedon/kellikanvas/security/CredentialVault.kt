package com.jedon.kellikanvas.security

import com.jedon.kellikanvas.model.SourceProfileId

interface CredentialVault {
    fun write(
        profileId: SourceProfileId,
        secret: ByteArray,
    )

    fun write(
        profileId: SourceProfileId,
        secret: CharArray,
    )

    fun read(profileId: SourceProfileId): CredentialReadResult

    fun remove(profileId: SourceProfileId)
}
