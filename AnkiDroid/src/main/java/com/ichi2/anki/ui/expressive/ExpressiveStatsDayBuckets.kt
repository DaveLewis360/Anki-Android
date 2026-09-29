// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 AnkiDroid M3 Expressive contributors
package com.ichi2.anki.ui.expressive

import com.ichi2.anki.libanki.EpochSeconds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One scheduler day represented as a half-open epoch-millisecond interval. */
data class ExpressiveStatsDayBucket(
    val date: LocalDate,
    val startMillis: Long,
    val endMillis: Long,
) {
    fun contains(timestampMillis: Long): Boolean = timestampMillis >= startMillis && timestampMillis < endMillis
}

/**
 * Builds consecutive scheduler-day intervals ending at the backend-provided [nextDayAt].
 *
 * Calendar subtraction is intentional: scheduler days can be 23 or 25 hours across DST changes.
 */
internal fun expressiveStatsDayBuckets(
    nextDayAt: EpochSeconds,
    zoneId: ZoneId,
    numberOfDays: Int,
): List<ExpressiveStatsDayBucket> {
    require(numberOfDays > 0) { "numberOfDays must be positive" }
    val nextCutoff = Instant.ofEpochSecond(nextDayAt).atZone(zoneId)
    return (numberOfDays downTo 1).map { daysBeforeEnd ->
        val start = nextCutoff.minusDays(daysBeforeEnd.toLong())
        val end = nextCutoff.minusDays(daysBeforeEnd.toLong() - 1)
        ExpressiveStatsDayBucket(
            date = start.toLocalDate(),
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = end.toInstant().toEpochMilli(),
        )
    }
}

/** Returns the containing bucket index, or `-1` for future/out-of-horizon timestamps. */
internal fun List<ExpressiveStatsDayBucket>.indexOfTimestamp(timestampMillis: Long): Int {
    if (isEmpty() || timestampMillis < first().startMillis || timestampMillis >= last().endMillis) return -1
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val bucket = this[middle]
        when {
            timestampMillis < bucket.startMillis -> high = middle - 1
            timestampMillis >= bucket.endMillis -> low = middle + 1
            else -> return middle
        }
    }
    return -1
}

/** Current streak, allowing today to be empty while yesterday continues the streak. */
internal fun currentReviewStreak(
    reviewedDayIndexes: Set<Int>,
    todayIndex: Int,
): Int {
    var day = if (todayIndex in reviewedDayIndexes) todayIndex else todayIndex - 1
    var streak = 0
    while (day in reviewedDayIndexes) {
        streak++
        day--
    }
    return streak
}
