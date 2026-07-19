package com.jedon.kellikanvas.source.http

import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.UnsupportedSourceAdapter

/**
 * HTTP source stub. Fails closed until a real HTTP adapter is implemented.
 */
class HttpSourceAdapter(
    profileId: SourceProfileId,
) : UnsupportedSourceAdapter(
    kind = SourceKind.HTTP,
    profileId = profileId,
    reason = "HTTP source is not implemented",
)
