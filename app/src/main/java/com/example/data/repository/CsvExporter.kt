package com.example.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.domain.model.ProductListing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    fun exportAndShare(context: Context, query: String, listings: List<ProductListing>): Result<Unit> {
        return try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeName = query.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(30).ifBlank { "search" }
            val outFile = File(exportDir, "pricefloor_${safeName}.csv")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            val sb = StringBuilder()
            sb.appendLine(
                "Title,Marketplace,Seller,Condition,Acquisition,OriginalPrice,OriginalCurrency," +
                    "ConvertedItemPrice,Shipping,FeesAndImportTax,TotalEstimatedCost,TotalVerified," +
                    "TargetCurrency,MatchConfidence,Location,RetrievedAt,SourceURL"
            )
            listings.forEach { item ->
                val c = item.costBreakdown
                val row = listOf(
                    item.title,
                    item.marketplaceName,
                    item.sellerName,
                    item.condition.displayLabel,
                    item.acquisitionType.label,
                    c.advertisedPriceOriginal.toString(),
                    c.originalCurrency,
                    c.convertedItemPrice.toString(),
                    c.shippingConverted?.toString() ?: "Unknown",
                    (c.mandatoryFeesConverted + c.estimatedImportVatOrDutyConverted).toString(),
                    c.totalEstimatedCostConverted?.toString() ?: "Unverified",
                    c.isTotalVerified.toString(),
                    c.targetCurrency,
                    "${item.matchConfidence}%",
                    item.locationText,
                    fmt.format(Date(item.retrievedAtMillis)),
                    item.sourceUrl
                ).joinToString(",") { escapeCsv(it) }
                sb.appendLine(row)
            }
            outFile.writeText(sb.toString(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PriceFloor Live Results: $query")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Export CSV Results").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
