package com.example.debtsavings

data class Credit(
    val id: String,
    var name: String,
    var rate: Double,              // % годовых
    var termMonths: Int,
    var monthlyPayment: Double,
    var principal: Double,         // тело долга
    var lastInterestDate: Long     // начало дня последней начитки %
)

data class Transaction(
    val id: String,
    val amount: Double,            // + пополнение / − списание
    val timestamp: Long,
    val note: String = "",
    val creditId: String? = null   // null = общий баланс, иначе привязка к кредиту
)
