package com.example.domain.model

enum class ListingCondition(val displayLabel: String, val qualityRank: Int) {
    NEW("New", 15),
    OPEN_BOX("Open Box", 14),
    LIKE_NEW("Like New", 13),
    REFURBISHED("Refurbished", 12),
    VERY_GOOD("Very Good", 11),
    GOOD("Good", 10),
    ACCEPTABLE("Acceptable", 9),
    USED("Used", 8),
    GRADED("Graded", 7),
    UNGRADED("Ungraded", 6),
    CLEARANCE("Clearance", 5),
    RENTAL("Rental", 4),
    SUBSCRIPTION("Subscription", 3),
    AUCTION("Auction", 2),
    DAMAGED("Damaged / Parts", 1);

    companion object {
        fun fromRaw(raw: String?): ListingCondition {
            if (raw.isNullOrBlank()) return USED
            val normalized = raw.trim().uppercase().replace(" ", "_").replace("-", "_")
            return entries.firstOrNull { it.name == normalized } ?: when {
                normalized.contains("NEW") && normalized.contains("LIKE") -> LIKE_NEW
                normalized.contains("OPEN") && normalized.contains("BOX") -> OPEN_BOX
                normalized.contains("REFURB") || normalized.contains("RENEW") -> REFURBISHED
                normalized.contains("VERY_GOOD") || normalized.contains("GRADE_A") || normalized.contains("MINT") -> VERY_GOOD
                normalized.contains("GOOD") || normalized.contains("GRADE_B") -> GOOD
                normalized.contains("ACCEPTABLE") || normalized.contains("FAIR") || normalized.contains("GRADE_C") -> ACCEPTABLE
                normalized.contains("DAMAG") || normalized.contains("PART") || normalized.contains("FAULTY") || normalized.contains("BROKEN") -> DAMAGED
                normalized.contains("RENT") || normalized.contains("HIRE") -> RENTAL
                normalized.contains("AUCTION") || normalized.contains("BID") -> AUCTION
                normalized.contains("SUBSCR") -> SUBSCRIPTION
                normalized.contains("CLEAR") -> CLEARANCE
                normalized.contains("GRADED") && !normalized.contains("UNGRADED") -> GRADED
                normalized.contains("UNGRADED") -> UNGRADED
                normalized.contains("NEW") -> NEW
                else -> USED
            }
        }
    }
}

enum class AcquisitionType(val label: String) {
    PURCHASE("Buy Outright"),
    AUCTION("Auction"),
    RENTAL("Rental"),
    SUBSCRIPTION("Subscription");

    companion object {
        fun fromRaw(raw: String?): AcquisitionType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PURCHASE
    }
}

enum class SourceStatus(val label: String) {
    SEARCHING("Searching"),
    SEARCHED("Searched"),
    NO_RESULTS("0 Results"),
    UNAVAILABLE("Unavailable"),
    REQUIRES_AUTH("API Key Needed"),
    UNSUPPORTED("Unsupported")
}

enum class SourceCategory(val label: String) {
    GENERAL("General Marketplace"),
    TECH("Electronics & Tech"),
    SPECIALIST("Specialist & Collectibles"),
    RENTALS("Rentals & Subscriptions"),
    DISCOVERY("Search & Aggregators"),
    AUCTIONS("Auctions & Clearance")
}

enum class SortOption(val label: String) {
    CHEAPEST_TOTAL("Cheapest Total Cost"),
    CHEAPEST_ADVERTISED("Cheapest Item Price"),
    NEWEST("Newest Listings"),
    BEST_CONDITION("Best Condition"),
    CLOSEST_LOCATION("Closest Location"),
    HIGHEST_MATCH("Highest Match Confidence")
}

data class SupportedCountry(
    val code: String,
    val name: String,
    val defaultCurrency: String,
    val isEuMember: Boolean,
    val standardVatRate: Double
)

object RegionDefaults {
    val countries = listOf(
        SupportedCountry("IE", "Ireland", "EUR", isEuMember = true, standardVatRate = 0.23),
        SupportedCountry("GB", "United Kingdom", "GBP", isEuMember = false, standardVatRate = 0.20),
        SupportedCountry("DE", "Germany", "EUR", isEuMember = true, standardVatRate = 0.19),
        SupportedCountry("FR", "France", "EUR", isEuMember = true, standardVatRate = 0.20),
        SupportedCountry("US", "United States", "USD", isEuMember = false, standardVatRate = 0.08),
        SupportedCountry("CA", "Canada", "CAD", isEuMember = false, standardVatRate = 0.13),
        SupportedCountry("AU", "Australia", "AUD", isEuMember = false, standardVatRate = 0.10)
    )

