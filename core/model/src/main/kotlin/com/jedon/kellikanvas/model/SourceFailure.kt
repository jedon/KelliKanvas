package com.jedon.kellikanvas.model

import java.io.IOException

/**
 * A privacy-safe source error. Adapters must allow cancellation exceptions to propagate unchanged.
 */
sealed class SourceFailure protected constructor(
    val profileId: SourceProfileId,
    val operation: String,
    val diagnosticCode: String,
    val recoveryCode: String,
    val safeDetail: String,
) : IOException("$diagnosticCode during $operation: $safeDetail") {
    init {
        requirePrivacySafeText(operation, "Source operation")
        requirePrivacySafeText(safeDetail, "Source failure detail")
    }

    class AuthenticationRequired(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Authentication required",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "authentication_required",
        recoveryCode = "reauthenticate",
        safeDetail,
    )

    class PermissionRevoked(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Permission grant required",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "permission_revoked",
        recoveryCode = "reauthorize",
        safeDetail,
    )

    class SourceUnavailable(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Source unavailable",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "source_unavailable",
        recoveryCode = "retry",
        safeDetail,
    )

    class NotFound(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Item not found",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "not_found",
        recoveryCode = "refresh_catalog",
        safeDetail,
    )

    class UnsupportedFormat(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Unsupported photo format",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "unsupported_format",
        recoveryCode = "skip_asset",
        safeDetail,
    )

    class CorruptContent(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Corrupt photo content",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "corrupt_content",
        recoveryCode = "skip_asset",
        safeDetail,
    )

    class Timeout(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Source operation timed out",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "timeout",
        recoveryCode = "retry",
        safeDetail,
    )

    class ProtocolFailure(
        profileId: SourceProfileId,
        operation: String,
        safeDetail: String = "Source protocol failure",
    ) : SourceFailure(
        profileId,
        operation,
        diagnosticCode = "protocol_failure",
        recoveryCode = "retry_or_report",
        safeDetail,
    )
}
