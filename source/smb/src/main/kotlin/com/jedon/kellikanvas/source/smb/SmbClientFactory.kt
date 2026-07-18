package com.jedon.kellikanvas.source.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.mssmb2.SMB2Dialect
import java.util.concurrent.TimeUnit

object SmbClientFactory {
    fun createClient(): SMBClient {
        val config =
            SmbConfig.builder()
                .withDialects(
                    SMB2Dialect.SMB_3_1_1,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_2_1,
                )
                .withSigningRequired(true)
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .build()
        return SMBClient(config)
    }

    fun authenticationContext(credentials: SmbCredentials): AuthenticationContext =
        AuthenticationContext(
            credentials.username,
            credentials.password,
            credentials.domain.ifBlank { null },
        )
}

/**
 * Owns SMBJ resources for one share. Close in reverse of open order.
 */
class SmbSessionScope(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    val share: DiskShare,
) : AutoCloseable {
    override fun close() {
        try {
            share.close()
        } finally {
            try {
                session.close()
            } finally {
                try {
                    connection.close()
                } finally {
                    client.close()
                }
            }
        }
    }

    companion object {
        fun open(
            profile: SmbProfile,
            credentials: SmbCredentials,
        ): SmbSessionScope {
            val client = SmbClientFactory.createClient()
            try {
                val connection = client.connect(profile.host, profile.port)
                try {
                    val session =
                        connection.authenticate(SmbClientFactory.authenticationContext(credentials))
                    try {
                        val share = session.connectShare(profile.share) as DiskShare
                        return SmbSessionScope(client, connection, session, share)
                    } catch (failure: Throwable) {
                        session.close()
                        throw failure
                    }
                } catch (failure: Throwable) {
                    connection.close()
                    throw failure
                }
            } catch (failure: Throwable) {
                client.close()
                throw failure
            }
        }
    }
}
