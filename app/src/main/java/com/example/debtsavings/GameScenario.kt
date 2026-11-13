package com.example.debtsavings

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class GameScenarioProgress(
    val xp: Int,
    val levelNumber: Int,
    val levelTitle: String,
    val nextLevelTitle: String?,
    val xpToNextLevel: Int,
    val levelProgressPercent: Int,
    val totalActiveDays: Int,
    val activeDaysThisWeek: Int,
    val weeklyMissionDone: Boolean,
    val earnedBadges: List<String>
)

/**
 * Ненавязчивый игровой сценарий «Путь к финансовой свободе».
 *
 * Очки начисляются только за вручную записанный реальный платёж по кредиту
 * или пополнение накоплений. Сумма не влияет на награду, повторные операции
 * в тот же день не дают дополнительных очков. Системные проценты и авто-отметки
 * не засчитываются. Пропуски ничего не отнимают.
 */
object GameScenario {
    const val PREF_ENABLED = "game_mode_enabled"
    private const val XP_PER_ACTIVE_DAY = 25

    private data class Level(val minXp: Int, val title: String)

    private val levels = listOf(
        Level(0, "Новичок"),
        Level(50, "Навигатор"),
        Level(150, "Стратег"),
        Level(300, "Капитан"),
        Level(600, "Архитектор финансов")
    )

    fun calculate(
        transactions: List<Transaction>,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): GameScenarioProgress {
        val todayStart = startOfDay(now, timeZone)
        val activeDayStarts = transactions.asSequence()
            .filter { it.timestamp <= now && isCountableAction(it) }
            .map { startOfDay(it.timestamp, timeZone) }
            .filter { it <= todayStart }
            .toSortedSet()

        val activeDays = activeDayStarts.size
        val xp = activeDays * XP_PER_ACTIVE_DAY
        val levelIndex = levels.indexOfLast { xp >= it.minXp }.coerceAtLeast(0)
        val currentLevel = levels[levelIndex]
        val nextLevel = levels.getOrNull(levelIndex + 1)
        val progressPercent = if (nextLevel == null) {
            100
        } else {
            (((xp - currentLevel.minXp).toDouble() /
                (nextLevel.minXp - currentLevel.minXp).toDouble()) * 100.0)
                .toInt().coerceIn(0, 100)
        }

        val weekStart = startOfWeek(todayStart, timeZone)
        val activeDaysThisWeek = activeDayStarts.count { it >= weekStart }
        val badges = mutableListOf<String>()
        if (activeDays >= 1) badges.add("Первый шаг")
        if (activeDays >= 5) badges.add("Устойчивый ритм")
        if (activeDays >= 15) badges.add("Финансовая дисциплина")
        if (activeDays >= 30) badges.add("Архитектор порядка")

        return GameScenarioProgress(
            xp = xp,
            levelNumber = levelIndex + 1,
            levelTitle = currentLevel.title,
            nextLevelTitle = nextLevel?.title,
            xpToNextLevel = nextLevel?.minXp?.minus(xp) ?: 0,
            levelProgressPercent = progressPercent,
            totalActiveDays = activeDays,
            activeDaysThisWeek = activeDaysThisWeek,
            weeklyMissionDone = activeDaysThisWeek >= 1,
            earnedBadges = badges
        )
    }

    internal fun isCountableAction(transaction: Transaction): Boolean {
        if (!transaction.amount.isFinite() || transaction.amount <= 0.0) return false
        val note = transaction.note.trim().lowercase(Locale.ROOT)

        // Исключаем системные начисления и условные записи авто-платежа:
        // приложение не должно награждать за события, которые пользователь не совершал.
        if (note.startsWith("проценты накопительного счёта") ||
            note.startsWith("проценты накопительного счета") ||
            note.startsWith("проценты:") ||
            note.startsWith("авто-отметка в журнале") ||
            note.startsWith("комиссия грейса:")
        ) return false

        return transaction.creditId != null || note == "пополнение"
    }

    private fun startOfDay(timestamp: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun startOfWeek(todayStart: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = todayStart
        val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        return calendar.timeInMillis
    }
}
