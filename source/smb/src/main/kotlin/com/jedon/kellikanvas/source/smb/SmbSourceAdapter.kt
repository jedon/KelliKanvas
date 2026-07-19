package com.jedon.kellikanvas.source.smb

import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.UnsupportedSourceAdapter

/**
 * SMB source stub. Fails closed until a real SMB adapter is implemented.
 */
class SmbSourceAdapter(
    profileId: SourceProfileId,
) : UnsupportedSourceAdapter(
    kind = SourceKind.SMB,
    profileId = profileId,
    reason = "SMB source is not implemented",
)
