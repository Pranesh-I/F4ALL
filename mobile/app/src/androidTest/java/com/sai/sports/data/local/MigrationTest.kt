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

    /**
     * v2 -> v3 adds the owner. Queued tests survive with no owner yet, which
     * the worker's next run under a signed-in athlete fills in.
     */
    @Test
    fun queuedAttemptsSurviveGainingAnOwner() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO test_attempts (id, test_type, recorded_at_ms, provisional_score, " +
                    "score_unit, sync_status, source_video_path, source_size_bytes, " +
                    "compressed_size_bytes, uploaded_bytes, attempt_count, result_id) " +
                    "VALUES ('a2', 'SQUATS', 2000, 15.0, 'reps', 'QUEUED', 'videos/a2.mp4', 10, 5, 2, 1, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT sync_status, uploaded_bytes, athlete_id FROM test_attempts WHERE id = 'a2'")
                .use { cursor ->
                    assertTrue("The queued attempt must still exist", cursor.moveToFirst())
                    assertEquals("QUEUED", cursor.getString(0))
                    assertEquals(2L, cursor.getLong(1))
                    assertTrue("No owner until one is claimed", cursor.isNull(2))
                }
        }
    }

    /** v3 -> v4 adds the session; queued tests keep their owner and gain no session. */
    @Test
    fun queuedAttemptsSurviveGainingASession() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO test_attempts (id, test_type, recorded_at_ms, provisional_score, " +
                    "score_unit, sync_status, source_video_path, source_size_bytes, " +
                    "compressed_size_bytes, uploaded_bytes, attempt_count, result_id, athlete_id) " +
                    "VALUES ('a3', 'LUNGES', 3000, 9.0, 'reps', 'QUEUED', 'videos/a3.mp4', 10, 5, 2, 1, NULL, 'asha')"
            )
        }

        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT sync_status, athlete_id, session_id FROM test_attempts WHERE id = 'a3'")
                .use { cursor ->
                    assertTrue("The queued attempt must still exist", cursor.moveToFirst())
                    assertEquals("QUEUED", cursor.getString(0))
                    assertEquals("asha", cursor.getString(1))
                    assertTrue("Recorded before sessions", cursor.isNull(2))
                }
        }
    }

    /** v4 -> v5 adds the photo check; queued official tests keep their session. */
    @Test
    fun queuedAttemptsSurviveGainingAnIdentityCheck() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO test_attempts (id, test_type, recorded_at_ms, provisional_score, " +
                    "score_unit, sync_status, source_video_path, source_size_bytes, " +
                    "compressed_size_bytes, uploaded_bytes, attempt_count, result_id, athlete_id, session_id) " +
                    "VALUES ('a4', 'PUSH_UPS', 4000, 12.0, 'reps', 'QUEUED', 'videos/a4.mp4', 10, 5, 2, 1, " +
                    "NULL, 'asha', 's1')"
            )
        }

        helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5).use { db ->
            db.query(
                "SELECT sync_status, session_id, identity_check_id, identity_photo_path " +
                    "FROM test_attempts WHERE id = 'a4'"
            ).use { cursor ->
                assertTrue("The queued attempt must still exist", cursor.moveToFirst())
                assertEquals("QUEUED", cursor.getString(0))
                assertEquals("s1", cursor.getString(1))
                assertTrue("Recorded before photo checks", cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
