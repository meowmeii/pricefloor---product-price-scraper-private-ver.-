package com.example.domain.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class WatchlistItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val marketplace: String = "",
    val sourceUrl: String = "",
    val imageUrl: String? = null,
    val condition: String = ListingCondition.USED.name,
    val acquisitionType: String = AcquisitionType.PURCHASE.name,
    val currency: String = "EUR",
    val initialTotalPrice: Double = 0.0,
    val latestTotalPrice: Double = 0.0,
    val advertisedPrice: Double = 0.0,
    val shippingCost: Double? = null,
    val priceDropped: Boolean = false,
    @ServerTimestamp val lastCheckedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun parsedCondition(): ListingCondition = ListingCondition.fromRaw(condition)
    fun parsedAcquisitionType(): AcquisitionType = AcquisitionType.fromRaw(acquisitionType)

    fun toCreateMap(uid: String): Map<String, Any> {
        val raw = mutableMapOf<String, Any?>(
            "userId" to uid,
            "title" to title.take(240).ifBlank { "Tracked Listing" },
            "marketplace" to marketplace.take(60).ifBlank { "Marketplace" },
            "sourceUrl" to sourceUrl.take(1000).ifBlank { "https://example.com" },
            "imageUrl" to imageUrl?.takeIf { it.isNotBlank() }?.take(1000),
            "condition" to parsedCondition().name,
            "acquisitionType" to parsedAcquisitionType().name,
            "currency" to currency.uppercase().take(3).ifBlank { "EUR" },
            "initialTotalPrice" to initialTotalPrice.coerceAtLeast(0.0),
            "latestTotalPrice" to latestTotalPrice.coerceAtLeast(0.0),
            "advertisedPrice" to advertisedPrice.coerceAtLeast(0.0),
            "shippingCost" to shippingCost?.takeIf { it >= 0.0 },
            "priceDropped" to priceDropped,
            "lastCheckedAt" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class SavedSearch(
    @DocumentId val id: String = "",
    val userId: String = "",
    val query: String = "",
    val countryCode: String = "IE",
    val postcode: String? = null,
    val currency: String = "EUR",
    val targetMaxPrice: Double? = null,
    val includeRentals: Boolean = true,
    val includeAuctions: Boolean = true,
    val alertEnabled: Boolean = true,
    val lastLowestTotal: Double? = null,
    val lastVerifiedCount: Long = 0L,
    @ServerTimestamp val lastRunAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> {
        val raw = mutableMapOf<String, Any?>(
            "userId" to uid,
            "query" to query.trim().take(240).ifBlank { "Search" },
            "countryCode" to countryCode.uppercase().take(5).ifBlank { "IE" },
            "postcode" to postcode?.trim()?.takeIf { it.isNotBlank() }?.take(20),
            "currency" to currency.uppercase().take(3).ifBlank { "EUR" },
            "targetMaxPrice" to targetMaxPrice?.takeIf { it >= 0.0 },
            "includeRentals" to includeRentals,
            "includeAuctions" to includeAuctions,
            "alertEnabled" to alertEnabled,
            "lastLowestTotal" to lastLowestTotal?.takeIf { it >= 0.0 },
            "lastVerifiedCount" to lastVerifiedCount.coerceAtLeast(0L),
            "lastRunAt" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class PriceHistoryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val normalizedQuery: String = "",
    val productTitle: String = "",
    val marketplace: String = "",
    val condition: String = ListingCondition.USED.name,
    val currency: String = "EUR",
    val lowestVerifiedTotal: Double = 0.0,
    val medianPrice: Double = 0.0,
    val sourceUrl: String = "",
    @ServerTimestamp val recordedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> {
        return mapOf(
            "userId" to uid,
            "normalizedQuery" to normalizedQuery.trim().lowercase().take(200).ifBlank { "product" },
            "productTitle" to productTitle.trim().take(240).ifBlank { "Product" },
            "marketplace" to marketplace.trim().take(60).ifBlank { "Marketplace" },
            "condition" to ListingCondition.fromRaw(condition).name,
            "currency" to currency.uppercase().take(3).ifBlank { "EUR" },
            "lowestVerifiedTotal" to lowestVerifiedTotal.coerceAtLeast(0.0),
            "medianPrice" to medianPrice.coerceAtLeast(0.0),
            "sourceUrl" to sourceUrl.trim().take(1000).ifBlank { "https://example.com" },
            "recordedAt" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp()
        )
    }
}
