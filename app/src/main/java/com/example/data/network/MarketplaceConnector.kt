package com.example.data.network

import com.example.domain.model.ParsedSearchIntent
import com.example.domain.model.ProductListing
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.SourceDescriptor
import com.example.domain.model.SourceStatus
import com.example.domain.model.SourceTelemetry

data class RawMarketplaceHit(
    val title: String,
    val marketplaceId: String,
    val marketplaceName: String,
    val sellerName: String,
    val sellerReputation: String? = null,
    val conditionRaw: String,
    val acquisitionTypeRaw: String = "PURCHASE",
    val price: Double,
    val currency: String,
    val shippingCost: Double? = null, // null explicitly means unknown
    val sourceCountryCode: String,
    val locationText: String,
    val availabilityText: String = "In Stock",
    val listingDateText: String? = null,
    val sourceUrl: String,
    val imageUrl: String? = null,
    val rentalDailyRate: Double? = null,
    val rentalWeeklyRate: Double? = null,
    val rentalMonthlyRate: Double? = null,
    val rentalMinDays: Int = 1,
    val rentalDeposit: Double? = null,
    val rentalDeliveryFee: Double? = null,
    val auctionCurrentBid: Double? = null,
    val auctionBidCount: Int? = null,
    val auctionTimeRemaining: String? = null,
    val auctionBuyItNow: Double? = null
)

data class ConnectorOutcome(
    val descriptor: SourceDescriptor,
    val status: SourceStatus,
    val hits: List<RawMarketplaceHit> = emptyList(),
    val latencyMs: Long = 0L,
    val message: String = "",
    val requiredSecretNames: List<String> = emptyList()
) {
    fun toTelemetry(): SourceTelemetry = SourceTelemetry(
        descriptor = descriptor,
        status = status,
        resultCount = hits.size,
        latencyMs = latencyMs,
        message = message,
        requiredSecretNames = requiredSecretNames
    )
}

interface MarketplaceConnector {
    val descriptor: SourceDescriptor
    suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome
}
