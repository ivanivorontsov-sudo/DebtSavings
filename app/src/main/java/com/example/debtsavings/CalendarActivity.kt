package com.example.debtsavings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class CalendarActivity : AppCompatActivity() {

    enum class EventKind { CREDIT, SUBSCRIPTION }

    data class CalEvent(
        val key: String,
        val title: String,
        val amount: Double,
        val dueMillis: Long,
        val kind: EventKind
    )

    private lateinit var textMonthTitle: TextView
    private lateinit var weekdayHeader: LinearLayout
    private lateinit var calendarGrid: LinearLayout
    private lateinit var eventsContainer: LinearLayout
    private lateinit var emptyEvents: TextView
    private lateinit var textSelectedDay: TextView
    private lateinit var textCountdown: TextView
    private lateinit var textCountdownTitle: TextView
    private lateinit var chartUpcoming: SimpleBarChartView
    private lateinit var chartPrincipal: SimpleBarChartView
    private lateinit var textChartUpcomingHint: TextView

    private val credits = mutableListOf<Credit>()
    private val subscriptions = mutableListOf<Subscription>()
    private val allEvents = mutableListOf<CalEvent>()
    private val eventsByDay = mutableMapOf<Long, MutableList<CalEvent>>()

    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val monthCal = Calendar.getInstance(moscow)
    private var selectedDayStart = 0L
    private var countdownTarget: CalEvent? = null

    private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2; minimumFractionDigits = 0
    }
    private val monthTitleFmt = SimpleDateFormat("LLLL yyyy", Locale("ru", "RU")).apply {
        timeZone = moscow
    }
    private val dayTitleFmt = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru", "RU")).apply {
        timeZone = moscow
    }
    private val weekdays = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshCountdown()
            handler.postDelayed(this, 60_000L)
        }
    }

    private val prefs by lazy { getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        textMonthTitle = findViewById(R.id.textMonthTitle)
        weekdayHeader = findViewById(R.id.weekdayHeader)
        calendarGrid = findViewById(R.id.calendarGrid)
        eventsContainer = findViewById(R.id.eventsContainer)
        emptyEvents = findViewById(R.id.emptyEvents)
        textSelectedDay = findViewById(R.id.textSelectedDay)
        textCountdown = findViewById(R.id.textCountdown)
        textCountdownTitle = findViewById(R.id.textCountdownTitle)
        chartUpcoming = findViewById(R.id.chartUpcoming)
        chartPrincipal = findViewById(R.id.chartPrincipal)
        textChartUpcomingHint = findViewById(R.id.textChartUpcomingHint)

        findViewById<ImageButton>(R.id.btnPrevMonth).setOnClickListener {
            monthCal.add(Calendar.MONTH, -1)
            renderCalendar()
        }
        findViewById<ImageButton>(R.id.btnNextMonth).setOnClickListener {
            monthCal.add(Calendar.MONTH, 1)
            renderCalendar()
        }

        buildWeekdayHeader()
        loadData()
        selectedDayStart = DailyInterest.startOfMoscowDay(System.currentTimeMillis())
        renderCalendar()
        showEventsFor(selectedDayStart)
        renderCharts()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        renderCalendar()
        showEventsFor(selectedDayStart)
        renderCharts()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun loadData() {
        Persistence.loadCredits(prefs, credits)
        Persistence.loadSubscriptions(prefs, subscriptions)
        rebuildEvents()
    }

    private fun rebuildEvents() {
        allEvents.clear()
        eventsByDay.clear()

        for (c in credits) {
            if (c.principal <= 0.01 && c.accruedInterest <= 0.01 && c.accruedFees <= 0.01) continue
            val amount = WbCreditMath.calculateMinPayment(c).total.let {
                if (it > 0.01) it else c.monthlyPayment
            }
            val dates = projectCreditDues(c, monthsAhead = 6)
            dates.forEachIndexed { idx, due ->
                val ev = CalEvent(
                    key = "credit:${c.id}:$idx",
                    title = c.name,
                    amount = if (amount > 0.01) amount else c.monthlyPayment,
                    dueMillis = due,
                    kind = EventKind.CREDIT
                )
                allEvents.add(ev)
                val day = DailyInterest.startOfMoscowDay(due)
                eventsByDay.getOrPut(day) { mutableListOf() }.add(ev)
            }
        }

        for (s in subscriptions) {
            if (!s.enabled || s.nextChargeDate <= 0L) continue
            var due = s.nextChargeDate
            for (i in 0 until 2) {
                val ev = CalEvent(
                    key = "sub:${s.id}:$i",
                    title = s.name,
                    amount = s.price,
                    dueMillis = due,
                    kind = EventKind.SUBSCRIPTION
                )
                allEvents.add(ev)
                val day = DailyInterest.startOfMoscowDay(due)
                eventsByDay.getOrPut(day) { mutableListOf() }.add(ev)
                due = advanceSubscription(s, due)
            }
        }
    }

    private fun projectCreditDues(c: Credit, monthsAhead: Int): List<Long> {
        val out = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        if (c.nextMinPaymentDue > 0L) {
            val d = DailyInterest.startOfMoscowDay(c.nextMinPaymentDue)
            out.add(d)
            seen.add(d)
        }
        val shouldProject = c.monthlyPayment > 0.01 || c.autoApplyMinPayment
        if (!shouldProject && out.isEmpty()) return out

        val cal = Calendar.getInstance(moscow)
        if (c.nextMinPaymentDue > 0L) {
            cal.timeInMillis = c.nextMinPaymentDue
        } else {
            cal.timeInMillis = System.currentTimeMillis()
            val day = c.paymentDay.coerceIn(1, 28)
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDay))
            cal.set(Calendar.HOUR_OF_DAY, 12)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (DailyInterest.startOfMoscowDay(cal.timeInMillis) < DailyInterest.startOfMoscowDay(System.currentTimeMillis())) {
                cal.add(Calendar.MONTH, 1)
                val md = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(md))
            }
        }
        // nextMinPaymentDue already added; project further months from that date
        for (i in 0 until monthsAhead) {
            if (i > 0 || c.nextMinPaymentDue <= 0L) {
                // for i==0 with no next due we still add current projection
            }
            if (i > 0) {
                cal.add(Calendar.MONTH, 1)
                val day = c.paymentDay.coerceIn(1, 28)
                val md = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(md))
            } else if (c.nextMinPaymentDue > 0L) {
                // already have first; skip adding again and advance for subsequent
                continue
            }
            val d = DailyInterest.startOfMoscowDay(cal.timeInMillis)
            if (seen.add(d)) out.add(d)
        }
        // If we had nextMinPaymentDue, add months after it
        if (c.nextMinPaymentDue > 0L) {
            cal.timeInMillis = c.nextMinPaymentDue
            for (i in 1..monthsAhead) {
                cal.add(Calendar.MONTH, 1)
                val day = c.paymentDay.coerceIn(1, 28)
                val md = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(md))
                val d = DailyInterest.startOfMoscowDay(cal.timeInMillis)
                if (seen.add(d)) out.add(d)
            }
        }
        return out.sorted()
    }

    private fun advanceSubscription(s: Subscription, from: Long): Long {
        val cal = Calendar.getInstance(moscow)
        cal.timeInMillis = from
        when (s.period) {
            BillingPeriod.MONTHLY -> cal.add(Calendar.MONTH, 1)
            BillingPeriod.YEARLY -> cal.add(Calendar.YEAR, 1)
            BillingPeriod.CUSTOM_DAYS -> cal.add(Calendar.DAY_OF_MONTH, s.customDays.coerceAtLeast(1))
        }
        return cal.timeInMillis
    }

    private fun buildWeekdayHeader() {
        weekdayHeader.removeAllViews()
        for (w in weekdays) {
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = w
                textSize = 12f
                setTextColor(getColor(R.color.text_tertiary))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 4, 0, 4)
            }
            weekdayHeader.addView(tv)
        }
    }

    private fun renderCalendar() {
        textMonthTitle.text = monthTitleFmt.format(monthCal.time).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale("ru", "RU")) else it.toString()
        }
        calendarGrid.removeAllViews()

        val first = monthCal.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        first.set(Calendar.HOUR_OF_DAY, 0)
        first.set(Calendar.MINUTE, 0)
        first.set(Calendar.SECOND, 0)
        first.set(Calendar.MILLISECOND, 0)
        // Calendar.MONDAY=2 … Sunday=1; convert to Mon=0
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayStart = DailyInterest.startOfMoscowDay(System.currentTimeMillis())

        var dayNum = 1
        var cells = 0
        while (dayNum <= daysInMonth || cells % 7 != 0) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (col in 0 until 7) {
                val cell = LayoutInflater.from(this).inflate(R.layout.item_calendar_day, row, false)
                val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
                cell.layoutParams = lp
                val dayNumber = cell.findViewById<TextView>(R.id.dayNumber)
                val dayDots = cell.findViewById<LinearLayout>(R.id.dayDots)
                dayDots.removeAllViews()

                if (cells < lead || dayNum > daysInMonth) {
                    dayNumber.text = ""
                    cell.isClickable = false
                } else {
                    val d = dayNum
                    val cellCal = first.clone() as Calendar
                    cellCal.set(Calendar.DAY_OF_MONTH, d)
                    val dayStart = DailyInterest.startOfMoscowDay(cellCal.timeInMillis)
                    dayNumber.text = d.toString()
                    val events = eventsByDay[dayStart].orEmpty()
                    val hasCredit = events.any { it.kind == EventKind.CREDIT }
                    val hasSub = events.any { it.kind == EventKind.SUBSCRIPTION }
                    if (hasCredit) dayDots.addView(makeDot(getColor(R.color.cal_credit)))
                    if (hasSub) dayDots.addView(makeDot(getColor(R.color.cal_subscription)))

                    when {
                        dayStart == selectedDayStart -> {
                            cell.setBackgroundColor(getColor(R.color.cal_selected_bg))
                            dayNumber.setTextColor(getColor(R.color.accent_dark))
                        }
                        dayStart == todayStart -> {
                            cell.setBackgroundColor(getColor(R.color.cal_today_bg))
                        }
                        else -> cell.setBackgroundColor(0)
                    }
                    cell.setOnClickListener {
                        selectedDayStart = dayStart
                        renderCalendar()
                        showEventsFor(dayStart)
                        if (events.isNotEmpty()) {
                            startCountdown(events.first())
                        } else {
                            clearCountdown()
                        }
                    }
                    dayNum++
                }
                row.addView(cell)
                cells++
            }
            calendarGrid.addView(row)
            if (dayNum > daysInMonth && cells % 7 == 0) break
            if (cells > 42) break
        }
    }

    private fun makeDot(color: Int): View {
        val v = View(this)
        val size = dp(6)
        val lp = LinearLayout.LayoutParams(size, size).apply { setMargins(dp(1), 0, dp(1), 0) }
        v.layoutParams = lp
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        v.background = bg
        return v
    }

    private fun showEventsFor(dayStart: Long) {
        textSelectedDay.text = dayTitleFmt.format(Date(dayStart)).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale("ru", "RU")) else it.toString()
        }
        eventsContainer.removeAllViews()
        val events = eventsByDay[dayStart].orEmpty().sortedBy { it.kind.ordinal }
        if (events.isEmpty()) {
            emptyEvents.visibility = View.VISIBLE
            return
        }
        emptyEvents.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (ev in events) {
            val row = inflater.inflate(R.layout.item_calendar_event, eventsContainer, false)
            row.findViewById<View>(R.id.eventColor).setBackgroundColor(
                getColor(if (ev.kind == EventKind.CREDIT) R.color.cal_credit else R.color.cal_subscription)
            )
            row.findViewById<TextView>(R.id.eventTitle).text = ev.title
            row.findViewById<TextView>(R.id.eventSubtitle).text =
                if (ev.kind == EventKind.CREDIT) "Мин. платёж по кредиту" else "Списание подписки"
            row.findViewById<TextView>(R.id.eventAmount).text =
                if (ev.amount > 0.01) "${moneyFormat.format(ev.amount)} ₽" else "—"
            row.setOnClickListener { startCountdown(ev) }
            eventsContainer.addView(row)
        }
    }

    private fun startCountdown(ev: CalEvent) {
        countdownTarget = ev
        textCountdownTitle.visibility = View.VISIBLE
        textCountdownTitle.text = ev.title
        refreshCountdown()
    }

    private fun clearCountdown() {
        countdownTarget = null
        textCountdownTitle.visibility = View.GONE
        textCountdown.text = getString(R.string.calendar_countdown_pick)
        textCountdown.setTextColor(getColor(R.color.accent))
    }

    private fun refreshCountdown() {
        val ev = countdownTarget ?: return
        val now = System.currentTimeMillis()
        val target = ev.dueMillis
        val today = DailyInterest.startOfMoscowDay(now)
        val dueDay = DailyInterest.startOfMoscowDay(target)
        val diff = target - now

        when {
            dueDay == today -> {
                textCountdown.text = "Сегодня"
                textCountdown.setTextColor(getColor(R.color.warning_orange))
            }
            diff < 0 -> {
                val overdue = -diff
                val days = TimeUnit.MILLISECONDS.toDays(overdue)
                val hours = TimeUnit.MILLISECONDS.toHours(overdue) % 24
                val mins = TimeUnit.MILLISECONDS.toMinutes(overdue) % 60
                textCountdown.text = when {
                    days > 0 -> "Просрочено на $days дн. $hours ч."
                    hours > 0 -> "Просрочено на $hours ч. $mins мин."
                    else -> "Просрочено на $mins мин."
                }
                textCountdown.setTextColor(getColor(R.color.debt_red))
            }
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
                val mins = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
                textCountdown.text = when {
                    days >= 2 -> "До платежа: $days дн. $hours ч."
                    days >= 1 -> "До платежа: $days дн. $hours ч. $mins мин."
                    hours >= 1 -> "До платежа: $hours ч. $mins мин."
                    else -> "До платежа: $mins мин."
                }
                textCountdown.setTextColor(getColor(R.color.accent))
            }
        }
    }

    private fun renderCharts() {
        val today = DailyInterest.startOfMoscowDay(System.currentTimeMillis())
        val end = today + 60L * 24 * 60 * 60 * 1000
        // Aggregate by week (7-day buckets) for readability
        val buckets = LinkedHashMap<String, Double>()
        val bucketStarts = mutableListOf<Long>()
        for (w in 0 until 9) {
            val start = today + w * 7L * 24 * 60 * 60 * 1000
            if (start > end) break
            bucketStarts.add(start)
            val label = SimpleDateFormat("d.MM", Locale("ru", "RU")).apply { timeZone = moscow }
                .format(Date(start))
            buckets[label] = 0.0
        }
        var total = 0.0
        for (ev in allEvents) {
            val d = DailyInterest.startOfMoscowDay(ev.dueMillis)
            if (d < today || d > end) continue
            val weekIdx = ((d - today) / (7L * 24 * 60 * 60 * 1000)).toInt().coerceIn(0, bucketStarts.size - 1)
            if (weekIdx in bucketStarts.indices) {
                val key = buckets.keys.elementAt(weekIdx)
                buckets[key] = (buckets[key] ?: 0.0) + ev.amount.coerceAtLeast(0.0)
                total += ev.amount.coerceAtLeast(0.0)
            }
        }
        val creditColor = getColor(R.color.chart_bar)
        chartUpcoming.setBars(
            buckets.map { (k, v) -> SimpleBarChartView.Bar(k, v, creditColor) },
            horizontal = false,
            emptyMessage = "Нет платежей в ближайшие 60 дней"
        )
        textChartUpcomingHint.text = "Сумма за 60 дн.: ${moneyFormat.format(total)} ₽ · столбцы — недели"

        val debtColor = getColor(R.color.cal_credit)
        val principalBars = credits
            .filter { it.principal > 0.01 }
            .sortedByDescending { it.principal }
            .map { SimpleBarChartView.Bar(it.name, it.principal, debtColor) }
        chartPrincipal.setBars(
            principalBars,
            horizontal = true,
            emptyMessage = "Нет кредитов с телом долга"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
