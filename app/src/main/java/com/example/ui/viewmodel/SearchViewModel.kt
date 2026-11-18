package com.example.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiSearchAssistant
import com.example.data.ai.ImageProductIdentification
import com.example.data.repository.FirestoreTrackerRepository
import com.example.data.repository.LiveSearchOrchestrator
import com.example.data.repository.RegionPreferences
import com.example.data.repository.UserPreferencesRepository
import com.example.domain.model.AcquisitionType
import com.example.domain.model.ListingCondition
import com.example.domain.model.LiveSearchSnapshot
import com.example.domain.model.ProductListing
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.SortOption
import com.example.domain.model.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RentVsBuyComparison(
    val cheapestRental: ProductListing,
    val cheapestPurchase: ProductListing,
    val rentalDurationDays: Int,
    val rentalTotal: Double,
    val purchaseTotal: Double,
    val breakEvenPeriodsDescription: String,
    val recommendationText: String
)

data class SearchFilterState(
    val queryInput: String = "",
    val selectedConditions: Set<ListingCondition> = emptySet(),
    val includeRentals: Boolean = true,
    val includeAuctions: Boolean = true,
    val verifiedTotalOnly: Boolean = false,
    val selectedMarketplaceId: String? = null,
    val maxPriceFilter: Double? = null,
    val sortOption: SortOption = SortOption.CHEAPEST_TOTAL
)

sealed interface ImageIdUiState {
    data object Idle : ImageIdUiState
    data object Analyzing : ImageIdUiState
    data class Identified(val info: ImageProductIdentification) : ImageIdUiState
    data class Error(val message: String) : ImageIdUiState
}

data class SearchUiState(
    val filters: SearchFilterState = SearchFilterState(),
    val region: RegionPreferences = RegionPreferences(),
    val snapshot: LiveSearchSnapshot? = null,
    val filteredListings: List<ProductListing> = emptyList(),
    val cheapestVerifiedOverall: ProductListing? = null,
    val cheapestByCondition: Map<ListingCondition, ProductListing> = emptyMap(),
    val rentVsBuy: RentVsBuyComparison? = null,
    val compareIds: Set<String> = emptySet(),
    val imageIdState: ImageIdUiState = ImageIdUiState.Idle,
    val statusBanner: String? = null
)

