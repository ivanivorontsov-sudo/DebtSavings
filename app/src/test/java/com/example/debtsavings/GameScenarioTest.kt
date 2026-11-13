package com.example.debtsavings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GameScenarioTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun transaction(
        id: String,
        amount: Double,
        timestamp: Long,
        note: String,
        creditId: String? = null
    ) = Transaction(id, amount, timestamp, note, creditId)

    @Test
    fun countsOneDayOnceRegardlessOfNumberOrAmountOfOperations() {
        val day = at(2026, Calendar.NOVEMBER, 12)
        val progress = GameScenario.calculate(
            listOf(
                transaction("deposit-1", 100.0, day, "Пополнение"),
                transaction("deposit-2", 250000.0, day + 60_000, "Пополнение"),
                transaction("payment", 50.0, day + 120_000, "Платёж", "credit-1"),
                transaction("withdrawal", -10.0, day + 180_000, "Списание")
            ),
            now = at(2026, Calendar.NOVEMBER, 13),
            timeZone = utc
        )

        assertEquals(1, progress.totalActiveDays)
        assertEquals(25, progress.xp)
    }

    @Test
    fun weeklyMissionCountsOnlyCurrentWeekActivityDays() {
        val progress = GameScenario.calculate(
            listOf(
                transaction("previous-week", 10.0, at(2026, Calendar.NOVEMBER, 8), "Пополнение"),
                transaction("monday", 10.0, at(2026, Calendar.NOVEMBER, 9), "Пополнение"),
                transaction("thursday", 10.0, at(2026, Calendar.NOVEMBER, 12), "Пополнение")
            ),
            now = at(2026, Calendar.NOVEMBER, 13),
            timeZone = utc
        )

        assertEquals(3, progress.totalActiveDays)
        assertEquals(2, progress.activeDaysThisWeek)
        assertTrue(progress.weeklyMissionDone)
    }

    @Test
    fun systemInterestAndAutomaticPaymentEntriesDoNotEarnPoints() {
        val now = at(2026, Calendar.NOVEMBER, 13)
        val progress = GameScenario.calculate(
            listOf(
                transaction("savings-interest", 5.0, now, "Проценты накопительного счёта за 1 дн."),
                transaction("auto-payment", 200.0, now, "Авто-отметка в журнале (не платёж банку)", "credit-1"),
                transaction("credit-interest", 2.0, now, "Проценты: кредит за 1 дн.", "credit-1")
            ),
            now = now,
            timeZone = utc
        )

        assertEquals(0, progress.totalActiveDays)
        assertEquals(0, progress.xp)
        assertFalse(progress.weeklyMissionDone)
    }

    @Test
    fun levelsAndBadgesAreBasedOnDistinctRealActionDays() {
        val progress = GameScenario.calculate(
            listOf(
                transaction("day-1", 1.0, at(2026, Calendar.NOVEMBER, 9), "Пополнение"),
                transaction("day-2", 1.0, at(2026, Calendar.NOVEMBER, 10), "Пополнение")
            ),
            now = at(2026, Calendar.NOVEMBER, 13),
            timeZone = utc
        )

        assertEquals(50, progress.xp)
        assertEquals(2, progress.levelNumber)
        assertEquals("Навигатор", progress.levelTitle)
        assertTrue(progress.earnedBadges.contains("Первый шаг"))
    }
}
