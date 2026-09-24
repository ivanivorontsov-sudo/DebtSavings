package com.example.debtsavings

/**
 * Кредит / кредитный лимит.
 *
 * [principal] — тело долга (отдельно от процентов и комиссий).
 * [accruedInterest] — начисленные проценты (не смешиваются с телом).
 * [accruedFees] — комиссии (в т.ч. за беспроцентный период).
 * [rate] — годовая ставка, % (по умолчанию WB: 59.8).
 * [graceMode] — беспроцентный период: вместо % начисляется комиссия.
 */
data class Credit(
    val id: String,
    var name: String,
    var rate: Double,                 // % годовых (annualRate)
    var termMonths: Int,
    var monthlyPayment: Double,       // обязательный/авто платёж (сумму задаёт пользователь)
    var principal: Double,            // тело долга
    var lastInterestDate: Long,       // начало дня последней начитки % / комиссий
    var accruedInterest: Double = 0.0,
    var accruedFees: Double = 0.0,
    var graceMode: Boolean = false,
    var principalSharePercent: Double = 0.0,   // 0 / 3 / 10
    var minPrincipalPaymentFloor: Double = 500.0,
    var paymentDay: Int = 25,                  // день месяца срока оплаты (после расчётного)
    var nextMinPaymentDue: Long = 0L,          // millis, срок мин. платежа
    var autoApplyMinPayment: Boolean = true,
    var contractStartDate: Long = 0L,          // дата договора (пропуск комиссии в 1-й мес. после 21-го)
    var lastFeeYearMonth: Int = 0,             // YYYYMM последней начисленной grace-комиссии
    var lastMinPaymentPeriodKey: Int = 0       // YYYYMM расчётного периода, за который мин. платёж уже учтён
)

data class Transaction(
    val id: String,
    val amount: Double,            // + пополнение / − списание
    val timestamp: Long,
    val note: String = "",
    val creditId: String? = null   // null = общий баланс, иначе привязка к кредиту
)

/** Разбивка платежа / мин. платежа: комиссии → проценты → тело. */
data class PaymentBreakdown(
    val fees: Double,
    val interest: Double,
    val principal: Double
) {
    val total: Double get() = fees + interest + principal
}

/** Период списания подписки. */
enum class BillingPeriod {
    MONTHLY,
    YEARLY,
    CUSTOM_DAYS
}

/**
 * Режим намерения до даты списания:
 * NEED_PAY — надо оплатить;
 * NEED_CANCEL — надо отключить до продления.
 */
enum class SubscriptionIntent {
    NEED_PAY,
    NEED_CANCEL
}

/**
 * Категория подписки (выбор опции, не свободный текст).
 * 1. CARD_ALERTS — уведомления по карте (SMS/push банка)
 * 2. DEBT_INSURANCE — страховка по долгу/кредиту (часто «надо отключить»)
 * 3. BANK_LIMITS — увеличенные лимиты в банке
 * 4. THIRD_PARTY — сторонние сервисы (стриминг, VPN, облако…)
 */
enum class SubscriptionCategory {
    CARD_ALERTS,
    DEBT_INSURANCE,
    BANK_LIMITS,
    THIRD_PARTY
}

data class Subscription(
    val id: String,
    var name: String,
    var price: Double,
    var period: BillingPeriod = BillingPeriod.MONTHLY,
    var customDays: Int = 30,
    var nextChargeDate: Long = 0L,
    var notes: String = "",
    var intent: SubscriptionIntent = SubscriptionIntent.NEED_PAY,
    var notifyDays: String = "7,3,1",
    var enabled: Boolean = true,
    var category: SubscriptionCategory = SubscriptionCategory.THIRD_PARTY
)

/** Шаблоны быстрых подписок (название, категория, период, цена-заглушка, намерение по умолчанию). */
data class SubscriptionPreset(
    val name: String,
    val category: SubscriptionCategory,
    val period: BillingPeriod,
    val price: Double,
    val intent: SubscriptionIntent,
    val notes: String = ""
)

