package com.sai.sports.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sai.sports.sync.SyncStatus

/**
 * One recorded attempt and everything needed to get it to SAI.
 *
 * This table is the offline queue. On a phone in a village with no signal it
 * may be the only record that a test ever happened, and it has to survive
 * reboots, force-stops, and app updates until the upload lands.
 *
 * The scoring detail (pose sequence, event log) deliberately stays in files
 * under `AttemptStore` — pose sequences are hundreds of KB of CSV and have no
 * business in a row that gets read on every sync tick.
 */
@Entity(
    tableName = "test_attempts",
    indices = [
        // The sync worker's hot query is "what still needs work, oldest first".
        Index(value = ["sync_status"]),
        Index(value = ["recorded_at_ms"])
    ]
)
data class TestAttemptEntity(

    /** Matches the attempt id used by AttemptStore and the video file name. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "test_type")
    val testType: String,

    @ColumnInfo(name = "recorded_at_ms")
    val recordedAtMs: Long,

    @ColumnInfo(name = "provisional_score")
    val provisionalScore: Double,

    @ColumnInfo(name = "score_unit")
    val scoreUnit: String,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus,

    /** Original camera recording, relative to filesDir. */
    @ColumnInfo(name = "source_video_path")
    val sourceVideoPath: String,

    /** 480p transcode actually uploaded; null until compression succeeds. */
    @ColumnInfo(name = "compressed_video_path")
    val compressedVideoPath: String? = null,

    @ColumnInfo(name = "source_size_bytes")
    val sourceSizeBytes: Long = 0,

    @ColumnInfo(name = "compressed_size_bytes")
    val compressedSizeBytes: Long = 0,

    @ColumnInfo(name = "checksum_sha256")
    val checksumSha256: String? = null,

    /**
     * Server-issued upload id, persisted the moment it is allocated.
     *
     * This single column is what turns a dropped upload into a resume instead
     * of a restart. If it is lost, the athlete pays for every byte again.
     */
    @ColumnInfo(name = "upload_id")
    val uploadId: String? = null,

    @ColumnInfo(name = "uploaded_bytes")
    val uploadedBytes: Long = 0,

    /** Set once the server has the video; used for the Sprint 5 submit call. */
    @ColumnInfo(name = "video_id")
    val videoId: String? = null,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "last_attempt_at_ms")
    val lastAttemptAtMs: Long? = null,

    @ColumnInfo(name = "synced_at_ms")
    val syncedAtMs: Long? = null
) {

    /** Upload progress 0.0..1.0 for the sync screen. */
    fun progress(): Float {
        if (syncStatus == SyncStatus.SYNCED) return 1f
        if (compressedSizeBytes <= 0) return 0f
        return (uploadedBytes.toFloat() / compressedSizeBytes).coerceIn(0f, 1f)
    }
}
