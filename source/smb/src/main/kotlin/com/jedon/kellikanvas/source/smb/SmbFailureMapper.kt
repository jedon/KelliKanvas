package com.jedon.kellikanvas.source.smb

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceProfileId
import java.net.SocketTimeoutException
import java.nio.file.AccessDeniedException

object SmbFailureMapper {
    fun map(
        profileId: SourceProfileId,
        operation: String,
        failure: Throwable,
    ): SourceFailure {
        val message = (failure.message ?: "").lowercase()
        return when {
            failure is SourceFailure -> failure
            failure is SocketTimeoutException || message.contains("timeout") ->
                SourceFailure.Timeout(profileId, operation)
            message.contains("logon") ||
                message.contains("authentication") ||
                message.contains("access denied") ||
                message.contains("status_logon_failure") ||
                message.contains("status_access_denied") ||
                failure is AccessDeniedException ->
                SourceFailure.AuthenticationRequired(profileId, operation)
            message.contains("not found") ||
                message.contains("status_object_name_not_found") ||
                message.contains("status_no_such_file") ||
                message.contains("status_object_path_not_found") ->
                SourceFailure.NotFound(profileId, operation)
            failure is SMBApiException || failure is SMBRuntimeException ->
                SourceFailure.ProtocolFailure(profileId, operation)
            else -> SourceFailure.SourceUnavailable(profileId, operation)
        }
    }
}