object SubscriptionPresets {
    fun all(): List<SubscriptionPreset> = listOf(
        // Уведомления по карте
        SubscriptionPreset("Сбер — уведомления по карте", SubscriptionCategory.CARD_ALERTS, BillingPeriod.MONTHLY, 59.0, SubscriptionIntent.NEED_CANCEL, "SMS/push банка"),
        SubscriptionPreset("Тинькофф — уведомления", SubscriptionCategory.CARD_ALERTS, BillingPeriod.MONTHLY, 49.0, SubscriptionIntent.NEED_CANCEL),
        SubscriptionPreset("WB Банк — уведомления", SubscriptionCategory.CARD_ALERTS, BillingPeriod.MONTHLY, 99.0, SubscriptionIntent.NEED_CANCEL),
        // Страховка по долгу
        SubscriptionPreset("Страховка по кредиту (банк)", SubscriptionCategory.DEBT_INSURANCE, BillingPeriod.MONTHLY, 300.0, SubscriptionIntent.NEED_CANCEL, "Часто можно отключить в приложении банка"),
        SubscriptionPreset("Страховка WB / рассрочка", SubscriptionCategory.DEBT_INSURANCE, BillingPeriod.MONTHLY, 199.0, SubscriptionIntent.NEED_CANCEL),
        SubscriptionPreset("Страховка карты от мошенничества", SubscriptionCategory.DEBT_INSURANCE, BillingPeriod.MONTHLY, 99.0, SubscriptionIntent.NEED_CANCEL),
        // Увеличенные лимиты
        SubscriptionPreset("WB — увеличенный лимит / Кэш+", SubscriptionCategory.BANK_LIMITS, BillingPeriod.MONTHLY, 199.0, SubscriptionIntent.NEED_CANCEL),
        SubscriptionPreset("Подписка на лимит (банк)", SubscriptionCategory.BANK_LIMITS, BillingPeriod.MONTHLY, 299.0, SubscriptionIntent.NEED_CANCEL),
        SubscriptionPreset("Premium-лимит карты", SubscriptionCategory.BANK_LIMITS, BillingPeriod.MONTHLY, 399.0, SubscriptionIntent.NEED_PAY),
        // Сторонние сервисы
        SubscriptionPreset("Яндекс Плюс", SubscriptionCategory.THIRD_PARTY, BillingPeriod.MONTHLY, 299.0, SubscriptionIntent.NEED_PAY),
        SubscriptionPreset("IVI", SubscriptionCategory.THIRD_PARTY, BillingPeriod.MONTHLY, 399.0, SubscriptionIntent.NEED_PAY),
        SubscriptionPreset("VPN", SubscriptionCategory.THIRD_PARTY, BillingPeriod.MONTHLY, 199.0, SubscriptionIntent.NEED_PAY),
        SubscriptionPreset("Облако (Mail/Яндекс/Google)", SubscriptionCategory.THIRD_PARTY, BillingPeriod.MONTHLY, 149.0, SubscriptionIntent.NEED_PAY),
        SubscriptionPreset("Spotify / музыка", SubscriptionCategory.THIRD_PARTY, BillingPeriod.MONTHLY, 169.0, SubscriptionIntent.NEED_PAY)
    )

    fun label(cat: SubscriptionCategory): String = when (cat) {
        SubscriptionCategory.CARD_ALERTS -> "Уведомления по карте"
        SubscriptionCategory.DEBT_INSURANCE -> "Страховка по долгу / кредиту"
        SubscriptionCategory.BANK_LIMITS -> "Увеличенные лимиты в банке"
        SubscriptionCategory.THIRD_PARTY -> "Сторонние сервисы"
    }

    fun help(cat: SubscriptionCategory): String = when (cat) {
        SubscriptionCategory.CARD_ALERTS -> "Платные SMS/push-оповещения банка по операциям карты."
        SubscriptionCategory.DEBT_INSURANCE -> "Страховка к кредиту/рассрочке. Часто выгоднее отключить до списания."
        SubscriptionCategory.BANK_LIMITS -> "Платное повышение лимита / «буст» в банке (WB и др.)."
        SubscriptionCategory.THIRD_PARTY -> "Стриминг, VPN, облако, музыка и прочие внешние сервисы."
    }
}
