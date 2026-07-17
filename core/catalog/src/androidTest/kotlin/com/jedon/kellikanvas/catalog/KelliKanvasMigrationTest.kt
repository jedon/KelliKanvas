package com.jedon.kellikanvas.catalog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KelliKanvasMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            KelliKanvasDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun exportedVersionOneSchemaOpensWithoutDestructiveFallback() {
        helper.createDatabase(DATABASE_NAME, 1).close()

        val database =
            Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                KelliKanvasDatabase::class.java,
                DATABASE_NAME,
            ).build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    @Test
    fun exportedVersionOneSchemaNormalizesLastPresentedAsset() {
        val database = helper.createDatabase("last-presented-schema", 1)

        val columns =
            database.query("PRAGMA table_info(`slideshow_session_last_presented`)").use { cursor ->
                buildMap {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                    while (cursor.moveToNext()) {
                        put(cursor.getString(nameIndex), cursor.getInt(notNullIndex))
                    }
                }
            }

        assertThat(columns["profile_id"]).isEqualTo(1)
        assertThat(columns["object_id"]).isEqualTo(1)
        database.close()
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
