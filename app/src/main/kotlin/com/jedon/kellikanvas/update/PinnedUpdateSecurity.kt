package com.jedon.kellikanvas.update

import com.jedon.kellikanvas.BuildConfig
import com.jedon.kellikanvas.platform.update.ManifestAuthenticator

fun pinnedManifestAuthenticator(): ManifestAuthenticator {
    val encodedKey =
        BuildConfig.UPDATE_METADATA_PUBLIC_KEY_BASE64.ifBlank {
            throw IllegalStateException("No update metadata public key is pinned in this build")
        }
    return ManifestAuthenticator.fromPinnedBase64(encodedKey)
}
