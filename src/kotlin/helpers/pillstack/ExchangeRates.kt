package desu.mintgram.helpers.pillstack

import android.util.Log
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.Currency
import java.util.Locale

/**
 * USD-based exchange rates (fiat + BTC/ETH/TON) from Coinbase's public, key-free endpoint — same
 * one exteraGram's ExchangeRates uses. Plain HTTPS GET, no backend of our own involved.
 */
object ExchangeRates {
    private const val TAG = "MintgramPillStack"
    private const val URL_STRING = "https://api.coinbase.com/v2/exchange-rates?currency=USD"
    private const val STALE_MS = 5 * 60 * 1000L

    class State(val usdRates: Map<String, BigDecimal>) {
        fun getUsdRate(code: String?): BigDecimal? = code?.let { usdRates[it.uppercase()] }

        fun getRate(from: String, to: String): BigDecimal? {
            val fromRate = getUsdRate(from) ?: return null
            val toRate = getUsdRate(to) ?: return null
            if (toRate.signum() == 0) return null
            return fromRate.divide(toRate, 12, RoundingMode.HALF_UP)
        }
    }

    @Volatile private var cache: State? = null
    @Volatile private var cacheTimestamp: Long = 0L
    private var requestInFlight = false
    private val pendingCallbacks = ArrayList<Utilities.Callback<State?>>()
    private val sync = Object()

    fun getCached(): State? = cache

    fun clearCache() {
        cacheTimestamp = 0L
    }

    private fun isStale(): Boolean = cache == null || cacheTimestamp == 0L || System.currentTimeMillis() - cacheTimestamp >= STALE_MS

    fun fetch(callback: Utilities.Callback<State?>) {
        val state = cache
        if (state != null && !isStale()) {
            AndroidUtilities.runOnUIThread { callback.run(state) }
            return
        }
        val shouldStart: Boolean
        synchronized(sync) {
            pendingCallbacks.add(callback)
            shouldStart = !requestInFlight
            if (shouldStart) requestInFlight = true
        }
        if (!shouldStart) return
        Utilities.globalQueue.postRunnable {
            val result = try {
                fetchInternal()
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
            if (result != null) {
                cache = result
                cacheTimestamp = System.currentTimeMillis()
            }
            complete(result ?: cache)
        }
    }

    private fun fetchInternal(): State? {
        val connection = URL(URL_STRING).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val rates = JSONObject(body).getJSONObject("data").getJSONObject("rates")
            val map = HashMap<String, BigDecimal>()
            map["USD"] = BigDecimal.ONE
            val keys = rates.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                try {
                    val rate = BigDecimal(rates.getString(key))
                    if (rate.signum() != 0) {
                        // Coinbase gives "1 USD = X <currency>" — invert to USD-per-unit like the rest of this map.
                        map[key] = BigDecimal.ONE.divide(rate, 16, RoundingMode.HALF_UP)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "skip rate for $key", e)
                }
            }
            return State(map)
        } finally {
            connection.disconnect()
        }
    }

    private fun complete(state: State?) {
        val callbacks: List<Utilities.Callback<State?>>
        synchronized(sync) {
            requestInFlight = false
            callbacks = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
        }
        AndroidUtilities.runOnUIThread {
            callbacks.forEach { it.run(state) }
        }
    }

    fun resolveTargetCurrency(target: String?): String {
        val normalized = target?.trim()?.uppercase()
        if (normalized != null && normalized != "AUTO" && PillStackCurrencies.TARGET_CURRENCIES.contains(normalized)) {
            return normalized
        }
        return try {
            val localeCurrency = Currency.getInstance(Locale.getDefault()).currencyCode
            if (PillStackCurrencies.TARGET_CURRENCIES.contains(localeCurrency)) localeCurrency else "USD"
        } catch (e: Exception) {
            "USD"
        }
    }
}
