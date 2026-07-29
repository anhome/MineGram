package desu.mintgram.helpers.pillstack

import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Target currencies a crypto/rate pill can be pointed at (long-press menu). Kept to a fixed list
 * rather than exteraGram's full localized-name catalogue — codes only, still functional.
 */
object PillStackCurrencies {
    val TARGET_CURRENCIES = arrayOf(
        "AUTO", "USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN", "AED", "CNY", "JPY", "INR",
    )

    fun getTargetCurrencies(exclude: String? = null): Array<String> {
        if (exclude.isNullOrEmpty()) return TARGET_CURRENCIES
        return TARGET_CURRENCIES.filter { !it.equals(exclude, ignoreCase = true) }.toTypedArray()
    }

    fun getTargetCurrencyLabel(code: String?): CharSequence {
        if (code == null || code.equals("AUTO", ignoreCase = true)) {
            return LocaleController.getString(R.string.QualityAuto)
        }
        return code.uppercase()
    }

    fun formatFiatPrice(amount: BigDecimal, currencyCode: String): String? {
        return try {
            val currency = Currency.getInstance(currencyCode.uppercase())
            val scale = maxOf(0, currency.defaultFractionDigits)
            val scaled = amount.setScale(scale, RoundingMode.HALF_UP)
            val format = NumberFormat.getNumberInstance(Locale.US)
            format.isGroupingUsed = true
            format.minimumFractionDigits = scale
            format.maximumFractionDigits = scale
            val symbol = currency.getSymbol(Locale.US)
            val amountText = format.format(scaled)
            if (symbol.equals(currencyCode, ignoreCase = true)) "$amountText $currencyCode" else "$symbol$amountText"
        } catch (e: Exception) {
            null
        }
    }
}
