package com.jedon.kellikanvas.source.saf

import android.content.ContentResolver
import android.content.Intent
import com.jedon.kellikanvas.model.SourceProfileId

data class SafProfile(
    val id: SourceProfileId,
    val grant: SafTreeGrant,
) {
    fun repair(
        resolver: ContentResolver,
        replacement: SafTreeGrant,
    ): SafProfile {
        if (grant.treeUri != replacement.treeUri) {
            try {
                resolver.releasePersistableUriPermission(
                    grant.treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Prior grant may already be gone.
            }
        }
        return copy(grant = replacement)
    }

    override fun toString(): String = "SafProfile(id=$id, grant=<redacted>)"
}
