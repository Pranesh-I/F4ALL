package com.sai.sports.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sai.sports.sync.SyncStatus
import kotlinx.coroutines.flow.Flow

/** One official session attempt in the queue, reduced to what session rules need. */
data class SessionAttemptRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "test_type") val testType: String,
    /** Set once the server accepted the submission. */
    @ColumnInfo(name = "result_id") val resultId: String?
)

@Dao
interface TestAttemptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: TestAttemptEntity)

    @Update
    suspend fun update(attempt: TestAttemptEntity)

    @Query("SELECT * FROM test_attempts WHERE id = :id")
    suspend fun findById(id: String): TestAttemptEntity?

    /** Backing query for the sync screen: one athlete's attempts, newest first. */
    @Query("SELECT * FROM test_attempts WHERE athlete_id = :athleteId ORDER BY recorded_at_ms DESC")
    fun observeAll(athleteId: String): Flow<List<TestAttemptEntity>>

    /**
     * Tests that have not reached SAI yet: still on their way up, or uploaded
     * but not yet submitted. A delivered video with no test attached is not
     * "sent" from the athlete's point of view — nothing will be verified.
     */
    @Query(
        "SELECT COUNT(*) FROM test_attempts WHERE athlete_id = :athleteId AND (" +
            "sync_status NOT IN ('SYNCED', 'FAILED') OR " +
            "(sync_status = 'SYNCED' AND result_id IS NULL AND attempt_count < :maxSubmitAttempts))"
    )
    fun observePendingCount(athleteId: String, maxSubmitAttempts: Int): Flow<Int>

    /**
     * Gives rows queued before ownership existed to [athleteId]. Before this,
     * the app assumed one athlete per phone, so the athlete signed in when the
     * update first runs is almost certainly the one who recorded them.
     */
    @Query("UPDATE test_attempts SET athlete_id = :athleteId WHERE athlete_id IS NULL")
    suspend fun claimUnowned(athleteId: String): Int

    /**
     * The worker's queue: everything not finished, oldest first.
     *
     * Oldest-first is deliberate. When connectivity returns after days offline,
     * the athlete cares most about the test they did first — and it is the one
     * closest to any submission deadline.
     */
    @Query(
        "SELECT * FROM test_attempts " +
            "WHERE athlete_id = :athleteId AND sync_status IN ('RECORDED', 'COMPRESSED', 'QUEUED') " +
            "ORDER BY recorded_at_ms ASC"
    )
    suspend fun findWorkQueue(athleteId: String): List<TestAttemptEntity>

    @Query("SELECT * FROM test_attempts WHERE sync_status = :status")
    suspend fun findByStatus(status: SyncStatus): List<TestAttemptEntity>

    @Query(
        "UPDATE test_attempts SET sync_status = :status, " +
            "last_error = :error, last_attempt_at_ms = :timestampMs " +
            "WHERE id = :id"
    )
    suspend fun updateStatus(
        id: String,
        status: SyncStatus,
        error: String?,
        timestampMs: Long
    )

    @Query("UPDATE test_attempts SET uploaded_bytes = :bytes WHERE id = :id")
    suspend fun updateUploadedBytes(id: String, bytes: Long)

    @Query("UPDATE test_attempts SET upload_id = :uploadId WHERE id = :id")
    suspend fun updateUploadId(id: String, uploadId: String)

    /**
     * Rescues rows left mid-flight by a process death.
     *
     * Android kills backgrounded apps without warning, and a row stuck in
     * COMPRESSING or UPLOADING would never be picked up again by the work queue
     * — the athlete's test would sit there looking busy forever. Called on
     * startup, before any work is scheduled.
     *
     * COMPRESSING falls back to RECORDED because a half-written transcode is
     * useless; UPLOADING falls back to QUEUED because the chunks that did land
     * are still good and `upload_id` survives.
     */
    @Query(
        "UPDATE test_attempts SET sync_status = " +
            "CASE sync_status WHEN 'COMPRESSING' THEN 'RECORDED' ELSE 'QUEUED' END " +
            "WHERE sync_status IN ('COMPRESSING', 'UPLOADING')"
    )
    suspend fun recoverInterrupted(): Int

    /** Videos the server has, with no test submitted against them yet. Oldest first. */
    @Query(
        "SELECT * FROM test_attempts " +
            "WHERE athlete_id = :athleteId AND sync_status = 'SYNCED' " +
            "AND video_id IS NOT NULL AND result_id IS NULL " +
            "ORDER BY recorded_at_ms ASC"
    )
    suspend fun findUnsubmitted(athleteId: String): List<TestAttemptEntity>

    @Query("UPDATE test_attempts SET result_id = :resultId WHERE id = :id")
    suspend fun updateResultId(id: String, resultId: String)

    /**
     * This athlete's official attempts for sessions, sent or still waiting.
     * What decides that a second attempt at a test is refused before it is
     * recorded; a FAILED row does not count, since it never reached SAI.
     */
    @Query(
        "SELECT session_id, test_type, result_id FROM test_attempts " +
            "WHERE athlete_id = :athleteId AND session_id IS NOT NULL AND sync_status != 'FAILED'"
    )
    fun observeSessionAttempts(athleteId: String): Flow<List<SessionAttemptRow>>

    @Query(
        "SELECT session_id, test_type, result_id FROM test_attempts " +
            "WHERE athlete_id = :athleteId AND session_id = :sessionId AND sync_status != 'FAILED'"
    )
    suspend fun sessionAttempts(athleteId: String, sessionId: String): List<SessionAttemptRow>

    /** Every attempt that waited on [photoPath] now carries the check it became. */
    @Query(
        "UPDATE test_attempts SET identity_check_id = :checkId, identity_photo_path = NULL " +
            "WHERE identity_photo_path = :photoPath"
    )
    suspend fun resolveIdentityPhoto(photoPath: String, checkId: String?)

    /** Photos still waiting to be checked, so orphans can be told apart. */
    @Query("SELECT DISTINCT identity_photo_path FROM test_attempts WHERE identity_photo_path IS NOT NULL")
    suspend fun pendingIdentityPhotos(): List<String>

    @Query("DELETE FROM test_attempts WHERE id = :id")
    suspend fun delete(id: String)
}
