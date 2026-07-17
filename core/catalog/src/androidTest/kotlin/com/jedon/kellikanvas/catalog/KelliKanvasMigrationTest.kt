package com.jedon.kellikanvas.catalog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
