package com.jedon.kellikanvas.source.saf

import com.jedon.kellikanvas.model.SourceProfileId

data class SafProfile(
    val id: SourceProfileId,
    val grant: SafTreeGrant,
) {
    fun repair(replacement: SafTreeGrant): SafProfile = copy(grant = replacement)

    override fun toString(): String = "SafProfile(id=$id, grant=<redacted>)"
}
