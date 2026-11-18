package com.example

import com.example.data.network.CurrencyConverter
import com.example.data.network.ProductMatchingEngine
import com.example.data.network.RawMarketplaceHit
import com.example.domain.model.CurrencyRatesSnapshot
import com.example.domain.model.ListingCondition
import com.example.domain.model.ParsedSearchIntent
import com.example.domain.model.SearchQueryRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceFloorEngineTest {

    private val converter = CurrencyConverter(OkHttpClient())
    private val matcher = ProductMatchingEngine(converter)

    private val sampleRates = CurrencyRatesSnapshot(
        baseCurrency = "EUR",
        ratesFromEur = mapOf("EUR" to 1.0, "GBP" to 0.85, "USD" to 1.10),
        retrievedAtMillis = System.currentTimeMillis(),
        providerName = "Test",
        isLiveRate = true
    )

    @Test
    fun `verified shipping ranks ahead of unknown shipping and calculates IE import VAT`() {
        val request = SearchQueryRequest(
            rawQuery = "RTX 4070",
            countryCode = "IE",
            targetCurrency = "EUR"
        )
        val intent = ParsedSearchIntent(
            originalQuery = "RTX 4070",
            canonicalProductName = "RTX 4070"
        )

        val hits = listOf(
            // Unknown shipping should NOT outrank verified total cost
            RawMarketplaceHit(
                title = "NVIDIA RTX 4070 12GB",
                marketplaceId = "adverts_ie",
                marketplaceName = "Adverts.ie",
                sellerName = "Dublin Seller",
                conditionRaw = "USED",
                price = 380.0,
                currency = "EUR",
                shippingCost = null,
                sourceCountryCode = "IE",
                locationText = "Dublin",
                sourceUrl = "https://www.adverts.ie/1"
            ),
            // Verified total in Ireland
            RawMarketplaceHit(
                title = "NVIDIA GeForce RTX 4070 12GB",
                marketplaceId = "cex_ie",
                marketplaceName = "CeX Ireland",
                sellerName = "CeX IE",
                conditionRaw = "USED",
                price = 420.0,
                currency = "EUR",
                shippingCost = 3.50,
                sourceCountryCode = "IE",
                locationText = "Ireland",
                sourceUrl = "https://ie.webuy.com/1"
            ),
            // UK import to Ireland adds 23% VAT + duty
            RawMarketplaceHit(
                title = "NVIDIA GeForce RTX 4070 12GB",
                marketplaceId = "scan_uk",
                marketplaceName = "Scan UK",
                sellerName = "Scan",
                conditionRaw = "NEW",
                price = 450.0,
                currency = "GBP",
                shippingCost = 15.0,
                sourceCountryCode = "GB",
                locationText = "UK",
                sourceUrl = "https://www.scan.co.uk/1"
            ),
            // Accessory trap should get low match confidence and warning flag
            RawMarketplaceHit(
                title = "RTX 4070 Empty Box Only Display",
                marketplaceId = "ebay",
                marketplaceName = "eBay",
                sellerName = "BoxSeller",
                conditionRaw = "USED",
                price = 25.0,
                currency = "EUR",
                shippingCost = 5.0,
                sourceCountryCode = "IE",
                locationText = "Cork",
                sourceUrl = "https://www.ebay.ie/1"
            )
        )

        val ranked = matcher.normalizeAndRank(hits, intent, request, sampleRates)
        assertEquals(4, ranked.size)

        // First result must be CeX IE (verified total, strong match)
        assertEquals("cex_ie", ranked[0].marketplaceId)
        assertTrue(ranked[0].costBreakdown.isTotalVerified)
        assertEquals(423.50, ranked[0].costBreakdown.totalEstimatedCostConverted!!, 0.01)

        // UK import must include estimated import VAT > 0
        val ukItem = ranked.first { it.marketplaceId == "scan_uk" }
        assertTrue(ukItem.costBreakdown.estimatedImportVatOrDutyConverted > 50.0)

        // Unknown shipping must remain null total & unverified
        val advertsItem = ranked.first { it.marketplaceId == "adverts_ie" }
        assertFalse(advertsItem.costBreakdown.isTotalVerified)
        assertNull(advertsItem.costBreakdown.totalEstimatedCostConverted)

        // Accessory / box-only trap flagged and demoted
        val boxItem = ranked.first { it.marketplaceId == "ebay" }
        assertTrue(boxItem.matchConfidence < 45)
        assertTrue(boxItem.suspiciousFlags.isNotEmpty())
    }

    @Test
    fun `rental terms calculate duration total and demote spare parts below full product`() {
        val request = SearchQueryRequest(
            rawQuery = "Fender Stratocaster",
            countryCode = "IE",
            targetCurrency = "EUR",
            rentalDurationDays = 7
        )
        val intent = ParsedSearchIntent(
            originalQuery = "Fender Stratocaster",
            canonicalProductName = "Fender Stratocaster"
        )

        val hits = listOf(
            RawMarketplaceHit(
                title = "Fender Stratocaster Knob Set Black",
                marketplaceId = "reverb",
                marketplaceName = "Reverb",
                sellerName = "Parts Shop",
                conditionRaw = "NEW",
                price = 10.0,
                currency = "USD",
                shippingCost = 5.0,
                sourceCountryCode = "US",
                locationText = "US",
                sourceUrl = "https://reverb.com/item/1"
            ),
            RawMarketplaceHit(
                title = "Fender Stratocaster Electric Guitar",
                marketplaceId = "reverb",
                marketplaceName = "Reverb",
                sellerName = "Guitar Shop",
                conditionRaw = "USED",
                price = 750.0,
                currency = "EUR",
                shippingCost = 20.0,
                sourceCountryCode = "DE",
                locationText = "Germany",
                sourceUrl = "https://reverb.com/item/2"
            ),
            RawMarketplaceHit(
                title = "Fender Stratocaster Rental",
                marketplaceId = "fat_llama",
                marketplaceName = "Fat Llama",
                sellerName = "Dublin Gear",
                conditionRaw = "RENTAL",
                acquisitionTypeRaw = "RENTAL",
                price = 30.0,
                currency = "EUR",
                shippingCost = 0.0,
                sourceCountryCode = "IE",
                locationText = "Dublin",
                sourceUrl = "https://fatllama.com/1",
                rentalDailyRate = 12.0,
                rentalWeeklyRate = 55.0,
                rentalDeposit = 150.0
            )
        )

        val ranked = matcher.normalizeAndRank(hits, intent, request, sampleRates)
        assertEquals(3, ranked.size)

        // Full guitar & rental rank ahead of low-confidence knob accessory
        assertTrue(ranked.last().title.contains("Knob Set"))
        assertTrue(ranked.last().matchConfidence < 45)
        assertTrue(ranked.last().suspiciousFlags.isNotEmpty())

        val rental = ranked.first { it.marketplaceId == "fat_llama" }
        assertEquals(55.0, rental.rentalTerms!!.totalForRequestedDurationConverted, 0.01)
    }
}
