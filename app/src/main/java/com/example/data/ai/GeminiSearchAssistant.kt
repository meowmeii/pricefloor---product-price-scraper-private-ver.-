package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.domain.model.ListingCondition
import com.example.domain.model.ParsedSearchIntent
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiSearchAssistant"

data class ImageProductIdentification(
    val identifiedName: String,
    val suggestedSearchQuery: String,
    val brandAndModel: String,
    val category: String,
    val detectedDetails: String
)

class GeminiSearchAssistant {

    private val querySchema = Schema.obj(
        properties = mapOf(
            "canonicalProductName" to Schema.string("Clean normalized product name without filler words like 'find me cheapest'"),
            "category" to Schema.enumeration(
                listOf("ELECTRONICS", "COMPUTING", "PHONES", "BOOKS", "TRADING_CARDS", "COLLECTIBLES", "MUSIC_GEAR", "SOFTWARE_GAMES", "HOME", "VEHICLES", "TOOLS", "GENERAL"),
                "Best matching product category"
            ),
            "alternativeSearchTerms" to Schema.array(
                Schema.string(),
                "Up to 3 alternative search queries, model numbers, or SKUs"
            ),
            "modelIdentifiers" to Schema.array(
                Schema.string(),
                "Specific model numbers, capacities, ISBNs, or SKU tokens extracted"
            ),
            "preferredConditions" to Schema.array(
                Schema.enumeration(
                    listOf("NEW", "USED", "LIKE_NEW", "VERY_GOOD", "GOOD", "REFURBISHED", "OPEN_BOX", "DAMAGED", "RENTAL", "AUCTION", "CLEARANCE"),
                    "Condition hint"
                ),
                "Explicitly requested conditions if any"
            ),
            "includeRentalsHint" to Schema.boolean("True if rentals are relevant or requested"),
            "includeAuctionsHint" to Schema.boolean("True if auctions are relevant or requested"),
            "rentalDurationDaysHint" to Schema.integer("Rental duration in days if mentioned, else 0"),
            "targetCountryHint" to Schema.string("2-letter ISO country code if mentioned (e.g. IE, GB, US, DE), else empty string"),
            "aiNote" to Schema.string("One concise sentence summarizing what product/variant is being searched")
        )
    )

    private val imageSchema = Schema.obj(
        properties = mapOf(
            "identifiedName" to Schema.string("Exact product title identified in the photo"),
            "suggestedSearchQuery" to Schema.string("Best clean marketplace search query for this exact product"),
            "brandAndModel" to Schema.string("Brand, generation, model number, or capacity if visible"),
            "category" to Schema.string("Product category"),
            "detectedDetails" to Schema.string("Any visible color, edition, barcode, or condition notes")
        )
    )

    private val queryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = querySchema
            }
        )
    }

    private val visionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = imageSchema
            }
        )
    }

    suspend fun parseAndExpandQuery(rawQuery: String, currentCountry: String): ParsedSearchIntent {
        val trimmed = rawQuery.trim()
        return try {
            val prompt = """
                Parse this product price-discovery query for a live multi-marketplace search engine.
                User's default country: $currentCountry.
                Query: "$trimmed"
                Strip conversational phrasing ("find me the cheapest", "in Ireland", etc.) into canonicalProductName while preserving critical specs (model, storage, edition, ISBN, card set).
            """.trimIndent()

            val response = withTimeout(30_000L) {
                queryModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw IllegalStateException("Empty AI response")
            val json = JSONObject(jsonStr)

            val altsArr = json.optJSONArray("alternativeSearchTerms")
            val alts = (0 until (altsArr?.length() ?: 0)).mapNotNull { altsArr?.optString(it)?.takeIf { s -> s.isNotBlank() } }

            val idsArr = json.optJSONArray("modelIdentifiers")
            val ids = (0 until (idsArr?.length() ?: 0)).mapNotNull { idsArr?.optString(it)?.takeIf { s -> s.isNotBlank() } }

            val condsArr = json.optJSONArray("preferredConditions")
            val conds = (0 until (condsArr?.length() ?: 0)).mapNotNull {
                condsArr?.optString(it)?.let { raw -> ListingCondition.fromRaw(raw) }
            }

            val rentalDays = json.optInt("rentalDurationDaysHint", 0).takeIf { it > 0 }
            val countryHint = json.optString("targetCountryHint").trim().uppercase().takeIf { it.length == 2 }

            ParsedSearchIntent(
                originalQuery = trimmed,
                canonicalProductName = json.optString("canonicalProductName").ifBlank { trimmed },
                category = json.optString("category", "GENERAL"),
                alternativeSearchTerms = alts,
                modelIdentifiers = ids,
                preferredConditions = conds,
                includeRentalsHint = json.optBoolean("includeRentalsHint", true),
                includeAuctionsHint = json.optBoolean("includeAuctionsHint", true),
                rentalDurationDaysHint = rentalDays,
                targetCountryHint = countryHint,
                aiExpanded = true,
                aiNote = json.optString("aiNote").takeIf { it.isNotBlank() }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini query expansion fallback to deterministic parser: ${e.message}")
            deterministicFallback(trimmed)
        }
    }

    suspend fun identifyProductFromImage(bitmap: Bitmap): Result<ImageProductIdentification> {
        return try {
            val scaled = scaleBitmapIfNeeded(bitmap, 1024)
            val response = withTimeout(45_000L) {
                visionModel.generateContent(
                    content {
                        image(scaled)
                        text(
                            "Identify this exact product for price comparison across online marketplaces. " +
                                "Extract brand, model number, generation, storage/size, or barcode/ISBN if visible, " +
                                "and provide a concise marketplace search query."
                        )
                    }
                )
            }
            val jsonStr = response.text ?: return Result.failure(IllegalStateException("Model returned empty response"))
            val json = JSONObject(jsonStr)
            Result.success(
                ImageProductIdentification(
                    identifiedName = json.optString("identifiedName", "Identified Product"),
                    suggestedSearchQuery = json.optString("suggestedSearchQuery").ifBlank {
                        json.optString("identifiedName", "")
                    },
                    brandAndModel = json.optString("brandAndModel"),
                    category = json.optString("category", "GENERAL"),
                    detectedDetails = json.optString("detectedDetails")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Image product identification failed", e)
            Result.failure(e)
        }
    }

    private fun deterministicFallback(query: String): ParsedSearchIntent {
        val lower = query.lowercase()
        val cleaned = query
            .replace(Regex("""(?i)\b(find me the cheapest|find the cheapest|cheapest way to|find a|in ireland|in uk)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { query }
        val conds = mutableListOf<ListingCondition>()
        if (lower.contains("used") || lower.contains("second-hand") || lower.contains("second hand")) conds += ListingCondition.USED
        if (lower.contains("refurb")) conds += ListingCondition.REFURBISHED
        if (lower.contains("new")) conds += ListingCondition.NEW
        if (lower.contains("rent")) conds += ListingCondition.RENTAL
        return ParsedSearchIntent(
            originalQuery = query,
            canonicalProductName = cleaned,
            preferredConditions = conds,
            aiExpanded = false,
            aiNote = null
        )
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }
}