class SearchViewModel(
    private val orchestrator: LiveSearchOrchestrator,
    private val geminiAssistant: GeminiSearchAssistant,
    private val prefsRepo: UserPreferencesRepository,
    private val trackerRepo: FirestoreTrackerRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _filters = MutableStateFlow(SearchFilterState())
    private val _snapshot = MutableStateFlow<LiveSearchSnapshot?>(null)
    private val _compareIds = MutableStateFlow<Set<String>>(emptySet())
    private val _imageIdState = MutableStateFlow<ImageIdUiState>(ImageIdUiState.Idle)
    private val _statusBanner = MutableStateFlow<String?>(null)

    private var activeSearchJob: Job? = null

    val regionPrefs: StateFlow<RegionPreferences> = prefsRepo.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = RegionPreferences()
        )

    val uiState: StateFlow<SearchUiState> = combine(
        _filters,
        regionPrefs,
        _snapshot,
        _compareIds,
        _imageIdState,
        _statusBanner
    ) { values ->
        val filters = values[0] as SearchFilterState
        val region = values[1] as RegionPreferences
        val snapshot = values[2] as LiveSearchSnapshot?
        @Suppress("UNCHECKED_CAST")
        val compareIds = values[3] as Set<String>
        val imageId = values[4] as ImageIdUiState
        val banner = values[5] as String?

        val allListings = snapshot?.listings.orEmpty()
        val filtered = applyFiltersAndSort(allListings, filters)

        val cheapestVerified = filtered.firstOrNull {
            it.costBreakdown.isTotalVerified &&
                it.acquisitionType == AcquisitionType.PURCHASE &&
                it.matchConfidence >= 45 &&
                it.condition != ListingCondition.DAMAGED
        } ?: filtered.firstOrNull { it.costBreakdown.isTotalVerified && it.matchConfidence >= 45 }

        val byCondition = linkedMapOf<ListingCondition, ProductListing>()
        allListings
            .filter {
                (filters.includeRentals || it.acquisitionType != AcquisitionType.RENTAL) &&
                    (filters.includeAuctions || it.acquisitionType != AcquisitionType.AUCTION) &&
                    it.matchConfidence >= 40
            }
            .groupBy { it.condition }
            .forEach { (cond, group) ->
                val best = group.sortedWith(
                    compareBy<ProductListing>(
                        { !it.costBreakdown.isTotalVerified },
                        { it.costBreakdown.totalEstimatedCostConverted ?: Double.MAX_VALUE },
                        { it.costBreakdown.convertedItemPrice }
                    )
                ).firstOrNull()
                if (best != null) byCondition[cond] = best
            }

        val rentVsBuy = computeRentVsBuy(allListings, snapshot?.request?.rentalDurationDays ?: region.rentalDurationDays)

        SearchUiState(
            filters = filters,
            region = region,
            snapshot = snapshot,
            filteredListings = filtered,
            cheapestVerifiedOverall = cheapestVerified,
            cheapestByCondition = byCondition,
            rentVsBuy = rentVsBuy,
            compareIds = compareIds,
            imageIdState = imageId,
            statusBanner = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = SearchUiState()
    )

    fun updateQueryInput(text: String) {
        _filters.update { it.copy(queryInput = text) }
    }

    fun toggleConditionFilter(condition: ListingCondition) {
        _filters.update { st ->
            val next = st.selectedConditions.toMutableSet()
            if (!next.add(condition)) next.remove(condition)
            st.copy(selectedConditions = next)
        }
    }

    fun clearConditionFilters() {
        _filters.update { it.copy(selectedConditions = emptySet()) }
    }

    fun setIncludeRentals(include: Boolean) {
        _filters.update { it.copy(includeRentals = include) }
    }

    fun setIncludeAuctions(include: Boolean) {
        _filters.update { it.copy(includeAuctions = include) }
    }

    fun setVerifiedTotalOnly(only: Boolean) {
        _filters.update { it.copy(verifiedTotalOnly = only) }
    }

    fun setMarketplaceFilter(marketplaceId: String?) {
        _filters.update { it.copy(selectedMarketplaceId = marketplaceId) }
    }

    fun setMaxPriceFilter(maxPrice: Double?) {
        _filters.update { it.copy(maxPriceFilter = maxPrice) }
    }

    fun setSortOption(option: SortOption) {
        _filters.update { it.copy(sortOption = option) }
    }

    fun toggleCompareListing(listingId: String) {
        _compareIds.update { current ->
            val next = current.toMutableSet()
            if (!next.remove(listingId)) {
                if (next.size >= 3) {
                    _statusBanner.value = "Compare supports up to 3 listings at once."
                } else {
                    next.add(listingId)
                }
            }
            next
        }
    }

    fun clearCompare() {
        _compareIds.value = emptySet()
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun dismissImageIdentification() {
        _imageIdState.value = ImageIdUiState.Idle
    }

    fun updateRegionSettings(countryCode: String, postcode: String, currency: String, rentalDays: Int) {
        viewModelScope.launch {
            prefsRepo.updateRegion(countryCode, postcode, currency, rentalDays)
            val currentQuery = _filters.value.queryInput.trim()
            if (currentQuery.isNotBlank() && _snapshot.value != null) {
                runLiveSearch(currentQuery, countryCode, postcode, currency, rentalDays)
            }
        }
    }

    fun runLiveSearch(
        explicitQuery: String? = null,
        overrideCountry: String? = null,
        overridePostcode: String? = null,
        overrideCurrency: String? = null,
        overrideRentalDays: Int? = null
    ) {
        val raw = (explicitQuery ?: _filters.value.queryInput).trim()
        if (raw.isBlank()) {
            _statusBanner.value = "Enter any product, model, ISBN, card, or natural-language request."
            return
        }
        if (explicitQuery != null) {
            _filters.update { it.copy(queryInput = explicitQuery) }
        }
        _compareIds.value = emptySet()

        val region = regionPrefs.value
        val req = SearchQueryRequest(
            rawQuery = raw,
            countryCode = overrideCountry ?: region.countryCode,
            postcode = overridePostcode ?: region.postcode,
            targetCurrency = overrideCurrency ?: region.currency,
            selectedConditions = _filters.value.selectedConditions,
            includeRentals = _filters.value.includeRentals,
            includeAuctions = _filters.value.includeAuctions,
            rentalDurationDays = overrideRentalDays ?: region.rentalDurationDays,
            verifiedTotalOnly = _filters.value.verifiedTotalOnly,
            maxTotalPrice = _filters.value.maxPriceFilter
        )

        activeSearchJob?.cancel()
        var historyRecorded = false
        activeSearchJob = viewModelScope.launch {
            try {
                orchestrator.executeLiveSearch(req).collect { snap ->
                    _snapshot.value = snap
                    if (!snap.isSearching && !historyRecorded) {
                        historyRecorded = true
                        recordSearchHistory(snap)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage ?: "Live search encountered an error."
            }
        }
    }

    fun identifyProductFromImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _imageIdState.value = ImageIdUiState.Analyzing
            val result = geminiAssistant.identifyProductFromImage(bitmap)
            result.onSuccess { info ->
                _imageIdState.value = ImageIdUiState.Identified(info)
                val query = info.suggestedSearchQuery.ifBlank { info.identifiedName }
                if (query.isNotBlank()) {
                    runLiveSearch(query)
                }
            }.onFailure { err ->
                _imageIdState.value = ImageIdUiState.Error(
                    err.localizedMessage ?: "Could not identify product from image."
                )
            }
        }
    }

    private suspend fun recordSearchHistory(snap: LiveSearchSnapshot) {
        val verified = snap.listings.filter {
            it.costBreakdown.isTotalVerified && it.matchConfidence >= 45 && it.condition != ListingCondition.DAMAGED
        }
        val cheapest = verified.firstOrNull() ?: return
        val totals = verified.mapNotNull { it.costBreakdown.totalEstimatedCostConverted }.sorted()
        val median = if (totals.isNotEmpty()) totals[totals.size / 2] else {
            cheapest.costBreakdown.totalEstimatedCostConverted ?: cheapest.costBreakdown.convertedItemPrice
        }
        val searchedSources = snap.telemetry.count { it.status == SourceStatus.SEARCHED || it.status == SourceStatus.NO_RESULTS }
        if (searchedSources > 0) {
            trackerRepo.recordPriceHistory(
                userId = currentUserId,
                normalizedQuery = snap.intent.canonicalProductName.ifBlank { snap.request.rawQuery },
                cheapestVerified = cheapest,
                medianPrice = median
            )
        }
    }

    private fun applyFiltersAndSort(
        listings: List<ProductListing>,
        filters: SearchFilterState
    ): List<ProductListing> {
        val filtered = listings.filter { item ->
            if (!filters.includeRentals && item.acquisitionType == AcquisitionType.RENTAL) return@filter false
            if (!filters.includeAuctions && item.acquisitionType == AcquisitionType.AUCTION) return@filter false
            if (filters.selectedConditions.isNotEmpty() && item.condition !in filters.selectedConditions) return@filter false
            if (filters.verifiedTotalOnly && !item.costBreakdown.isTotalVerified) return@filter false
            if (filters.selectedMarketplaceId != null && item.marketplaceId != filters.selectedMarketplaceId) return@filter false
            val maxP = filters.maxPriceFilter
            if (maxP != null) {
                val effectivePrice = item.costBreakdown.totalEstimatedCostConverted ?: item.costBreakdown.convertedItemPrice
                if (effectivePrice > maxP) return@filter false
            }
            true
        }

        return when (filters.sortOption) {
            SortOption.CHEAPEST_TOTAL -> filtered.sortedWith(
                compareBy<ProductListing>(
                    { !it.costBreakdown.isTotalVerified },
                    { it.matchConfidence < 45 },
                    { it.costBreakdown.totalEstimatedCostConverted ?: Double.MAX_VALUE },
                    { it.costBreakdown.convertedItemPrice }
                )
            )
            SortOption.CHEAPEST_ADVERTISED -> filtered.sortedBy { it.costBreakdown.convertedItemPrice }
            SortOption.NEWEST -> filtered.sortedByDescending { it.listingDateText ?: "" }
            SortOption.BEST_CONDITION -> filtered.sortedByDescending { it.condition.qualityRank }
            SortOption.CLOSEST_LOCATION -> {
                val targetCc = _snapshot.value?.request?.countryCode ?: "IE"
                filtered.sortedWith(
                    compareBy<ProductListing>(
                        { !it.sourceCountryCode.equals(targetCc, ignoreCase = true) },
                        { !it.costBreakdown.isTotalVerified },
                        { it.costBreakdown.totalEstimatedCostConverted ?: it.costBreakdown.convertedItemPrice }
                    )
                )
            }
            SortOption.HIGHEST_MATCH -> filtered.sortedWith(
                compareByDescending<ProductListing> { it.matchConfidence }
                    .thenBy { it.costBreakdown.totalEstimatedCostConverted ?: it.costBreakdown.convertedItemPrice }
            )
        }
    }

    private fun computeRentVsBuy(listings: List<ProductListing>, durationDays: Int): RentVsBuyComparison? {
        val rental = listings
            .filter { it.acquisitionType == AcquisitionType.RENTAL && it.rentalTerms != null }
            .minByOrNull { it.rentalTerms!!.totalForRequestedDurationConverted } ?: return null
        val purchase = listings
            .filter { it.acquisitionType == AcquisitionType.PURCHASE && it.condition != ListingCondition.DAMAGED && it.matchConfidence >= 45 }
            .minByOrNull { it.costBreakdown.totalEstimatedCostConverted ?: it.costBreakdown.convertedItemPrice } ?: return null

        val rentTotal = rental.rentalTerms!!.totalForRequestedDurationConverted
        val buyTotal = purchase.costBreakdown.totalEstimatedCostConverted ?: purchase.costBreakdown.convertedItemPrice
        val daily = rental.rentalTerms.dailyRateConverted ?: (rentTotal / durationDays.coerceAtLeast(1))
        val breakEvenDays = if (daily > 0.0) (buyTotal / daily).toInt().coerceAtLeast(1) else durationDays

        val rec = if (rentTotal < buyTotal * 0.65) {
            "Renting for $durationDays days saves ${(buyTotal - rentTotal).toInt()} vs buying outright; buying breaks even around $breakEvenDays days."
        } else {
            "Buying outright (${purchase.condition.displayLabel}) is more cost-effective beyond ~$breakEvenDays days of use."
        }
        return RentVsBuyComparison(
            cheapestRental = rental,
            cheapestPurchase = purchase,
            rentalDurationDays = durationDays,
            rentalTotal = rentTotal,
            purchaseTotal = buyTotal,
            breakEvenPeriodsDescription = "~$breakEvenDays days",
            recommendationText = rec
        )
    }
}
