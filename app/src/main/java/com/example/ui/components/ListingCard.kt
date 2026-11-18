package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.model.AcquisitionType
import com.example.domain.model.ListingCondition
import com.example.domain.model.ProductListing
import com.example.ui.theme.AmberAuction
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.VioletRental
import com.example.ui.util.Formatters

@Composable
fun ListingCard(
    listing: ProductListing,
    isBestOverall: Boolean,
    isSelectedForCompare: Boolean,
    onCardClick: () -> Unit,
    onToggleCompare: () -> Unit,
    onSaveToWatchlist: () -> Unit
) {
    val context = LocalContext.current
    val c = listing.costBreakdown
    val borderColor = when {
        isBestOverall -> EmeraldPrimary
        isSelectedForCompare -> CyanAccent
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isBestOverall || isSelectedForCompare) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clip(RoundedCornerShape(18.dp))
            .clickable { onCardClick() }
            .testTag("listing_card_${listing.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isBestOverall) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (isBestOverall) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LOWEST VERIFIED TOTAL ACQUISITION COST",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (!listing.imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = listing.imageUrl,
                            contentDescription = listing.title,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = listing.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${listing.marketplaceName} • ${listing.locationText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val condColor = conditionBadgeColor(listing.condition)
                        BadgeChip(listing.condition.displayLabel, condColor)
                        if (listing.acquisitionType == AcquisitionType.AUCTION) {
                            BadgeChip("Auction", AmberAuction)
                        } else if (listing.acquisitionType == AcquisitionType.RENTAL) {
                            BadgeChip("Rental", VioletRental)
                        }
                        val matchColor = if (listing.matchConfidence >= 75) EmeraldPrimary else if (listing.matchConfidence >= 50) AmberAuction else CoralError
                        BadgeChip("${listing.matchConfidence}% match", matchColor)
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    val primaryDisplay = when {
                        listing.rentalTerms != null -> Formatters.formatMoney(
                            listing.rentalTerms.totalForRequestedDurationConverted,
                            c.targetCurrency
                        )
                        c.totalEstimatedCostConverted != null -> Formatters.formatMoney(
                            c.totalEstimatedCostConverted,
                            c.targetCurrency
                        )
                        else -> Formatters.formatMoney(c.convertedItemPrice, c.targetCurrency)
                    }
                    Text(
                        text = primaryDisplay,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (c.isTotalVerified) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            listing.rentalTerms != null -> "for ${listing.rentalTerms.requestedDurationDays}d rental"
                            listing.acquisitionType == AcquisitionType.AUCTION -> "Current bid"
                            c.isTotalVerified -> "Verified total"
                            else -> "+ Shipping unknown"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.isTotalVerified) EmeraldPrimary else AmberAuction
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Transparent cost line
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val shipText = c.shippingConverted?.let {
                        if (it == 0.0) "Free ship" else "+${Formatters.formatMoney(it, c.targetCurrency)} ship"
                    } ?: "Ship: Unknown"
                    val taxText = if (c.estimatedImportVatOrDutyConverted > 0.0) {
                        " • +${Formatters.formatMoney(c.estimatedImportVatOrDutyConverted, c.targetCurrency)} VAT/duty"
                    } else ""
                    Text(
                        text = "Item ${Formatters.formatMoney(c.advertisedPriceOriginal, c.originalCurrency)} • $shipText$taxText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = Formatters.formatTime(listing.retrievedAtMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (listing.auctionDetails != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Gavel, contentDescription = null, tint = AmberAuction, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    val bids = listing.auctionDetails.bidCount?.let { "$it bids • " } ?: ""
                    val rem = listing.auctionDetails.timeRemainingText?.let { "Ends $it" } ?: "Live auction"
                    Text(
                        text = "$bids$rem (bid may increase)",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberAuction
                    )
                }
            }

            if (listing.suspiciousFlags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = CoralError, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = listing.suspiciousFlags.first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralError,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action strip with 48dp accessible touch targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = listing.availabilityText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleCompare,
                        modifier = Modifier.testTag("compare_toggle_${listing.id}")
                    ) {
                        Icon(
                            imageVector = if (isSelectedForCompare) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = "Compare listing",
                            tint = if (isSelectedForCompare) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onSaveToWatchlist,
                        modifier = Modifier.testTag("watchlist_add_${listing.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save to Watchlist",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listing.sourceUrl)))
                        },
                        modifier = Modifier.testTag("open_external_${listing.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open original listing",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun conditionBadgeColor(cond: ListingCondition): Color = when (cond) {
    ListingCondition.NEW, ListingCondition.OPEN_BOX -> EmeraldPrimary
    ListingCondition.LIKE_NEW, ListingCondition.REFURBISHED -> CyanAccent
    ListingCondition.DAMAGED -> CoralError
    ListingCondition.RENTAL, ListingCondition.SUBSCRIPTION -> VioletRental
    ListingCondition.AUCTION -> AmberAuction
    else -> Color(0xFF64748B)
}
