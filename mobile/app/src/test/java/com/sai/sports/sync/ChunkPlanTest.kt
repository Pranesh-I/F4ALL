package com.sai.sports.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanTest {

    @Test
    fun `file dividing evenly produces exact chunks`() {

        val plan = ChunkPlan(fileSizeBytes = 1000, chunkSizeBytes = 100)

        assertEquals(10, plan.totalChunks)
        assertEquals(100, plan.sizeOf(9))
        assertEquals(900L, plan.offsetOf(9))
    }

    @Test
    fun `final chunk is short when the file does not divide evenly`() {

        val plan = ChunkPlan(fileSizeBytes = 1050, chunkSizeBytes = 100)

        assertEquals(11, plan.totalChunks)
        assertEquals(100, plan.sizeOf(9))
        assertEquals(50, plan.sizeOf(10))
    }

    @Test
    fun `chunk sizes sum to the file size`() {

        // Off-by-one here means a truncated or over-read upload that only
        // fails at the server's checksum, after the athlete paid for the data.
        listOf(1L, 99L, 100L, 101L, 5_242_880L, 6_339_211L).forEach { size ->

            val plan = ChunkPlan(size, ChunkPlan.DEFAULT_CHUNK_SIZE_BYTES)

            val total = (0 until plan.totalChunks).sumOf { plan.sizeOf(it).toLong() }

            assertEquals("Size $size", size, total)
        }
    }

    @Test
    fun `chunk offsets are contiguous with no gaps or overlaps`() {

        val plan = ChunkPlan(fileSizeBytes = 1050, chunkSizeBytes = 100)

        var expectedOffset = 0L

        for (index in 0 until plan.totalChunks) {
            assertEquals(expectedOffset, plan.offsetOf(index))
            expectedOffset += plan.sizeOf(index)
        }

        assertEquals(1050L, expectedOffset)
    }

    @Test
    fun `remaining chunks skips what the server already has`() {

        val plan = ChunkPlan(fileSizeBytes = 1000, chunkSizeBytes = 100)

        val remaining = plan.remainingChunks(setOf(0, 1, 2, 5))

        assertEquals(listOf(3, 4, 6, 7, 8, 9), remaining)
    }

    @Test
    fun `remaining chunks is empty once every chunk landed`() {

        val plan = ChunkPlan(fileSizeBytes = 1000, chunkSizeBytes = 100)

        assertTrue(plan.isComplete((0 until 10).toSet()))
        assertFalse(plan.isComplete((0 until 9).toSet()))
    }

    @Test
    fun `progress reflects chunks the server confirmed`() {

        val plan = ChunkPlan(fileSizeBytes = 1000, chunkSizeBytes = 100)

        assertEquals(0f, plan.progress(emptySet()), 0.001f)
        assertEquals(0.5f, plan.progress((0 until 5).toSet()), 0.001f)
        assertEquals(1f, plan.progress((0 until 10).toSet()), 0.001f)
    }

    @Test
    fun `empty file has no chunks`() {

        val plan = ChunkPlan(fileSizeBytes = 0, chunkSizeBytes = 100)

        assertEquals(0, plan.totalChunks)
        assertTrue(plan.isComplete(emptySet()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero chunk size is rejected`() {
        ChunkPlan(fileSizeBytes = 100, chunkSizeBytes = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of range chunk index is rejected`() {
        ChunkPlan(fileSizeBytes = 100, chunkSizeBytes = 100).sizeOf(5)
    }
}
