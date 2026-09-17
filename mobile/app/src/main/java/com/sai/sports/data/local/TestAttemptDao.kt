package com.sai.sports.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sai.sports.sync.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TestAttemptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: TestAttemptEntity)

    @Update
    suspend fun update(attempt: TestAttemptEntity)

    @Query("SELECT * FROM test_attempts WHERE id = :id")
    suspend fun findById(id: String): TestAttemptEntity?

    /** Backing query for the sync screen. Newest first. */
    @Query("SELECT * FROM test_attempts ORDER BY recorded_at_ms DESC")
    fun observeAll(): Flow<List<TestAttemptEntity>>

    @Query(
        "SELECT COUNT(*) FROM test_attempts " +
            "WHERE sync_status NOT IN ('SYNCED', 'FAILED')"
    )
    fun observePendingCount(): Flow<Int>

    /**
     * The worker's queue: everything not finished, oldest first.
     *
     * Oldest-first is deliberate. When connectivity returns after days offline,
     * the athlete cares most about the test they did first — and it is the one
     * closest to any submission deadline.
     */
    @Query(
        "SELECT * FROM test_attempts " +
            "WHERE sync_status IN ('RECORDED', 'COMPRESSED', 'QUEUED') " +
            "ORDER BY recorded_at_ms ASC"
    )
    suspend fun findWorkQueue(): List<TestAttemptEntity>

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
            "WHERE sync_status = 'SYNCED' AND video_id IS NOT NULL AND result_id IS NULL " +
            "ORDER BY recorded_at_ms ASC"
    )
    suspend fun findUnsubmitted(): List<TestAttemptEntity>

    @Query("UPDATE test_attempts SET result_id = :resultId WHERE id = :id")
    suspend fun updateResultId(id: String, resultId: String)

    @Query("DELETE FROM test_attempts WHERE id = :id")
    suspend fun delete(id: String)
}
