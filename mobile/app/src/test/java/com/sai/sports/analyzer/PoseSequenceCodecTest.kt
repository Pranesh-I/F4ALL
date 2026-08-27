package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PoseSequenceCodecTest {

    @Test
    fun `round trip preserves frames`() {

        val original = PoseFixtures.sitUpRep(startMs = 0, ascentFrames = 5, descentFrames = 5)

        val decoded = PoseSequenceCodec.decode(
            PoseSequenceCodec.encode(original, "Sit-ups")
        )

        assertEquals(original.size, decoded.size)

        original.zip(decoded).forEach { (source, result) ->
            assertEquals(source.timestampMs, result.timestampMs)
            assertEquals(
                source.points[PoseLandmarkIndex.LEFT_HIP].x,
                result.points[PoseLandmarkIndex.LEFT_HIP].x,
                0.0001f
            )
            assertEquals(
                source.points[PoseLandmarkIndex.LEFT_SHOULDER].y,
                result.points[PoseLandmarkIndex.LEFT_SHOULDER].y,
                0.0001f
            )
            assertEquals(
                source.points[PoseLandmarkIndex.LEFT_KNEE].visibility,
                result.points[PoseLandmarkIndex.LEFT_KNEE].visibility,
                0.0001f
            )
        }
    }

    @Test
    fun `a round tripped sequence scores identically`() {

        // The validation harness is only meaningful if replaying a saved
        // sequence gives the same answer as the live run did.
        val frames = mutableListOf<PoseFrame>()
        frames += PoseFixtures.hold(0, 155.0, 10)

        var cursor = 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
        repeat(3) {
            val rep = PoseFixtures.sitUpRep(cursor, ascentFrames = 40, descentFrames = 40)
            frames += rep
            cursor = rep.last().timestampMs + PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
            frames += PoseFixtures.hold(cursor, 155.0, 8)
            cursor += 8 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
        }

        val live = SitUpAnalyzer().analyzeSequence(frames)

        val replayed = SitUpAnalyzer().analyzeSequence(
            PoseSequenceCodec.decode(PoseSequenceCodec.encode(frames, "Sit-ups"))
        )

        assertEquals(live.score, replayed.score, 0.0)
    }

    @Test
    fun `header and blank lines are ignored`() {

        val text = """
            # f4all-pose-sequence v1 test=Sit-ups landmarks=33
            timestamp_ms,l0_x,l0_y,l0_z,l0_v

            0,0.5,0.5,0.0,0.9
            33,0.5,0.4,0.0,0.9
        """.trimIndent()

        val frames = PoseSequenceCodec.decode(text)

        assertEquals(2, frames.size)
        assertEquals(33L, frames[1].timestampMs)
    }

    @Test
    fun `truncated rows are skipped rather than throwing`() {

        val text = """
            0,0.5,0.5,0.0,0.9
            this-is-not-a-row
            33,0.5
            66,0.5,0.4,0.0,0.9
        """.trimIndent()

        val frames = PoseSequenceCodec.decode(text)

        assertEquals(2, frames.size)
        assertEquals(listOf(0L, 66L), frames.map { it.timestampMs })
    }

    @Test
    fun `decimal separator does not follow the system locale`() {

        // In a comma-decimal locale the default formatter writes "0,50000" and
        // silently adds a column to every row.
        val original = Locale.getDefault()

        try {
            Locale.setDefault(Locale.GERMANY)

            val encoded = PoseSequenceCodec.encode(
                frames = listOf(PoseFixtures.sitUpFrame(0, 90.0)),
                testName = "Sit-ups"
            )

            assertTrue(
                "Encoded output must use a decimal point",
                encoded.contains("0.5")
            )

            val decoded = PoseSequenceCodec.decode(encoded)

            assertEquals(1, decoded.size)
            assertEquals(
                PoseFrame.LANDMARK_COUNT,
                decoded.first().points.size
            )

        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `recorder caps collected frames`() {

        val recorder = PoseSequenceRecorder(maxFrames = 5)

        repeat(8) { index ->
            recorder.add(PoseFixtures.sitUpFrame(index.toLong(), 120.0))
        }

        assertEquals(5, recorder.size())
        assertEquals(3, recorder.droppedFrames)

        // The start of the attempt is kept, because that is where the jump
        // analyzer's calibration frames live.
        assertEquals(0L, recorder.frames().first().timestampMs)
    }
}
