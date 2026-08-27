package com.sai.sports.sync

/**
 * Where a recorded attempt sits on its way to SAI.
 *
 *   RECORDED ─> COMPRESSING ─> COMPRESSED ─> QUEUED ─> UPLOADING ─> SYNCED
 *                    │              │           │          │
 *                    └──────────────┴───────────┴──────────┴──> FAILED
 *
 * FAILED is not terminal — the athlete can retry it, and the sync screen exists
 * so they can see that they need to.
 *
 * SYNCED is terminal, and it means the server acknowledged the upload. It does
 * NOT mean the test is verified: the server re-scores in Sprint 5 and an
 * official approves in Sprint 8. Conflating "we delivered the video" with "the
 * result counts" is the kind of thing that makes an athlete think they are done
 * when they are not.
 */
enum class SyncStatus {
    RECORDED,
    COMPRESSING,
    COMPRESSED,
    QUEUED,
    UPLOADING,
    SYNCED,
    FAILED;

    val isTerminal: Boolean
        get() = this == SYNCED

    /** True while background work should be considered in flight. */
    val isInProgress: Boolean
        get() = this == COMPRESSING || this == UPLOADING

    /** True when the athlete is waiting on us rather than on themselves. */
    val isPending: Boolean
        get() = this != SYNCED && this != FAILED

    fun canTransitionTo(next: SyncStatus): Boolean =
        next in ALLOWED_TRANSITIONS.getValue(this)

    companion object {

        private val ALLOWED_TRANSITIONS: Map<SyncStatus, Set<SyncStatus>> = mapOf(

            RECORDED to setOf(COMPRESSING, FAILED),

            // COMPRESSING may fall back to RECORDED: if the process dies
            // mid-transcode, the half-written output is discarded and the
            // attempt starts over from the original recording.
            COMPRESSING to setOf(COMPRESSED, RECORDED, FAILED),

            // COMPRESSING is reachable from both because the transcode can
            // vanish underneath a queued attempt — Android reclaims storage,
            // users clear app data. Without this edge the worker asks to
            // recompress, the transition is refused, and the attempt retries
            // forever while showing the athlete "waiting for a connection".
            COMPRESSED to setOf(QUEUED, COMPRESSING, FAILED),

            QUEUED to setOf(UPLOADING, COMPRESSING, FAILED),

            // UPLOADING may fall back to QUEUED: a dropped connection is a
            // retry, not a failure, and the already-uploaded chunks are kept.
            UPLOADING to setOf(SYNCED, QUEUED, FAILED),

            // A failed attempt re-enters wherever its artifacts allow: back to
            // RECORDED to rebuild the transcode from the original video, or
            // straight to QUEUED to resume an upload whose chunks survive.
            FAILED to setOf(RECORDED, COMPRESSING, QUEUED),

            SYNCED to emptySet()
        )
    }
}
