package com.sai.sports.analyzer

/**
 * Everything an angle-driven rep counter needs except the exercise itself.
 *
 * Subclasses say which landmarks matter and turn a smoothed frame into a
 * [RepSample]; this class owns the frame gate, smoothing, side locking, the
 * [RepCycleTracker], the event trace and the final result. Keeping that in one
 * place means four exercises share one set of rejection rules — and one Python
 * port of them.
 *
 * Not thread-safe: feed it from one thread, as with [SitUpAnalyzer].
 */
abstract class RepExerciseAnalyzer(
    final override val testType: TestType,
    protected val repThresholds: RepThresholds,
    private val leftIndices: List<Int>,
    private val rightIndices: List<Int>,
    /**
     * Side-on exercises measure whichever side faces the camera and gate only
     * on it — the far arm of a push-up is hidden behind the body by design.
     * The rest need both sides of the body in view.
     */
    private val locksOneSide: Boolean
) : TestAnalyzer {

    /**
     * Shown when the start position was never held. Must begin with
     * "Start position never detected" — the phone translates on that prefix.
     */
    protected abstract val noStartReason: String

    /** Turns one gated, smoothed frame into what the rep machine consumes. */
    protected abstract fun sample(frame: PoseFrame, side: BodySide?): RepSample?

    private val leftGate = FrameQualityGate(leftIndices)
    private val rightGate = FrameQualityGate(rightIndices)
    private val bothGate = FrameQualityGate(leftIndices + rightIndices)

    private val smoother = PoseSmoother()

    protected val tracker = RepCycleTracker(repThresholds)

    private var repCount = 0
    private var partialReps = 0
    private var tooFastReps = 0
    private var formRejectedReps = 0

    private var framesAnalyzed = 0
    private var framesRejected = 0
    private var consecutiveRejectedFrames = 0
    private var visibilitySum = 0.0
    private var trackingLost = false
    private var ready = false

    /** The side a side-on exercise locked onto; null for two-sided exercises. */
    protected var lockedSide: BodySide? = null
        private set

    private var lastAngle: Double? = null
    private var lastIssues: Set<FormIssue> = emptySet()

    protected val events = mutableListOf<AnalyzerEvent>()

    override fun onFrame(frame: PoseFrame) {

        val verdict = evaluate(frame)

        if (!verdict.accepted) {
            framesRejected++
            consecutiveRejectedFrames++
            // A fault seen before the athlete left the frame is not still happening.
            lastIssues = emptySet()

            if (consecutiveRejectedFrames == AnalyzerThresholds.MAX_CONSECUTIVE_REJECTED_FRAMES) {
                trackingLost = true
                events += AnalyzerEvent(
                    timestampMs = frame.timestampMs,
                    label = "tracking_lost",
                    detail = "Body not fully visible for " +
                        "${AnalyzerThresholds.MAX_CONSECUTIVE_REJECTED_FRAMES} frames"
                )
            }
            return
        }

        consecutiveRejectedFrames = 0
        framesAnalyzed++
        visibilitySum += verdict.meanVisibility

        process(smoother.smooth(frame))
    }

    /**
     * Gates the frame, locking a side-on exercise to its visible side on the
     * first frame where either side passes. The lock holds for the attempt:
     * switching sides mid-attempt puts a step in the angle signal, and the
     * rep machine would read that step as a rep.
     */
    private fun evaluate(frame: PoseFrame): FrameQualityGate.Verdict {

        if (!locksOneSide) return bothGate.evaluate(frame)

        lockedSide?.let { return gateFor(it).evaluate(frame) }

        val left = leftGate.evaluate(frame)
        val right = rightGate.evaluate(frame)

        val side = when {
            left.accepted && right.accepted ->
                PoseMath.selectMoreVisibleSide(frame, leftIndices, rightIndices)
            left.accepted -> BodySide.LEFT
            right.accepted -> BodySide.RIGHT
            else -> return left
        }

        lockedSide = side
        events += AnalyzerEvent(
            timestampMs = frame.timestampMs,
            label = "side_locked",
            detail = side.name
        )

        return if (side == BodySide.LEFT) left else right
    }

    private fun gateFor(side: BodySide) =
        if (side == BodySide.LEFT) leftGate else rightGate

    /** One accepted, smoothed frame. Overridden by exercises with more than one tracker. */
    protected open fun process(frame: PoseFrame) {
        val sample = sample(frame, lockedSide) ?: return
        showLive(sample)
        tracker.update(sample, frame.timestampMs)?.let(::record)
    }

    protected fun showLive(sample: RepSample) {
        lastAngle = sample.angle
        lastIssues = sample.issues
    }

    /** Turns a rep machine decision into counts and a trace event. */
    protected fun record(event: RepEvent) {

        when (event) {

            is RepEvent.Ready -> {
                if (ready) return
                ready = true
                events += AnalyzerEvent(event.timestampMs, "ready", "Start position detected")
            }

            is RepEvent.Counted -> {
                repCount++
                events += AnalyzerEvent(
                    event.timestampMs,
                    "rep_counted",
                    "Rep $repCount in ${event.durationMs}ms"
                )
                event.warnings.forEach {
                    events += AnalyzerEvent(event.timestampMs, "form_warning", it.code)
                }
            }

            is RepEvent.Partial -> {
                partialReps++
                events += AnalyzerEvent(
                    event.timestampMs,
                    "rep_rejected_partial",
                    "Reached ${"%.0f".format(java.util.Locale.US, event.deepestAngle)} deg, " +
                        "needs ${repThresholds.depthAngle.toInt()} deg"
                )
            }

            is RepEvent.TooFast -> {
                tooFastReps++
                events += AnalyzerEvent(
                    event.timestampMs,
                    "rep_rejected_too_fast",
                    "${event.durationMs}ms is below the ${repThresholds.minRepDurationMs}ms minimum"
                )
            }

            is RepEvent.FormRejected -> {
                formRejectedReps++
                events += AnalyzerEvent(
                    event.timestampMs,
                    "rep_rejected_form",
                    event.issues.joinToString(",") { it.code }
                )
            }
        }
    }

    override fun currentScore(): Double = repCount.toDouble()

    override fun isReady(): Boolean = ready

    override fun eventsSince(fromIndex: Int): List<AnalyzerEvent> =
        if (fromIndex >= events.size) emptyList() else events.subList(fromIndex, events.size).toList()

    /** Live driving angle, for the on-screen readout. */
    fun currentAngle(): Double? = lastAngle

    /** Faults visible on the latest frame — what live form feedback reads. */
    fun currentIssues(): Set<FormIssue> = lastIssues

    fun rejectedPartialReps(): Int = partialReps

    fun rejectedFormReps(): Int = formRejectedReps

    override fun result(): AnalyzerResult {

        if (framesAnalyzed == 0) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = "No usable pose data — make sure your whole body is in frame",
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        if (!ready) {
            return AnalyzerResult.invalid(
                testType = testType,
                reason = noStartReason,
                framesAnalyzed = framesAnalyzed,
                framesRejected = framesRejected,
                events = events.toList()
            )
        }

        return AnalyzerResult(
            testType = testType,
            score = repCount.toDouble(),
            unit = testType.unit,
            status = AttemptStatus.COMPLETE,
            confidence = confidence(),
            framesAnalyzed = framesAnalyzed,
            framesRejected = framesRejected,
            events = events.toList()
        )
    }

    /** Same blend as [SitUpAnalyzer]: how clearly, times how much of the attempt, was seen. */
    private fun confidence(): Double {

        val meanVisibility = visibilitySum / framesAnalyzed
        val totalFrames = framesAnalyzed + framesRejected
        val acceptanceRatio =
            if (totalFrames == 0) 0.0 else framesAnalyzed.toDouble() / totalFrames

        val base = meanVisibility * acceptanceRatio

        return (if (trackingLost) base * 0.5 else base).coerceIn(0.0, 1.0)
    }

    override fun reset() {
        tracker.reset()
        repCount = 0
        partialReps = 0
        tooFastReps = 0
        formRejectedReps = 0
        framesAnalyzed = 0
        framesRejected = 0
        consecutiveRejectedFrames = 0
        visibilitySum = 0.0
        trackingLost = false
        ready = false
        lockedSide = null
        lastAngle = null
        lastIssues = emptySet()
        events.clear()
        smoother.reset()
    }

    protected companion object {

        fun index(side: BodySide, left: Int, right: Int): Int =
            if (side == BodySide.LEFT) left else right
    }
}
