package com.jedon.kellikanvas.source.smb

/**
 * Baked-in household NAS topology (no secrets).
 * Credentials are injected at build time via app BuildConfig / env.
 */
object HouseholdNasDefaults {
    const val PRIMARY_HOST: String = "192.168.68.81"
    const val PORT: Int = 445
    const val DISPLAY_NAME: String = "DarklingNAS"

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
     * Primary share is [Kelli], which holds the household photo libraries.
     */
    val PHOTO_SHARES: List<HouseholdSmbShare> =
        listOf(
            HouseholdSmbShare(
                share = "Kelli",
                displayName = "Kelli photos",
                photoRoots =
                    listOf(
                        "Digital Photos",
                        "Cell Phone Photos",
                        "Photos for frame TV and printing",
                        "Frame TV landscape photos_mix",
                        "Images",
                        "kelli_resized",
                        "Business Card photos 2026",
                        "Colonial Williamsburg Grand Illumination",
                        "Jedons cell phone photos incl Swept Away",
                        "Photography files",
                    ),
            ),
            HouseholdSmbShare(
                share = "Multimedia",
                displayName = "Multimedia Canvas",
                photoRoots = listOf("Canvas"),
            ),
            HouseholdSmbShare(
                share = "Public",
                displayName = "Public pictures",
                photoRoots = listOf("Media/Pictures"),
            ),
        )

    /** Default share used when a single profile is needed. */
    val PRIMARY_SHARE: HouseholdSmbShare = PHOTO_SHARES.first()
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
