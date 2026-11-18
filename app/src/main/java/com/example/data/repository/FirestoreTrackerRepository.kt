package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.firebase.OperationType
import com.example.core.firebase.handleFirestoreError
import com.example.domain.model.ListingCondition
import com.example.domain.model.PriceHistoryEntry
import com.example.domain.model.ProductListing
import com.example.domain.model.SavedSearch
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.WatchlistItem
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class FirestoreTrackerRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireVerifiedUid(expectedUserId: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user write." }
        return uid
    }

    fun observeWatchlist(userId: String): Flow<List<WatchlistItem>> =
        db.collection("users").document(userId).collection("watchlist")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    WatchlistItem::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    fun observeSavedSearches(userId: String): Flow<List<SavedSearch>> =
        db.collection("users").document(userId).collection("saved_searches")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    SavedSearch::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    fun observePriceHistory(userId: String): Flow<List<PriceHistoryEntry>> =
        db.collection("users").document(userId).collection("price_history")
            .orderBy("recordedAt", Query.Direction.DESCENDING)
            .limit(60)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    PriceHistoryEntry::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    suspend fun addToWatchlist(userId: String, listing: ProductListing): Result<Unit> {
        val docId = "w_${listing.marketplaceId}_${abs(listing.sourceUrl.hashCode())}"
        val path = "users/$userId/watchlist/$docId"
        return try {
            val uid = requireVerifiedUid(userId)
            val total = listing.costBreakdown.totalEstimatedCostConverted
                ?: listing.costBreakdown.convertedItemPrice
            val item = WatchlistItem(
                userId = uid,
                title = listing.title.take(240),
                marketplace = listing.marketplaceName.take(60),
                sourceUrl = listing.sourceUrl.take(1000),
                imageUrl = listing.imageUrl?.take(1000),
                condition = listing.condition.name,
                acquisitionType = listing.acquisitionType.name,
                currency = listing.costBreakdown.targetCurrency,
                initialTotalPrice = total,
                latestTotalPrice = total,
                advertisedPrice = listing.costBreakdown.convertedItemPrice,
                shippingCost = listing.costBreakdown.shippingConverted,
                priceDropped = false
            )
            db.collection("users").document(uid).collection("watchlist").document(docId)
                .set(item.toCreateMap(uid))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateWatchlistPrice(
        userId: String,
        item: WatchlistItem,
        newLatestTotal: Double,
        newAdvertisedPrice: Double,
        newShipping: Double?
    ): Result<Unit> {
        val path = "users/$userId/watchlist/${item.id}"
        return try {
            val uid = requireVerifiedUid(userId)
            require(item.id.isNotBlank()) { "Watchlist item ID cannot be blank" }
            val dropped = newLatestTotal < item.initialTotalPrice - 0.01
            val updates = mutableMapOf<String, Any>(
                "latestTotalPrice" to newLatestTotal.coerceAtLeast(0.0),
                "advertisedPrice" to newAdvertisedPrice.coerceAtLeast(0.0),
                "priceDropped" to dropped,
                "lastCheckedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (newShipping != null && newShipping >= 0.0) {
                updates["shippingCost"] = newShipping
            }
            db.collection("users").document(uid).collection("watchlist").document(item.id)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun removeWatchlistItem(userId: String, itemId: String): Result<Unit> {
        val path = "users/$userId/watchlist/$itemId"
        return try {
            val uid = requireVerifiedUid(userId)
            require(itemId.isNotBlank()) { "Item ID cannot be blank" }
            db.collection("users").document(uid).collection("watchlist").document(itemId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun saveSearch(
        userId: String,
        request: SearchQueryRequest,
        targetMaxPrice: Double?,
        alertEnabled: Boolean,
        lowestTotal: Double?,
        verifiedCount: Int
    ): Result<Unit> {
        val cleanKey = request.rawQuery.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").take(48)
        val docId = "s_${cleanKey}_${request.countryCode.lowercase()}"
        val path = "users/$userId/saved_searches/$docId"
        return try {
            val uid = requireVerifiedUid(userId)
            val model = SavedSearch(
                userId = uid,
                query = request.rawQuery.trim().take(240),
                countryCode = request.countryCode,
                postcode = request.postcode.takeIf { it.isNotBlank() },
                currency = request.targetCurrency,
                targetMaxPrice = targetMaxPrice,
                includeRentals = request.includeRentals,
                includeAuctions = request.includeAuctions,
                alertEnabled = alertEnabled,
                lastLowestTotal = lowestTotal,
                lastVerifiedCount = verifiedCount.toLong()
            )
            db.collection("users").document(uid).collection("saved_searches").document(docId)
                .set(model.toCreateMap(uid))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun toggleSavedSearchAlert(userId: String, searchId: String, enabled: Boolean): Result<Unit> {
        val path = "users/$userId/saved_searches/$searchId"
        return try {
            val uid = requireVerifiedUid(userId)
            db.collection("users").document(uid).collection("saved_searches").document(searchId)
                .update(
                    mapOf(
                        "alertEnabled" to enabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSavedSearchRunStats(
        userId: String,
        searchId: String,
        lowestTotal: Double?,
        verifiedCount: Int
    ): Result<Unit> {
        val path = "users/$userId/saved_searches/$searchId"
        return try {
            val uid = requireVerifiedUid(userId)
            val updates = mutableMapOf<String, Any>(
                "lastVerifiedCount" to verifiedCount.toLong().coerceAtLeast(0L),
                "lastRunAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (lowestTotal != null && lowestTotal >= 0.0) {
                updates["lastLowestTotal"] = lowestTotal
            }
            db.collection("users").document(uid).collection("saved_searches").document(searchId)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun removeSavedSearch(userId: String, searchId: String): Result<Unit> {
        val path = "users/$userId/saved_searches/$searchId"
        return try {
            val uid = requireVerifiedUid(userId)
            db.collection("users").document(uid).collection("saved_searches").document(searchId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun recordPriceHistory(
        userId: String,
        normalizedQuery: String,
        cheapestVerified: ProductListing,
        medianPrice: Double
    ): Result<Unit> {
        val docId = "h_${System.currentTimeMillis()}_${abs(normalizedQuery.hashCode())}"
        val path = "users/$userId/price_history/$docId"
        return try {
            val uid = requireVerifiedUid(userId)
            val lowest = cheapestVerified.costBreakdown.totalEstimatedCostConverted
                ?: cheapestVerified.costBreakdown.convertedItemPrice
            val entry = PriceHistoryEntry(
                userId = uid,
                normalizedQuery = normalizedQuery.trim().lowercase().take(200),
                productTitle = cheapestVerified.title.take(240),
                marketplace = cheapestVerified.marketplaceName.take(60),
                condition = ListingCondition.fromRaw(cheapestVerified.condition.name).name,
                currency = cheapestVerified.costBreakdown.targetCurrency,
                lowestVerifiedTotal = lowest,
                medianPrice = medianPrice,
                sourceUrl = cheapestVerified.sourceUrl.take(1000)
            )
            db.collection("users").document(uid).collection("price_history").document(docId)
                .set(entry.toCreateMap(uid))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun removePriceHistoryEntry(userId: String, entryId: String): Result<Unit> {
        val path = "users/$userId/price_history/$entryId"
        return try {
            val uid = requireVerifiedUid(userId)
            db.collection("users").document(uid).collection("price_history").document(entryId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}
