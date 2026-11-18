package com.example.data.network

import com.example.domain.model.AcquisitionType
import com.example.domain.model.CurrencyRatesSnapshot
import com.example.domain.model.ListingCondition
import com.example.domain.model.ParsedSearchIntent
import com.example.domain.model.ProductListing
import com.example.domain.model.RentalTerms
import com.example.domain.model.AuctionDetails
import com.example.domain.model.SearchQueryRequest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

class ProductMatchingEngine(private val currencyConverter: CurrencyConverter) {

    private val accessoryKeywords = setOf(
        "case", "cover", "screen protector", "tempered glass", "charger", "cable",
        "adapter", "strap", "band", "mount", "holder", "skin", "sticker", "decal",
        "empty box", "box only", "manual only", "replacement screw", "dummy", "replica", "display model",
        "knob", "pickguard", "wiring harness", "gig bag", "carrying bag", "remote control",
        "battery door", "stand", "bracket", "dust cover", "screw set", "ferrules",
        "accessory kit", "capacitor", "tremolo arm", "backplate", "switch tip",
        "feature-movie", "music-video", "(song)", "(album)"
    )

    private val capacityRegex = Regex("""\b(\d+\s?(?:GB|TB|MB|W|MM|CM|INCH|"|ML|KG))\b""", RegexOption.IGNORE_CASE)
    private val modelSuffixTokens = listOf("pro max", "pro", "plus", "ultra", "mini", "ti", "super", "xt", "xtx", "fe", "lite", "air", "se")

