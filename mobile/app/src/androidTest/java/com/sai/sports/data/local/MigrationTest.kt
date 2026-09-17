package com.sai.sports.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 -> v2 must keep every queued test.
 *
 * On a phone in the field this table can hold the only record of a test an
 * athlete travelled to record, still waiting for signal. An app update that
 * drops it loses that test silently.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        F4allDatabase::class.java
    )

    @Test
    fun unsyncedAttemptsSurviveTheMigration() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO test_attempts (id, test_type, recorded_at_ms, provisional_score, " +
                    "score_unit, sync_status, source_video_path, source_size_bytes, " +
                    "compressed_size_bytes, uploaded_bytes, attempt_count) " +
                    "VALUES ('a1', 'SIT_UPS', 1000, 24.0, 'reps', 'QUEUED', 'videos/a1.mp4', 10, 5, 2, 1)"
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT sync_status, uploaded_bytes, result_id FROM test_attempts WHERE id = 'a1'")
                .use { cursor ->
                    assertTrue("The queued attempt must still exist", cursor.moveToFirst())
                    assertEquals("QUEUED", cursor.getString(0))
                    assertEquals(2L, cursor.getLong(1))
                    assertTrue("Not yet submitted", cursor.isNull(2))
                }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
