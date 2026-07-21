package com.jedon.kellikanvas.diagnostics

import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId

/**
 * Outcome of restoring one configured source profile's adapter at shell load:
 * restored, or skipped/failed with a user-readable [reason].
 * [kind] is null when the profile has no connection record of any known kind.
 */
data class RootRestoreStatus(
    val profileId: SourceProfileId,
    val label: String,
    val kind: SourceKind?,
    val restored: Boolean,
    val reason: String? = null,
)
