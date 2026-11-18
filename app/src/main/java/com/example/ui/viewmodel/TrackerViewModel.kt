package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.FirestoreTrackerRepository
import com.example.data.repository.LiveSearchOrchestrator
import com.example.domain.model.PriceHistoryEntry
import com.example.domain.model.ProductListing
import com.example.domain.model.SavedSearch
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.WatchlistItem
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TRACKER_TAG = "TrackerVM"

sealed interface TrackerUiState<out T> {
    data object Loading : TrackerUiState<Nothing>
    data class Success<T>(val data: T) : TrackerUiState<T>
    data class Error(val message: String) : TrackerUiState<Nothing>
}

class TrackerViewModel(
    private val trackerRepo: FirestoreTrackerRepository,
    private val orchestrator: LiveSearchOrchestrator,
    private val currentUserId: String
) : ViewModel() {

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _refreshingItemId = MutableStateFlow<String?>(null)
    val refreshingItemId: StateFlow<String?> = _refreshingItemId.asStateFlow()

    val watchlistState: StateFlow<TrackerUiState<List<WatchlistItem>>> =
        trackerRepo.observeWatchlist(currentUserId)
            .map<List<WatchlistItem>, TrackerUiState<List<WatchlistItem>>> { TrackerUiState.Success(it) }
            .catch { error ->
                val fsErr = unwrapFirestoreError(error)
                if (fsErr == null && error is CancellationException) throw error
                val root = fsErr ?: error
                Log.w(TRACKER_TAG, "Error observing watchlist", root)
                emit(TrackerUiState.Error(root.message ?: "Failed to load watchlist"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = TrackerUiState.Loading
            )

    val savedSearchesState: StateFlow<TrackerUiState<List<SavedSearch>>> =
        trackerRepo.observeSavedSearches(currentUserId)
            .map<List<SavedSearch>, TrackerUiState<List<SavedSearch>>> { TrackerUiState.Success(it) }
            .catch { error ->
                val fsErr = unwrapFirestoreError(error)
                if (fsErr == null && error is CancellationException) throw error
                val root = fsErr ?: error
                Log.w(TRACKER_TAG, "Error observing saved searches", root)
                emit(TrackerUiState.Error(root.message ?: "Failed to load saved searches"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = TrackerUiState.Loading
            )

    val priceHistoryState: StateFlow<TrackerUiState<List<PriceHistoryEntry>>> =
        trackerRepo.observePriceHistory(currentUserId)
            .map<List<PriceHistoryEntry>, TrackerUiState<List<PriceHistoryEntry>>> { TrackerUiState.Success(it) }
            .catch { error ->
                val fsErr = unwrapFirestoreError(error)
                if (fsErr == null && error is CancellationException) throw error
                val root = fsErr ?: error
                Log.w(TRACKER_TAG, "Error observing price history", root)
                emit(TrackerUiState.Error(root.message ?: "Failed to load price history"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = TrackerUiState.Loading
            )

    fun addToWatchlist(listing: ProductListing) {
        viewModelScope.launch {
            trackerRepo.addToWatchlist(currentUserId, listing)
                .onSuccess { _actionMessage.value = "Added \"${listing.title.take(36)}\" to Watchlist." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to save to watchlist." }
        }
    }

    fun removeFromWatchlist(itemId: String) {
        viewModelScope.launch {
            trackerRepo.removeWatchlistItem(currentUserId, itemId)
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to remove item." }
        }
    }

    fun refreshWatchlistItemLive(item: WatchlistItem, countryCode: String) {
        viewModelScope.launch {
            _refreshingItemId.value = item.id
            try {
                val req = SearchQueryRequest(
                    rawQuery = item.title,
                    countryCode = countryCode,
                    targetCurrency = item.currency
                )
                val finalSnap = orchestrator.executeLiveSearch(req)
                    .firstOrNull { !it.isSearching }
                val bestMatch = finalSnap?.listings
                    ?.filter { it.costBreakdown.isTotalVerified && it.matchConfidence >= 45 }
                    ?.minByOrNull { it.costBreakdown.totalEstimatedCostConverted ?: Double.MAX_VALUE }
                if (bestMatch != null) {
                    val newTotal = bestMatch.costBreakdown.totalEstimatedCostConverted
                        ?: bestMatch.costBreakdown.convertedItemPrice
                    trackerRepo.updateWatchlistPrice(
                        userId = currentUserId,
                        item = item,
                        newLatestTotal = newTotal,
                        newAdvertisedPrice = bestMatch.costBreakdown.convertedItemPrice,
                        newShipping = bestMatch.costBreakdown.shippingConverted
                    ).onSuccess {
                        val delta = newTotal - item.initialTotalPrice
                        _actionMessage.value = when {
                            delta < -0.5 -> "Price drop detected! Now $newTotal ${item.currency}"
                            else -> "Live check complete: $newTotal ${item.currency}"
                        }
                    }
                } else {
                    _actionMessage.value = "Live check found no verified match to update price."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionMessage.value = e.localizedMessage ?: "Live refresh failed."
            } finally {
                _refreshingItemId.value = null
            }
        }
    }

    fun saveSearchWithAlert(
        request: SearchQueryRequest,
        targetMaxPrice: Double?,
        alertEnabled: Boolean,
        lowestTotal: Double?,
        verifiedCount: Int
    ) {
        viewModelScope.launch {
            trackerRepo.saveSearch(
                userId = currentUserId,
                request = request,
                targetMaxPrice = targetMaxPrice,
                alertEnabled = alertEnabled,
                lowestTotal = lowestTotal,
                verifiedCount = verifiedCount
            )
                .onSuccess { _actionMessage.value = "Saved search \"${request.rawQuery}\"." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to save search." }
        }
    }

    fun toggleSavedSearchAlert(searchId: String, enabled: Boolean) {
        viewModelScope.launch {
            trackerRepo.toggleSavedSearchAlert(currentUserId, searchId, enabled)
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to update alert." }
        }
    }

    fun removeSavedSearch(searchId: String) {
        viewModelScope.launch {
            trackerRepo.removeSavedSearch(currentUserId, searchId)
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to delete search." }
        }
    }

    fun removePriceHistory(entryId: String) {
        viewModelScope.launch {
            trackerRepo.removePriceHistoryEntry(currentUserId, entryId)
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
}
