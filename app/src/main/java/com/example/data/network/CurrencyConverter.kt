package com.example.data.network

import android.util.Log
import com.example.domain.model.AcquisitionType
import com.example.domain.model.CurrencyRatesSnapshot
import com.example.domain.model.RegionDefaults
import com.example.domain.model.TotalCostBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt

private const val FX_TAG = "CurrencyConverter"

class CurrencyConverter(private val httpClient: OkHttpClient) {

    suspend fun fetchLiveRates(): CurrencyRatesSnapshot = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.frankfurter.app/latest?from=EUR")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val ratesObj = json.getJSONObject("rates")
                    val map = mutableMapOf("EUR" to 1.0)
                    val keys = ratesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k.uppercase()] = ratesObj.getDouble(k)
                    }
                    return@withContext CurrencyRatesSnapshot(
                        baseCurrency = "EUR",
                        ratesFromEur = map,
                        retrievedAtMillis = System.currentTimeMillis(),
                        providerName = "ECB via Frankfurter.app",
                        isLiveRate = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(FX_TAG, "Frankfurter FX failed, trying ER-API fallback: ${e.message}")
        }

        try {
            val fallbackReq = Request.Builder()
                .url("https://open.er-api.com/v6/latest/EUR")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(fallbackReq).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val ratesObj = json.getJSONObject("rates")
                    val map = mutableMapOf("EUR" to 1.0)
                    val keys = ratesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k.uppercase()] = ratesObj.getDouble(k)
                    }
                    return@withContext CurrencyRatesSnapshot(
                        baseCurrency = "EUR",
                        ratesFromEur = map,
                        retrievedAtMillis = System.currentTimeMillis(),
                        providerName = "Open Exchange Rates API",
                        isLiveRate = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(FX_TAG, "All live FX endpoints failed: ${e.message}")
        }

        // Fallback indicative table ONLY labeled as non-live when offline/blocked
        CurrencyRatesSnapshot(
            baseCurrency = "EUR",
            ratesFromEur = mapOf(
                "EUR" to 1.0,
                "GBP" to 0.84,
                "USD" to 1.07,
                "CAD" to 1.49,
                "AUD" to 1.64,
                "CHF" to 0.94,
                "JPY" to 164.0
            ),
            retrievedAtMillis = System.currentTimeMillis(),
            providerName = "Offline Fallback (FX unreachable)",
            isLiveRate = false
        )
    }

    fun convert(amount: Double, fromCurrency: String, toCurrency: String, rates: CurrencyRatesSnapshot): Pair<Double, Double> {
        val from = fromCurrency.uppercase()
        val to = toCurrency.uppercase()
        if (from == to) return amount to 1.0
        val fromPerEur = rates.ratesFromEur[from] ?: 1.0
        val toPerEur = rates.ratesFromEur[to] ?: 1.0
        val rate = toPerEur / fromPerEur
        return round2(amount * rate) to rate
    }

    fun calculateTotalCost(
        advertisedPrice: Double,
        originalCurrency: String,
        shippingOriginal: Double?,
        sourceCountryCode: String,
        targetCountryCode: String,
        targetCurrency: String,
        acquisitionType: AcquisitionType,
        marketplaceId: String,
        rates: CurrencyRatesSnapshot
    ): TotalCostBreakdown {
        val (itemConverted, fxRate) = convert(advertisedPrice, originalCurrency, targetCurrency, rates)
        val shippingConverted = shippingOriginal?.let { round2(it * fxRate) }
        val notes = mutableListOf<String>()

        if (!rates.isLiveRate && !originalCurrency.equals(targetCurrency, ignoreCase = true)) {
            notes += "Indicative FX fallback (live FX unreachable)"
        }

        // Mandatory buyer fees (only when verified/known by platform policy)
        val buyerFeeConverted = when {
            marketplaceId == "ebay" && acquisitionType == AcquisitionType.PURCHASE -> 0.0
            marketplaceId == "reverb" -> 0.0 // Seller pays fees on Reverb
            else -> 0.0
        }

        // Cross-border VAT / customs calculation for physical imports into EU/Ireland from non-EU (e.g. GB, US, JP)
        val originCountry = RegionDefaults.countryFor(sourceCountryCode)
        val destCountry = RegionDefaults.countryFor(targetCountryCode)
        val isCrossBorderNonEuToEu = destCountry.isEuMember && !originCountry.isEuMember &&
            !sourceCountryCode.equals(targetCountryCode, ignoreCase = true) &&
            acquisitionType != AcquisitionType.SUBSCRIPTION

        val estimatedImportTaxConverted = if (isCrossBorderNonEuToEu && shippingConverted != null) {
            val cifValue = itemConverted + shippingConverted
            val (itemInEur, _) = convert(advertisedPrice + shippingOriginal, originalCurrency, "EUR", rates)
            val dutyRate = if (itemInEur > 150.0) 0.025 else 0.0
            val vatAmount = (cifValue * (1.0 + dutyRate)) * destCountry.standardVatRate
            val dutyAmount = cifValue * dutyRate
            notes += if (itemInEur > 150.0) {
                "Includes est. ${(destCountry.standardVatRate * 100).toInt()}% ${destCountry.code} import VAT + ~2.5% customs duty from ${originCountry.code}"
            } else {
                "Includes est. ${(destCountry.standardVatRate * 100).toInt()}% ${destCountry.code} import VAT from ${originCountry.code} (under €150 duty threshold)"
            }
            round2(vatAmount + dutyAmount)
        } else {
            0.0
        }

        if (shippingConverted == null) {
            notes += "Shipping unverified — total cost cannot be guaranteed"
        }
        if (acquisitionType == AcquisitionType.AUCTION) {
            notes += "Auction current bid — final hammer price may rise"
        }

        val totalVerified = shippingConverted != null && acquisitionType != AcquisitionType.AUCTION
        val totalEstimated = if (shippingConverted != null) {
            round2(itemConverted + shippingConverted + buyerFeeConverted + estimatedImportTaxConverted)
        } else {
            null
        }

        return TotalCostBreakdown(
            advertisedPriceOriginal = round2(advertisedPrice),
            originalCurrency = originalCurrency.uppercase(),
            targetCurrency = targetCurrency.uppercase(),
            fxRateApplied = fxRate,
            convertedItemPrice = itemConverted,
            shippingConverted = shippingConverted,
            mandatoryFeesConverted = buyerFeeConverted,
            estimatedImportVatOrDutyConverted = estimatedImportTaxConverted,
            totalEstimatedCostConverted = totalEstimated,
            isTotalVerified = totalVerified,
            costNotes = notes
        )
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