    fun normalizeAndRank(
        rawHits: List<RawMarketplaceHit>,
        intent: ParsedSearchIntent,
        request: SearchQueryRequest,
        rates: CurrencyRatesSnapshot
    ): List<ProductListing> {
        val now = System.currentTimeMillis()
        val queryLower = intent.canonicalProductName.lowercase()
        val queryTokens = tokenize(queryLower)
        val queryCapacities = capacityRegex.findAll(queryLower).map { it.groupValues[1].uppercase().replace(" ", "") }.toSet()

        // First pass: convert currencies, compute total costs, score match confidence, and deduplicate
        val preliminaries = rawHits.map { hit ->
            val condition = ListingCondition.fromRaw(hit.conditionRaw)
            val acquisitionType = AcquisitionType.fromRaw(hit.acquisitionTypeRaw)
            val costBreakdown = currencyConverter.calculateTotalCost(
                advertisedPrice = hit.price,
                originalCurrency = hit.currency,
                shippingOriginal = hit.shippingCost,
                sourceCountryCode = hit.sourceCountryCode,
                targetCountryCode = request.countryCode,
                targetCurrency = request.targetCurrency,
                acquisitionType = acquisitionType,
                marketplaceId = hit.marketplaceId,
                rates = rates
            )

            val rentalTerms = if (acquisitionType == AcquisitionType.RENTAL) {
                buildRentalTerms(hit, costBreakdown.fxRateApplied, request.rentalDurationDays)
            } else null

            val auctionDetails = if (acquisitionType == AcquisitionType.AUCTION) {
                AuctionDetails(
                    currentBidConverted = costBreakdown.convertedItemPrice,
                    bidCount = hit.auctionBidCount,
                    timeRemainingText = hit.auctionTimeRemaining,
                    buyItNowConverted = hit.auctionBuyItNow?.let { round2(it * costBreakdown.fxRateApplied) }
                )
            } else null

            val (confidence, reason, flags, variant) = evaluateMatch(
                title = hit.title,
                queryLower = queryLower,
                queryTokens = queryTokens,
                queryCapacities = queryCapacities,
                condition = condition
            )

            ProductListing(
                id = "${hit.marketplaceId}_${abs(hit.sourceUrl.hashCode())}_${UUID.randomUUID().toString().take(6)}",
                title = hit.title,
                marketplaceId = hit.marketplaceId,
                marketplaceName = hit.marketplaceName,
                sellerName = hit.sellerName,
                sellerReputation = hit.sellerReputation,
                condition = condition,
                acquisitionType = acquisitionType,
                originalPrice = hit.price,
                originalCurrency = hit.currency.uppercase(),
                shippingOriginal = hit.shippingCost,
                sourceCountryCode = hit.sourceCountryCode,
                locationText = hit.locationText,
                availabilityText = hit.availabilityText,
                listingDateText = hit.listingDateText,
                retrievedAtMillis = now,
                sourceUrl = hit.sourceUrl,
                imageUrl = hit.imageUrl,
                matchConfidence = confidence,
                matchReason = reason,
                variantSummary = variant,
                suspiciousFlags = flags,
                rentalTerms = rentalTerms,
                auctionDetails = auctionDetails,
                costBreakdown = costBreakdown
            )
        }

        // Deduplicate by normalized URL or (marketplace + normalized title + rounded price)
        val deduplicated = preliminaries.distinctBy { listing ->
            val cleanUrl = listing.sourceUrl.substringBefore("?").lowercase()
            val normTitle = listing.title.lowercase().replace(Regex("[^a-z0-9]"), "").take(40)
            "$cleanUrl|${listing.marketplaceId}|$normTitle|${listing.originalPrice.roundToInt()}"
        }

        // Anomaly detection: flag suspiciously low prices (<35% of median verified price for non-damaged, non-accessory items)
        val verifiedNonDamagedPrices = deduplicated
            .filter { it.costBreakdown.isTotalVerified && it.condition != ListingCondition.DAMAGED && it.matchConfidence >= 55 }
            .mapNotNull { it.costBreakdown.totalEstimatedCostConverted }
            .sorted()

        val medianPrice = if (verifiedNonDamagedPrices.size >= 3) {
            verifiedNonDamagedPrices[verifiedNonDamagedPrices.size / 2]
        } else null

        val flagged = deduplicated.map { listing ->
            val total = listing.costBreakdown.totalEstimatedCostConverted ?: listing.costBreakdown.convertedItemPrice
            val extraFlags = listing.suspiciousFlags.toMutableList()
            if (medianPrice != null && medianPrice > 25.0 &&
                listing.condition != ListingCondition.DAMAGED &&
                listing.acquisitionType == AcquisitionType.PURCHASE &&
                total < medianPrice * 0.35 &&
                listing.matchConfidence >= 50
            ) {
                extraFlags += "Suspiciously low price (<35% of median ${round2(medianPrice)} ${request.targetCurrency}) — verify seller & listing carefully"
            }
            if (extraFlags.size == listing.suspiciousFlags.size) listing else listing.copy(suspiciousFlags = extraFlags)
        }

        // Default Rank: Verified total cost first, cheapest to most expensive; then unverified-shipping by converted item price
        return flagged.sortedWith(
            compareBy<ProductListing>(
                { !it.costBreakdown.isTotalVerified },
                { it.matchConfidence < 45 },
                { it.costBreakdown.totalEstimatedCostConverted ?: Double.MAX_VALUE },
                { it.costBreakdown.convertedItemPrice }
            )
        )
    }

