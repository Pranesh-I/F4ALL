package com.sai.sports.analyzer

/**
 * Serialization for a recorded run of pose frames.
 *
 * A sequence is captured alongside every attempt and it earns its keep twice:
 *
 *  - The results screen replays the skeleton from it, with no video decoding.
 *  - The Sprint 3.4 validation harness replays reference recordings through the
 *    analyzers on a plain JVM, so thresholds can be re-tuned in seconds without
 *    a device, a camera, or a person willing to do another twenty sit-ups.
 *
 * CSV rather than JSON because these files are long and narrow, get diffed and
 * eyeballed during calibration, and open in a spreadsheet.
 *
 * Format:
 *
 *     # f4all-pose-sequence v1 test=Sit-ups landmarks=33
 *     timestamp_ms,l0_x,l0_y,l0_z,l0_v,l1_x,...
 *     0,0.51,0.32,-0.1,0.99,...
 */
object PoseSequenceCodec {

    /**
     * Locale.US is not optional here. In a locale that uses a decimal comma —
     * which includes several this app is meant to ship in — the default
     * formatter writes "0,51234" and every CSV row silently gains columns.
     */
    private val NUMBER_LOCALE: java.util.Locale = java.util.Locale.US

    const val VERSION = 1

    private const val HEADER_PREFIX = "# f4all-pose-sequence"

    fun encode(
        frames: List<PoseFrame>,
        testName: String,
        landmarkCount: Int = PoseFrame.LANDMARK_COUNT
    ): String {

        val builder = StringBuilder()

        builder.append(HEADER_PREFIX)
            .append(" v").append(VERSION)
            .append(" test=").append(testName)
            .append(" landmarks=").append(landmarkCount)
            .append('\n')

        builder.append("timestamp_ms")
        for (index in 0 until landmarkCount) {
            builder.append(",l").append(index).append("_x")
            builder.append(",l").append(index).append("_y")
            builder.append(",l").append(index).append("_z")
            builder.append(",l").append(index).append("_v")
        }
        builder.append('\n')

        for (frame in frames) {
            builder.append(frame.timestampMs)
            for (index in 0 until landmarkCount) {
                val point = frame.points.getOrNull(index)
                if (point == null) {
                    builder.append(",0,0,0,0")
                } else {
                    builder.append(',').append(format(point.x))
                    builder.append(',').append(format(point.y))
                    builder.append(',').append(format(point.z))
                    builder.append(',').append(format(point.visibility))
                }
            }
            builder.append('\n')
        }

        return builder.toString()
    }

    /**
     * Parses an encoded sequence. Malformed rows are skipped rather than thrown
     * on — a truncated recording from a killed process should still yield the
     * frames it did manage to write.
     */
    fun decode(text: String): List<PoseFrame> {

        val frames = mutableListOf<PoseFrame>()

        for (rawLine in text.lineSequence()) {

            val line = rawLine.trim()

            if (line.isEmpty()) continue
            if (line.startsWith("#")) continue
            if (line.startsWith("timestamp_ms")) continue

            val cells = line.split(',')

            if (cells.size < 5) continue

            val timestampMs = cells[0].toLongOrNull() ?: continue

            val points = mutableListOf<PosePoint>()
            var cursor = 1

            while (cursor + 3 < cells.size) {
                points += PosePoint(
                    x = cells[cursor].toFloatOrNull() ?: 0f,
                    y = cells[cursor + 1].toFloatOrNull() ?: 0f,
                    z = cells[cursor + 2].toFloatOrNull() ?: 0f,
                    visibility = cells[cursor + 3].toFloatOrNull() ?: 0f
                )
                cursor += 4
            }

            frames += PoseFrame(timestampMs = timestampMs, points = points)
        }

        return frames
    }

    private fun format(value: Float): String =
        String.format(NUMBER_LOCALE, "%.5f", value)
}

/**
 * Collects frames during an attempt.
 *
 * Capped so a forgotten running recording cannot exhaust memory on a low-end
 * device — at ~15 fps the cap is roughly twenty minutes, far beyond any test in
 * the battery. Once full it stops collecting rather than dropping the start,
 * because the calibration frames at the beginning are the ones the jump
 * analyzer cannot do without.
 */
class PoseSequenceRecorder(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES
) {

    private val frames = mutableListOf<PoseFrame>()

    var droppedFrames = 0
        private set

    fun add(frame: PoseFrame) {
        if (frames.size >= maxFrames) {
            droppedFrames++
            return
        }
        frames += frame
    }

    fun frames(): List<PoseFrame> = frames.toList()

    fun size(): Int = frames.size

    fun isEmpty(): Boolean = frames.isEmpty()

    fun clear() {
        frames.clear()
        droppedFrames = 0
    }

    companion object {
        const val DEFAULT_MAX_FRAMES = 18_000
    }
}
