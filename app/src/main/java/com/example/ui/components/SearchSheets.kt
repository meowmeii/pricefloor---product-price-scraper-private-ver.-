package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.repository.RegionPreferences
import com.example.domain.model.AcquisitionType
import com.example.domain.model.ProductListing
import com.example.domain.model.RegionDefaults
import com.example.domain.model.SourceStatus
import com.example.domain.model.SourceTelemetry
import com.example.ui.theme.AmberAuction
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.util.Formatters
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegionCurrencySheet(
    current: RegionPreferences,
    onDismiss: () -> Unit,
    onApply: (countryCode: String, postcode: String, currency: String, rentalDays: Int) -> Unit
) {
    var selectedCountry by remember { mutableStateOf(current.countryCode) }
    var postcode by remember { mutableStateOf(current.postcode) }
    var selectedCurrency by remember { mutableStateOf(current.currency) }
    var rentalDays by remember { mutableFloatStateOf(current.rentalDurationDays.toFloat()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Delivery Location, Currency & Rental Term",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Destination Country (determines local sources & import VAT/duties)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RegionDefaults.countries.forEach { country ->
                    FilterChip(
                        selected = selectedCountry == country.code,
                        onClick = {
                            selectedCountry = country.code
                            selectedCurrency = country.defaultCurrency
                        },
                        label = { Text("${country.name} (${country.code})") },
                        modifier = Modifier.testTag("country_chip_${country.code}")
                    )
                }
            }

            OutlinedTextField(
                value = postcode,
                onValueChange = { postcode = it },
                label = { Text("Postcode / Eircode (optional)") },
                placeholder = { Text("e.g. D02 X285") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("postcode_input")
            )

            Text(
                text = "Preferred Display Currency",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RegionDefaults.currencies.forEach { (code, sym) ->
                    FilterChip(
                        selected = selectedCurrency == code,
                        onClick = { selectedCurrency = code },
                        label = { Text("$code ($sym)") },
                        modifier = Modifier.testTag("currency_chip_$code")
                    )
                }
            }

            Column {
                Text(
                    text = "Rental Comparison Duration: ${rentalDays.roundToInt()} days",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = rentalDays,
                    onValueChange = { rentalDays = it },
                    valueRange = 1f..30f,
                    steps = 28
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onApply(selectedCountry, postcode, selectedCurrency, rentalDays.roundToInt())
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("apply_region_button")
                ) {
                    Text("Apply & Search")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesTelemetrySheet(
    telemetry: List<SourceTelemetry>,
    onDismiss: () -> Unit
) {
    val searched = telemetry.filter { it.status == SourceStatus.SEARCHED }
    val noResults = telemetry.filter { it.status == SourceStatus.NO_RESULTS }
    val unavailable = telemetry.filter { it.status == SourceStatus.UNAVAILABLE }
    val requiresAuth = telemetry.filter { it.status == SourceStatus.REQUIRES_AUTH }
    val unsupported = telemetry.filter { it.status == SourceStatus.UNSUPPORTED }
    val searching = telemetry.filter { it.status == SourceStatus.SEARCHING }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Live Source Transparency",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${searched.size} returned hits • ${noResults.size} 0-hits • ${unavailable.size} unavailable • ${requiresAuth.size} API-key gated • ${unsupported.size} unsupported",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close sources sheet")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(420.dp)
            ) {
                if (searching.isNotEmpty()) {
                    item { SectionHeader("Currently Searching (${searching.size})") }
                    items(searching, key = { it.descriptor.id }) { TelemetryRow(it) }
                }
                if (searched.isNotEmpty()) {
                    item { SectionHeader("Returned Live Results (${searched.size})") }
                    items(searched, key = { it.descriptor.id }) { TelemetryRow(it) }
                }
                if (noResults.isNotEmpty()) {
                    item { SectionHeader("Searched — 0 Matching Listings (${noResults.size})") }
                    items(noResults, key = { it.descriptor.id }) { TelemetryRow(it) }
                }
                if (unavailable.isNotEmpty()) {
                    item { SectionHeader("Unavailable / Blocked / Timed Out (${unavailable.size})") }
                    items(unavailable, key = { it.descriptor.id }) { TelemetryRow(it) }
                }
                if (requiresAuth.isNotEmpty()) {
                    item { SectionHeader("Requires API Key in Secrets Panel (${requiresAuth.size})") }
                    items(requiresAuth, key = { it.descriptor.id }) { TelemetryRow(it) }
                }
                if (unsupported.isNotEmpty()) {
                    item { SectionHeader("Unsupported by Platform Terms (${unsupported.size})") }
                    items(unsupported, key = { it.descriptor.id }) { TelemetryRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun TelemetryRow(item: SourceTelemetry) {
    val (icon, tint) = when (item.status) {
        SourceStatus.SEARCHED -> Icons.Default.CheckCircle to EmeraldPrimary
        SourceStatus.SEARCHING -> Icons.Default.CheckCircle to CyanAccent
        SourceStatus.NO_RESULTS -> Icons.Default.SearchOff to MaterialTheme.colorScheme.onSurfaceVariant
        SourceStatus.UNAVAILABLE -> Icons.Default.ErrorOutline to AmberAuction
        SourceStatus.REQUIRES_AUTH -> Icons.Default.Key to CyanAccent
        SourceStatus.UNSUPPORTED -> Icons.Default.Warning to CoralError
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = item.status.label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${item.descriptor.name} (${item.descriptor.regionLabel})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (item.latencyMs > 0) {
                        Text(
                            text = "${item.latencyMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "${item.descriptor.integrationMethod} • ${item.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.requiredSecretNames.isNotEmpty()) {
                    Text(
                        text = "Secrets: ${item.requiredSecretNames.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingDetailSheet(
    listing: ProductListing,
    onDismiss: () -> Unit,
    onSaveToWatchlist: (ProductListing) -> Unit
) {
    val context = LocalContext.current
    val c = listing.costBreakdown

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = listing.title,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "${listing.marketplaceName} • ${listing.sellerName}${listing.sellerReputation?.let { " ($it)" } ?: ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistPill(listing.condition.displayLabel, EmeraldPrimary)
                AssistPill(listing.acquisitionType.label, CyanAccent)
                AssistPill("${listing.matchConfidence}% Match", MaterialTheme.colorScheme.secondary)
            }

            if (listing.suspiciousFlags.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listing.suspiciousFlags.forEach { flag ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = CoralError, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(flag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("True Total Acquisition Cost Breakdown", style = MaterialTheme.typography.titleSmall)
                    CostRow(
                        "Advertised Price (${c.originalCurrency})",
                        "${Formatters.formatMoney(c.advertisedPriceOriginal, c.originalCurrency)} → ${Formatters.formatMoney(c.convertedItemPrice, c.targetCurrency)}"
                    )
                    CostRow(
                        "Shipping (${listing.sourceCountryCode} → Dest)",
                        c.shippingConverted?.let {
                            if (it == 0.0) "Free (0.00)" else Formatters.formatMoney(it, c.targetCurrency)
                        } ?: "Unknown (Unverified)"
                    )
                    CostRow(
                        "Mandatory Buyer Fees",
                        Formatters.formatMoney(c.mandatoryFeesConverted, c.targetCurrency)
                    )
                    CostRow(
                        "Estimated Import VAT / Duty",
                        Formatters.formatMoney(c.estimatedImportVatOrDutyConverted, c.targetCurrency)
                    )
                    HorizontalDivider()
                    CostRow(
                        label = if (c.isTotalVerified) "Verified Total Cost" else "Total Estimated Cost (Unverified)",
                        value = c.totalEstimatedCostConverted?.let { Formatters.formatMoney(it, c.targetCurrency) }
                            ?: "Unknown (Shipping unverified)",
                        bold = true
                    )
                    c.costNotes.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            listing.rentalTerms?.let { r ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Rental Schedule (${r.requestedDurationDays} days selected)", style = MaterialTheme.typography.titleSmall)
                        CostRow("Daily Rate", Formatters.formatMoney(r.dailyRateConverted, c.targetCurrency))
                        CostRow("Weekly Rate", Formatters.formatMoney(r.weeklyRateConverted, c.targetCurrency))
                        CostRow("Monthly Rate", Formatters.formatMoney(r.monthlyRateConverted, c.targetCurrency))
                        r.depositConverted?.let { CostRow("Deposit", Formatters.formatMoney(it, c.targetCurrency)) }
                        CostRow("Total for ${r.requestedDurationDays} Days", Formatters.formatMoney(r.totalForRequestedDurationConverted, c.targetCurrency), bold = true)
                    }
                }
            }

            listing.auctionDetails?.let { a ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Auction Status (Price may increase)", style = MaterialTheme.typography.titleSmall)
                        CostRow("Current Bid", Formatters.formatMoney(a.currentBidConverted, c.targetCurrency), bold = true)
                        a.bidCount?.let { CostRow("Bids Placed", "$it bids") }
                        a.timeRemainingText?.let { CostRow("Ends / Remaining", it) }
                        a.buyItNowConverted?.let { CostRow("Buy It Now Option", Formatters.formatMoney(it, c.targetCurrency)) }
                    }
                }
            }

            Text(
                text = "Location: ${listing.locationText} • Stock: ${listing.availabilityText} • Retrieved: ${Formatters.formatTime(listing.retrievedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSaveToWatchlist(listing)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("detail_watchlist_button")
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Track Price")
                }
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(listing.sourceUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("detail_open_listing_button")
                ) {
                    Text("Open Listing")
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareListingsSheet(
    listings: List<ProductListing>,
    onDismiss: () -> Unit,
    onClearCompare: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Side-by-Side Comparison (${listings.size})", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = {
                    onClearCompare()
                    onDismiss()
                }) {
                    Text("Clear")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listings.forEach { item ->
                    val c = item.costBreakdown
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .width(220.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.marketplaceName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider()
                            CompareCell("Condition", item.condition.displayLabel)
                            CompareCell("Method", item.acquisitionType.label)
                            CompareCell("Item Price", Formatters.formatMoney(c.convertedItemPrice, c.targetCurrency))
                            CompareCell("Shipping", c.shippingConverted?.let { Formatters.formatMoney(it, c.targetCurrency) } ?: "Unknown")
                            CompareCell("VAT / Fees", Formatters.formatMoney(c.estimatedImportVatOrDutyConverted + c.mandatoryFeesConverted, c.targetCurrency))
                            CompareCell(
                                "Total",
                                c.totalEstimatedCostConverted?.let { Formatters.formatMoney(it, c.targetCurrency) } ?: "Unverified",
                                highlight = c.isTotalVerified
                            )
                            CompareCell("Match", "${item.matchConfidence}%")
                            CompareCell("Origin", item.locationText)
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SaveSearchAlertDialog(
    query: String,
    currency: String,
    currentLowest: Double?,
    onDismiss: () -> Unit,
    onConfirm: (targetMaxPrice: Double?, alertEnabled: Boolean) -> Unit
) {
    var targetText by remember { mutableStateOf(currentLowest?.let { (it * 0.95).roundToInt().toString() } ?: "") }
    var alertEnabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Search & Price Alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Query: \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("Target Alert Max Price ($currency, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable price-drop alert", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = alertEnabled, onCheckedChange = { alertEnabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(targetText.toDoubleOrNull(), alertEnabled)
                    onDismiss()
                },
                modifier = Modifier.testTag("confirm_save_search_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CompareCell(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) EmeraldPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CostRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            color = if (bold) EmeraldPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun AssistPill(label: String, accentColor: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}
