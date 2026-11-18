package com.example.ui.screens

import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.repository.CsvExporter
import com.example.domain.model.ListingCondition
import com.example.domain.model.ProductListing
import com.example.domain.model.SortOption
import com.example.domain.model.SourceStatus
import com.example.ui.components.CompareListingsSheet
import com.example.ui.components.ListingCard
import com.example.ui.components.ListingDetailSheet
import com.example.ui.components.RegionCurrencySheet
import com.example.ui.components.SaveSearchAlertDialog
import com.example.ui.components.SourcesTelemetrySheet
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.VioletRental
import com.example.ui.util.Formatters
import com.example.ui.viewmodel.ImageIdUiState
import com.example.ui.viewmodel.SearchUiState
import com.example.ui.viewmodel.SearchViewModel
import com.example.ui.viewmodel.TrackerViewModel

@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel,
    trackerViewModel: TrackerViewModel,
    onShowMessage: (String) -> Unit
) {
    val state by searchViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var showRegionSheet by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedListing by remember { mutableStateOf<ProductListing?>(null) }
    var showCompareSheet by remember { mutableStateOf(false) }
    var showSaveSearchDialog by remember { mutableStateOf(false) }
    var maxBudgetText by remember { mutableStateOf("") }

    // Zero-permission Android Photo Picker for image-based product identification
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                searchViewModel.identifyProductFromImage(bitmap)
            } catch (e: Exception) {
                onShowMessage("Could not load selected image: ${e.localizedMessage}")
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header & Region/Currency Selector
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PriceFloor",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Universal Live Price Discovery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showRegionSheet = true }
                            .testTag("region_currency_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Delivery location",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${state.region.countryCode} • ${state.region.currency}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // 2. Universal Search Bar + Photo Picker + Live Search Trigger
            item {
                OutlinedTextField(
                    value = state.filters.queryInput,
                    onValueChange = searchViewModel::updateQueryInput,
                    placeholder = {
                        Text(
                            "Search anything or ask naturally (e.g. \"cheapest used RTX 4070 in Ireland\")",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("photo_identify_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = "Identify product from photo",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                            IconButton(
                                onClick = {
                                    keyboard?.hide()
                                    searchViewModel.runLiveSearch()
                                },
                                modifier = Modifier.testTag("search_submit_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Radar,
                                    contentDescription = "Run live search",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            searchViewModel.runLiveSearch()
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("universal_search_input")
                )
            }

            // 3. Gemini Vision Identification Banner
            item {
                when (val imgState = state.imageIdState) {
                    is ImageIdUiState.Analyzing -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Gemini Vision is identifying the product...", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    is ImageIdUiState.Identified -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Identified: ${imgState.info.identifiedName}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${imgState.info.brandAndModel} • ${imgState.info.detectedDetails}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = searchViewModel::dismissImageIdentification) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                    is ImageIdUiState.Error -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = imgState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = searchViewModel::dismissImageIdentification) {
                                    Text("OK")
                                }
                            }
                        }
                    }
                    ImageIdUiState.Idle -> Unit
                }
            }

            // 4. Condition & Acquisition Filter Strip + Advanced Toggle
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.filters.includeRentals,
                        onClick = { searchViewModel.setIncludeRentals(!state.filters.includeRentals) },
                        label = { Text("Rentals") },
                        modifier = Modifier.testTag("toggle_rentals_chip")
                    )
                    FilterChip(
                        selected = state.filters.includeAuctions,
                        onClick = { searchViewModel.setIncludeAuctions(!state.filters.includeAuctions) },
                        label = { Text("Auctions") },
                        modifier = Modifier.testTag("toggle_auctions_chip")
                    )
                    FilterChip(
                        selected = state.filters.verifiedTotalOnly,
                        onClick = { searchViewModel.setVerifiedTotalOnly(!state.filters.verifiedTotalOnly) },
                        label = { Text("Verified Total Only") },
                        modifier = Modifier.testTag("toggle_verified_chip")
                    )
                    FilterChip(
                        selected = showAdvancedFilters,
                        onClick = { showAdvancedFilters = !showAdvancedFilters },
                        label = { Text("Conditions & Filters") },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            Icon(
                                if (showAdvancedFilters) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("advanced_filters_chip")
                    )
                }

                AnimatedVisibility(visible = showAdvancedFilters) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Condition Filters (Compare simultaneously)", style = MaterialTheme.typography.labelLarge)
                                if (state.filters.selectedConditions.isNotEmpty()) {
                                    TextButton(onClick = searchViewModel::clearConditionFilters) {
                                        Text("All")
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ListingCondition.entries.forEach { cond ->
                                    FilterChip(
                                        selected = cond in state.filters.selectedConditions,
                                        onClick = { searchViewModel.toggleConditionFilter(cond) },
                                        label = { Text(cond.displayLabel) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = maxBudgetText,
                                onValueChange = {
                                    maxBudgetText = it
                                    searchViewModel.setMaxPriceFilter(it.toDoubleOrNull())
                                },
                                label = { Text("Max Total Budget (${state.region.currency})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 5. Empty State with Quick Natural-Language Examples OR Active Live Search View
            val snap = state.snapshot
            if (snap == null) {
                item {
                    EmptyDiscoveryCard(
                        onTryExample = { example ->
                            searchViewModel.runLiveSearch(example)
                        }
                    )
                }
            } else {
                // Live Search Telemetry Strip
                item {
                    val totalSources = snap.telemetry.size
                    val finishedSources = snap.telemetry.count { it.status != SourceStatus.SEARCHING }
                    val hitSources = snap.telemetry.count { it.status == SourceStatus.SEARCHED }
                    val unavailableSources = snap.telemetry.count { it.status == SourceStatus.UNAVAILABLE }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showSourcesSheet = true }
                            .testTag("live_telemetry_banner")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = CyanAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (snap.isSearching) {
                                            "Live scan: $finishedSources/$totalSources sources..."
                                        } else {
                                            "$hitSources returned hits • $totalSources sources checked"
                                        },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Text(
                                    text = "View Sources",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (snap.isSearching) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { (finishedSources.toFloat() / totalSources.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            val fxText = snap.currencySnapshot?.let {
                                if (it.isLiveRate) "Live FX (${it.providerName})" else it.providerName
                            } ?: "Fetching live FX..."
                            val aiSummary = snap.intent.aiNote ?: "Query: \"${snap.intent.canonicalProductName}\""
                            Text(
                                text = "$aiSummary\n$fxText • $unavailableSources unavailable (tap for full breakdown)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Action Toolbar: Sort + Save Search Alert + Export CSV
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            AssistChip(
                                onClick = { showSortMenu = true },
                                label = { Text(state.filters.sortOption.label) },
                                leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.testTag("sort_dropdown_chip")
                            )
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            searchViewModel.setSortOption(option)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { showSaveSearchDialog = true },
                                modifier = Modifier.testTag("save_search_alert_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkAdd,
                                    contentDescription = "Save search and alert"
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (state.filteredListings.isEmpty()) {
                                        onShowMessage("No listings to export yet.")
                                    } else {
                                        CsvExporter.exportAndShare(context, snap.request.rawQuery, state.filteredListings)
                                            .onFailure { onShowMessage(it.localizedMessage ?: "Export failed") }
                                    }
                                },
                                modifier = Modifier.testTag("export_csv_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Export results to CSV"
                                )
                            }
                        }
                    }
                }

                // Cheapest by Condition Simultaneous Comparison Strip
                if (state.cheapestByCondition.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Cheapest by Condition",
                                style = MaterialTheme.typography.titleSmall
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.cheapestByCondition.entries.toList(), key = { it.key.name }) { (cond, item) ->
                                    val c = item.costBreakdown
                                    val total = c.totalEstimatedCostConverted ?: c.convertedItemPrice
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(
                                                width = if (cond in state.filters.selectedConditions) 1.5.dp else 1.dp,
                                                color = if (cond in state.filters.selectedConditions) EmeraldPrimary else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .clickable { searchViewModel.toggleConditionFilter(cond) }
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                            Text(cond.displayLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = Formatters.formatMoney(total, c.targetCurrency),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (c.isTotalVerified) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (c.isTotalVerified) item.marketplaceName else "${item.marketplaceName} (+ship?)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Rent vs Buy Breakeven Card (when rental results exist)
                state.rentVsBuy?.let { rvb ->
                    item {
                        val curr = state.region.currency
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = VioletRental.copy(alpha = 0.12f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, VioletRental.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Rent vs Buy Analysis (${rvb.rentalDurationDays}-Day Term)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = VioletRental
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Rent (${rvb.cheapestRental.marketplaceName}): ${Formatters.formatMoney(rvb.rentalTotal, curr)}",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        "Buy (${rvb.cheapestPurchase.condition.displayLabel}): ${Formatters.formatMoney(rvb.purchaseTotal, curr)}",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                Text(
                                    text = rvb.recommendationText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Results List or 0-Results Explanation
                if (state.filteredListings.isEmpty() && !snap.isSearching) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No verified matching listings found across accessible sources.", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap 'View Sources' above to inspect which platforms returned 0 hits, were blocked by WAF/rate limits, or require API keys in the Secrets panel.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(state.filteredListings, key = { it.id }) { listing ->
                        ListingCard(
                            listing = listing,
                            isBestOverall = listing.id == state.cheapestVerifiedOverall?.id,
                            isSelectedForCompare = listing.id in state.compareIds,
                            onCardClick = { selectedListing = listing },
                            onToggleCompare = { searchViewModel.toggleCompareListing(listing.id) },
                            onSaveToWatchlist = { trackerViewModel.addToWatchlist(listing) }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }

        // Floating Compare Action Bar when 2+ items selected
        if (state.compareIds.size >= 2) {
            Surface(
                shape = RoundedCornerShape(100),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(100))
                    .clickable { showCompareSheet = true }
                    .testTag("open_compare_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Compare ${state.compareIds.size} Selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    if (showRegionSheet) {
        RegionCurrencySheet(
            current = state.region,
            onDismiss = { showRegionSheet = false },
            onApply = searchViewModel::updateRegionSettings
        )
    }

    if (showSourcesSheet && state.snapshot != null) {
        SourcesTelemetrySheet(
            telemetry = state.snapshot!!.telemetry,
            onDismiss = { showSourcesSheet = false }
        )
    }

    selectedListing?.let { listing ->
        ListingDetailSheet(
            listing = listing,
            onDismiss = { selectedListing = null },
            onSaveToWatchlist = trackerViewModel::addToWatchlist
        )
    }

    if (showCompareSheet) {
        val selectedItems = state.filteredListings.filter { it.id in state.compareIds }
        CompareListingsSheet(
            listings = selectedItems,
            onDismiss = { showCompareSheet = false },
            onClearCompare = searchViewModel::clearCompare
        )
    }

    if (showSaveSearchDialog && state.snapshot != null) {
        val snap = state.snapshot!!
        val lowest = state.cheapestVerifiedOverall?.costBreakdown?.totalEstimatedCostConverted
        val verifiedCount = state.filteredListings.count { it.costBreakdown.isTotalVerified }
        SaveSearchAlertDialog(
            query = snap.request.rawQuery,
            currency = state.region.currency,
            currentLowest = lowest,
            onDismiss = { showSaveSearchDialog = false },
            onConfirm = { targetMax, alertEnabled ->
                trackerViewModel.saveSearchWithAlert(
                    request = snap.request,
                    targetMaxPrice = targetMax,
                    alertEnabled = alertEnabled,
                    lowestTotal = lowest,
                    verifiedCount = verifiedCount
                )
            }
        )
    }
}

@Composable
private fun EmptyDiscoveryCard(onTryExample: (String) -> Unit) {
    val examples = listOf(
        "iPhone 15 Pro 256GB",
        "Find me the cheapest used RTX 4070 in Ireland",
        "Charizard ex Pokemon card",
        "Fender Stratocaster",
        "Elden Ring PC",
        "Clean Code Robert Martin"
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_price_radar_1795024595966),
                contentDescription = "Live multi-marketplace discovery illustration",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
            Text(
                text = "Every search runs fresh across live sources",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Search electronics, phones, GPUs, Pokémon cards, books, music gear, software keys, or paste a natural-language request. Or tap the photo icon to identify an item with Gemini Vision.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Try a live search:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                examples.forEach { ex ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTryExample(ex) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(ex, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search $ex",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
