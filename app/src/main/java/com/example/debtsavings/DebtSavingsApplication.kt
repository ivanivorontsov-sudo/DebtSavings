package com.example.debtsavings

import android.app.Application

class DebtSavingsApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("debt_savings_prefs", MODE_PRIVATE)
        val transactions = mutableListOf<Transaction>()
        val credits = mutableListOf<Credit>()
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadCredits(prefs, credits)

        // Correct accrual immediately when Android starts the app process.
        DailyInterest.applyIfNeeded(prefs, transactions, credits)
        InterestAlarmScheduler.scheduleNext(this)
    }
}
