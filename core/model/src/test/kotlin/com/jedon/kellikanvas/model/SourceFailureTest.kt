package com.jedon.kellikanvas.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SourceFailureTest {
    private val profileId = SourceProfileId("private-profile-id")

    @Test
    fun `source failures are IO exceptions with stable diagnostic and recovery codes`() {
        val failures =
            listOf(
                SourceFailure.AuthenticationRequired(profileId, "probe") to
                    ("authentication_required" to "reauthenticate"),
                SourceFailure.PermissionRevoked(profileId, "list_children") to
                    ("permission_revoked" to "reauthorize"),
                SourceFailure.SourceUnavailable(profileId, "probe") to
                    ("source_unavailable" to "retry"),
                SourceFailure.NotFound(profileId, "metadata") to
                    ("not_found" to "refresh_catalog"),
                SourceFailure.UnsupportedFormat(profileId, "metadata") to
                    ("unsupported_format" to "skip_asset"),
                SourceFailure.CorruptContent(profileId, "open") to
                    ("corrupt_content" to "skip_asset"),
                SourceFailure.Timeout(profileId, "open") to
                    ("timeout" to "retry"),
                SourceFailure.ProtocolFailure(profileId, "list_children") to
                    ("protocol_failure" to "retry_or_report"),
            )

        failures.forEach { (failure, codes) ->
            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(failure.profileId).isEqualTo(profileId)
            assertThat(failure.diagnosticCode).isEqualTo(codes.first)
            assertThat(failure.recoveryCode).isEqualTo(codes.second)
            assertThat(failure.safeDetail).isNotEmpty()
        }
    }

    @Test
    fun `source failure diagnostics reject unsafe details and hide profile IDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceFailure.ProtocolFailure(
                profileId = profileId,
                operation = "open",
                safeDetail = "Failed at https://private.test/photo?token=secret",
            )
        }

        val failure =
            SourceFailure.AuthenticationRequired(
                profileId = profileId,
                operation = "probe",
                safeDetail = "Sign-in required",
            )

        assertThat(failure.toString()).doesNotContain(profileId.value)
        assertThat(failure.message).contains("authentication_required")
        assertThat(failure.message).contains("Sign-in required")
    }

    @Test
    fun `source failures reject blank operations and details`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceFailure.Timeout(profileId, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceFailure.Timeout(profileId, "open", safeDetail = "")
        }
    }
}
