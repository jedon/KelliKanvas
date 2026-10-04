package com.jedon.kellikanvas.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert
import com.jedon.kellikanvas.model.SourceProfileId

/** Public connection metadata only. Google grants belong in CredentialVault. */
data class GoogleConnection(val profileId: SourceProfileId, val resourceId: String = "", val settingsUri: String = "", val clientId: String = "") {
    override fun toString() = "GoogleConnection(<redacted>)"
}

@Entity(
    tableName = "google_connections",
    primaryKeys = ["profile_id"],
    foreignKeys = [
        ForeignKey(entity = SourceProfileEntity::class, parentColumns = ["profile_id"], childColumns = ["profile_id"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class GoogleConnectionEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "settings_uri") val settingsUri: String,
    @ColumnInfo(name = "client_id") val clientId: String,
)

@Dao
internal interface RoomGoogleConnectionDao {
    @Upsert suspend fun upsert(entity: GoogleConnectionEntity)

    @Query("SELECT * FROM google_connections WHERE profile_id = :profileId")
    suspend fun get(profileId: String): GoogleConnectionEntity?

    @Query("SELECT * FROM google_connections")
    suspend fun list(): List<GoogleConnectionEntity>
}

class GoogleConnectionDao internal constructor(private val dao: RoomGoogleConnectionDao) {
    suspend fun get(id: SourceProfileId): GoogleConnection? = dao.get(id.value)?.domain()
    suspend fun list(): List<GoogleConnection> = dao.list().map { it.domain() }
    suspend fun upsert(connection: GoogleConnection) = dao.upsert(GoogleConnectionEntity(connection.profileId.value, connection.resourceId, connection.settingsUri, connection.clientId))
    private fun GoogleConnectionEntity.domain() = GoogleConnection(SourceProfileId(profileId), resourceId, settingsUri, clientId)
}
