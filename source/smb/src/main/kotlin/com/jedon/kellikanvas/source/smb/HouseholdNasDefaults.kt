package com.jedon.kellikanvas.source.smb

/**
 * Baked-in household NAS topology (no secrets).
 * Credentials are injected at build time via app BuildConfig / env.
 *
 * Auto-bootstrap targets only the Frame TV 16×9 landscape mix folder.
 */
object HouseholdNasDefaults {
    const val PRIMARY_HOST: String = "192.168.68.81"
    const val PORT: Int = 445
    const val DISPLAY_NAME: String = "DarklingNAS"

    /** Relative to the [PRIMARY_SHARE] share. */
    const val FRAME_TV_16X9_PATH: String = "Frame TV landscape photos_mix/16X9"

    /** Hostnames to try in order when connecting. */
    val HOST_CANDIDATES: List<String> =
        listOf(
            "192.168.68.81",
            "DarklingNAS",
            "darklingnas",
            "darklingnas.local",
        )

    /**
     * Probe-proven photo roots. Paths are relative to [share].
     * Only the Frame TV 16×9 folder is auto-selected for household bootstrap.
     */
    val PHOTO_SHARES: List<HouseholdSmbShare> =
        listOf(
            HouseholdSmbShare(
                share = "Kelli",
                displayName = "Frame TV 16x9",
                photoRoots = listOf(FRAME_TV_16X9_PATH),
            ),
        )

    /** Default share used when a single profile is needed. */
    val PRIMARY_SHARE: HouseholdSmbShare = PHOTO_SHARES.first()

    fun isPreferredSmbRoot(objectId: String): Boolean =
        SmbPath.normalize(objectId).equals(FRAME_TV_16X9_PATH, ignoreCase = true)
}

data class HouseholdSmbShare(
    val share: String,
    val displayName: String,
    val photoRoots: List<String>,
) {
    init {
        require(share.isNotBlank() && !share.contains('/') && !share.contains('\\')) {
            "Share name must be a single path segment"
        }
        require(displayName.isNotBlank())
        photoRoots.forEach { SmbPath.normalize(it) }
    }
}