    private fun buildRentalTerms(hit: RawMarketplaceHit, fxRate: Double, requestedDays: Int): RentalTerms {
        val daily = hit.rentalDailyRate?.let { round2(it * fxRate) }
            ?: round2((hit.price * fxRate) / 30.0)
        val weekly = hit.rentalWeeklyRate?.let { round2(it * fxRate) } ?: round2(daily * 5.5)
        val monthly = hit.rentalMonthlyRate?.let { round2(it * fxRate) } ?: round2(hit.price * fxRate)
        val deposit = hit.rentalDeposit?.let { round2(it * fxRate) }
        val delivery = hit.rentalDeliveryFee?.let { round2(it * fxRate) } ?: 0.0
        val effectiveDays = maxOf(requestedDays, hit.rentalMinDays)
        val baseRental = when {
            effectiveDays >= 28 && hit.rentalMonthlyRate != null ->
                monthly * ceil(effectiveDays / 30.0)
            effectiveDays >= 7 && hit.rentalWeeklyRate != null ->
                weekly * ceil(effectiveDays / 7.0)
            else -> daily * effectiveDays
        }
        return RentalTerms(
            dailyRateConverted = daily,
            weeklyRateConverted = weekly,
            monthlyRateConverted = monthly,
            minimumDurationDays = hit.rentalMinDays,
            depositConverted = deposit,
            deliveryCollectionFeeConverted = delivery.takeIf { it > 0.0 },
            totalForRequestedDurationConverted = round2(baseRental + delivery),
            requestedDurationDays = requestedDays
        )
    }

    private data class MatchEval(
        val confidence: Int,
        val reason: String,
        val flags: List<String>,
        val variant: String?
    )

    private fun evaluateMatch(
        title: String,
        queryLower: String,
        queryTokens: Set<String>,
        queryCapacities: Set<String>,
        condition: ListingCondition
    ): MatchEval {
        val titleLower = title.lowercase()
        val titleTokens = tokenize(titleLower)
        val flags = mutableListOf<String>()

        val overlap = queryTokens.count { qt -> titleTokens.any { it == qt || it.contains(qt) } }
        var score = if (queryTokens.isEmpty()) 60 else ((overlap.toDouble() / queryTokens.size) * 75.0).roundToInt() + 20

        // Accessory / box-only trap detection
        val queryAsksForAccessory = accessoryKeywords.any { queryLower.contains(it) }
        val titleIsAccessory = accessoryKeywords.any { titleLower.contains(it) }
        if (!queryAsksForAccessory && titleIsAccessory) {
            score = minOf(score, 28)
            flags += "Possible accessory, case, or box-only listing (not main product)"
        }

        // Capacity / storage mismatch check
        val titleCapacities = capacityRegex.findAll(titleLower).map { it.groupValues[1].uppercase().replace(" ", "") }.toSet()
        if (queryCapacities.isNotEmpty() && titleCapacities.isNotEmpty() && queryCapacities.intersect(titleCapacities).isEmpty()) {
            score = minOf(score, 45)
            flags += "Different capacity/spec (${titleCapacities.joinToString()} vs requested ${queryCapacities.joinToString()})"
        }

        // Model tier mismatch check (e.g. "iPhone 15" vs "iPhone 15 Pro Max")
        for (suffix in modelSuffixTokens) {
            val inQuery = Regex("""\b$suffix\b""").containsMatchIn(queryLower)
            val inTitle = Regex("""\b$suffix\b""").containsMatchIn(titleLower)
            if (inQuery != inTitle) {
                score = (score - 15).coerceAtLeast(25)
                if (inQuery) {
                    flags += "May be missing '$suffix' tier specification"
                } else {
                    flags += "Different model variant ('$suffix')"
                }
                break
            }
        }

        if (condition == ListingCondition.DAMAGED && !queryLower.contains("parts") && !queryLower.contains("broken")) {
            flags += "Damaged / For Parts only — not a working unit"
        }

        val variantParts = mutableListOf<String>()
        if (titleCapacities.isNotEmpty()) variantParts += titleCapacities.joinToString("/")
        val variantSummary = variantParts.takeIf { it.isNotEmpty() }?.joinToString(" • ")

        val clamped = score.coerceIn(10, 99)
        val reason = when {
            clamped >= 80 -> "Strong title & spec match"
            clamped >= 55 -> "Partial keyword / variant match"
            else -> "Low match — check variant or accessory status"
        }
        return MatchEval(clamped, reason, flags, variantSummary)
    }

    private fun tokenize(text: String): Set<String> =
        text.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in setOf("the", "and", "for", "with", "new", "used", "cheapest", "find", "in", "of", "copy") }
            .toSet()

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
}
