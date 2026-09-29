// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressiveStatsDayBucketsTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `spring DST scheduler day is 23 hours with half open boundaries`() {
        val nextCutoff = cutoff(2025, 3, 10, newYork)
        val buckets = expressiveStatsDayBuckets(nextCutoff, newYork, 2)
        val springDay = buckets.first()

        assertEquals(
            LocalDateTime
                .of(2025, 3, 8, 4, 0)
                .atZone(newYork)
                .toInstant()
                .toEpochMilli(),
            springDay.startMillis,
        )
        assertEquals(
            LocalDateTime
                .of(2025, 3, 9, 4, 0)
                .atZone(newYork)
                .toInstant()
                .toEpochMilli(),
            springDay.endMillis,
        )
        assertEquals(23, Duration.ofMillis(springDay.endMillis - springDay.startMillis).toHours())
        assertFalse(springDay.contains(springDay.startMillis - 1))
        assertTrue(springDay.contains(springDay.startMillis))
        assertFalse(springDay.contains(springDay.endMillis))
    }

    @Test
    fun `fall DST scheduler day is 25 hours and contains both repeated hours`() {
        val nextCutoff = cutoff(2025, 11, 3, newYork)
        val buckets = expressiveStatsDayBuckets(nextCutoff, newYork, 2)
        val fallDay = buckets.first()
        val firstRepeatedHour = ZonedDateTime.parse("2025-11-02T01:30-04:00[America/New_York]").toInstant().toEpochMilli()
        val secondRepeatedHour = ZonedDateTime.parse("2025-11-02T01:30-05:00[America/New_York]").toInstant().toEpochMilli()

        assertEquals(25, Duration.ofMillis(fallDay.endMillis - fallDay.startMillis).toHours())
        assertTrue(fallDay.contains(firstRepeatedHour))
        assertTrue(fallDay.contains(secondRepeatedHour))
    }

    @Test
    fun `bucket lookup rejects future and out of horizon timestamps`() {
        val buckets = expressiveStatsDayBuckets(cutoff(2025, 1, 5, ZoneId.of("UTC")), ZoneId.of("UTC"), 7)

        assertEquals(-1, buckets.indexOfTimestamp(buckets.first().startMillis - 1))
        assertEquals(0, buckets.indexOfTimestamp(buckets.first().startMillis))
        assertEquals(6, buckets.indexOfTimestamp(buckets.last().endMillis - 1))
        assertEquals(-1, buckets.indexOfTimestamp(buckets.last().endMillis))
        assertEquals(-1, buckets.indexOfTimestamp(Long.MAX_VALUE))
        assertEquals(-1, buckets.indexOfTimestamp(Long.MIN_VALUE))
    }

    @Test
    fun `seven scheduler days preserve dates across year boundary`() {
        val buckets = expressiveStatsDayBuckets(cutoff(2025, 1, 5, ZoneId.of("UTC")), ZoneId.of("UTC"), 7)

        assertEquals(
            listOf("2024-12-29", "2024-12-30", "2024-12-31", "2025-01-01", "2025-01-02", "2025-01-03", "2025-01-04"),
            buckets.map { it.date.toString() },
        )
    }

    @Test
    fun `streak does not treat yesterday as today`() {
        assertEquals(1, currentReviewStreak(setOf(5), todayIndex = 6))
        assertEquals(2, currentReviewStreak(setOf(4, 5), todayIndex = 6))
        assertEquals(3, currentReviewStreak(setOf(4, 5, 6), todayIndex = 6))
        assertEquals(0, currentReviewStreak(setOf(3, 4), todayIndex = 6))
    }

    @Test
    fun `same cutoff instant uses the requested timezone deterministically`() {
        val nextCutoff = cutoff(2025, 6, 15, ZoneId.of("UTC"))
        val utcBucket = expressiveStatsDayBuckets(nextCutoff, ZoneId.of("UTC"), 1).single()
        val losAngelesBucket = expressiveStatsDayBuckets(nextCutoff, ZoneId.of("America/Los_Angeles"), 1).single()

        assertEquals("2025-06-14", utcBucket.date.toString())
        assertEquals("2025-06-13", losAngelesBucket.date.toString())
    }

    private fun cutoff(
        year: Int,
        month: Int,
        day: Int,
        zoneId: ZoneId,
    ): Long = LocalDateTime.of(year, month, day, 4, 0).atZone(zoneId).toEpochSecond()
}