    val currencies = listOf(
        "EUR" to "€",
        "GBP" to "£",
        "USD" to "$",
        "CAD" to "CA$",
        "AUD" to "A$",
        "CHF" to "CHF ",
        "JPY" to "¥"
    )

    fun symbolFor(currency: String): String =
        currencies.firstOrNull { it.first == currency.uppercase() }?.second ?: "$currency "

    fun countryFor(code: String): SupportedCountry =
        countries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: countries.first()
}

data class SearchQueryRequest(
    val rawQuery: String,
    val countryCode: String = "IE",
    val postcode: String = "",
    val targetCurrency: String = "EUR",
    val selectedConditions: Set<ListingCondition> = emptySet(),
    val includeRentals: Boolean = true,
    val includeAuctions: Boolean = true,
    val rentalDurationDays: Int = 7,
    val verifiedTotalOnly: Boolean = false,
    val maxTotalPrice: Double? = null
)

data class ParsedSearchIntent(
    val originalQuery: String,
    val canonicalProductName: String,
    val category: String = "GENERAL",
    val alternativeSearchTerms: List<String> = emptyList(),
    val modelIdentifiers: List<String> = emptyList(),
    val preferredConditions: List<ListingCondition> = emptyList(),
    val includeRentalsHint: Boolean = true,
    val includeAuctionsHint: Boolean = true,
    val rentalDurationDaysHint: Int? = null,
    val targetCountryHint: String? = null,
    val aiExpanded: Boolean = false,
    val aiNote: String? = null
)

data class RentalTerms(
    val dailyRateConverted: Double?,
    val weeklyRateConverted: Double?,
    val monthlyRateConverted: Double?,
    val minimumDurationDays: Int = 1,
    val depositConverted: Double? = null,
    val deliveryCollectionFeeConverted: Double? = null,
    val totalForRequestedDurationConverted: Double,
    val requestedDurationDays: Int
)

data class AuctionDetails(
    val currentBidConverted: Double,
    val bidCount: Int?,
    val timeRemainingText: String?,
    val buyItNowConverted: Double? = null
)

data class TotalCostBreakdown(
    val advertisedPriceOriginal: Double,
    val originalCurrency: String,
    val targetCurrency: String,
    val fxRateApplied: Double,
    val convertedItemPrice: Double,
    val shippingConverted: Double?, // null explicitly means Unknown
    val mandatoryFeesConverted: Double,
    val estimatedImportVatOrDutyConverted: Double,
    val totalEstimatedCostConverted: Double?, // null if shipping is unknown and cannot verify total
    val isTotalVerified: Boolean,
    val costNotes: List<String> = emptyList()
)

data class ProductListing(
    val id: String,
    val title: String,
    val marketplaceId: String,
    val marketplaceName: String,
    val sellerName: String,
    val sellerReputation: String? = null,
    val condition: ListingCondition,
    val acquisitionType: AcquisitionType,
    val originalPrice: Double,
    val originalCurrency: String,
    val shippingOriginal: Double?, // null = Unknown
    val sourceCountryCode: String,
    val locationText: String,
    val availabilityText: String,
    val listingDateText: String? = null,
    val retrievedAtMillis: Long,
    val sourceUrl: String,
    val imageUrl: String? = null,
    val matchConfidence: Int, // 0..100
    val matchReason: String,
    val variantSummary: String? = null,
    val suspiciousFlags: List<String> = emptyList(),
    val rentalTerms: RentalTerms? = null,
    val auctionDetails: AuctionDetails? = null,
    val costBreakdown: TotalCostBreakdown
)

data class SourceDescriptor(
    val id: String,
    val name: String,
    val category: SourceCategory,
    val regionLabel: String,
    val integrationMethod: String
)

data class SourceTelemetry(
    val descriptor: SourceDescriptor,
    val status: SourceStatus,
    val resultCount: Int = 0,
    val latencyMs: Long = 0L,
    val message: String = "",
    val requiredSecretNames: List<String> = emptyList()
)

data class CurrencyRatesSnapshot(
    val baseCurrency: String,
    val ratesFromEur: Map<String, Double>,
    val retrievedAtMillis: Long,
    val providerName: String,
    val isLiveRate: Boolean
)

data class LiveSearchSnapshot(
    val request: SearchQueryRequest,
    val intent: ParsedSearchIntent,
    val listings: List<ProductListing>,
    val telemetry: List<SourceTelemetry>,
    val currencySnapshot: CurrencyRatesSnapshot?,
    val isSearching: Boolean,
    val startedAtMillis: Long,
    val completedAtMillis: Long?
)
