package com.example.data.network.connectors

import com.example.BuildConfig
import com.example.data.network.ConnectorOutcome
import com.example.data.network.MarketplaceConnector
import com.example.data.network.RawMarketplaceHit
import com.example.domain.model.ParsedSearchIntent
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.SourceCategory
import com.example.domain.model.SourceDescriptor
import com.example.domain.model.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8")

class CexConnector(
    private val httpClient: OkHttpClient,
    private val region: String // "IE" or "UK"
) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = if (region == "IE") "cex_ie" else "cex_uk",
        name = if (region == "IE") "CeX Ireland" else "CeX UK",
        category = SourceCategory.TECH,
        regionLabel = region,
        integrationMethod = "CeX Public Box Search API v3"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val domain = if (region == "IE") "wss2.cex.ie.webuy.io" else "wss2.cex.uk.webuy.io"
            val webBase = if (region == "IE") "https://ie.webuy.com" else "https://uk.webuy.com"
            val currency = if (region == "IE") "EUR" else "GBP"
            val defaultShipping = if (region == "IE") 3.50 else 2.95
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }

            try {
                val url = "https://$domain/v3/boxes?q=${encodeQuery(query)}&firstRecord=1&count=15"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) PriceFloor/1.0")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code} from $domain"
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val boxes = root.optJSONObject("response")
                        ?.optJSONObject("data")
                        ?.optJSONArray("boxes")
                        ?: JSONArray()

                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until boxes.length()) {
                        val b = boxes.optJSONObject(i) ?: continue
                        val boxId = b.optString("boxId")
                        val boxName = b.optString("boxName")
                        val sellPrice = b.optDouble("sellPrice", 0.0)
                        if (boxName.isBlank() || sellPrice <= 0.0) continue

                        val outOfStock = b.optInt("outOfStock", 0) == 1
                        val gradeCond = when {
                            boxName.contains(", A", ignoreCase = true) || boxName.endsWith(" A") -> "LIKE_NEW"
                            boxName.contains(", B", ignoreCase = true) || boxName.endsWith(" B") -> "GOOD"
                            boxName.contains(", C", ignoreCase = true) || boxName.endsWith(" C") -> "ACCEPTABLE"
                            else -> "REFURBISHED"
                        }
                        val img = b.optJSONObject("imageUrls")?.optString("medium")
                            ?.takeIf { it.startsWith("http") }

                        hits += RawMarketplaceHit(
                            title = boxName,
                            marketplaceId = descriptor.id,
                            marketplaceName = descriptor.name,
                            sellerName = "CeX $region (5-Yr Warranty)",
                            sellerReputation = "Verified Retailer",
                            conditionRaw = gradeCond,
                            acquisitionTypeRaw = "PURCHASE",
                            price = sellPrice,
                            currency = currency,
                            shippingCost = defaultShipping,
                            sourceCountryCode = if (region == "IE") "IE" else "GB",
                            locationText = if (region == "IE") "Ireland (CeX Stores & Online)" else "United Kingdom",
                            availabilityText = if (outOfStock) "Out of stock online" else "In Stock",
                            sourceUrl = "$webBase/product-detail?id=${encodeQuery(boxId)}",
                            imageUrl = img
                        )
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No matching CeX listings" else "${hits.size} live listings"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class ReverbConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "reverb",
        name = "Reverb",
        category = SourceCategory.SPECIALIST,
        regionLabel = "Global / EU",
        integrationMethod = "Reverb Public Listings API"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            try {
                val url = "https://api.reverb.com/api/listings?query=${encodeQuery(query)}&per_page=15"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/hal+json")
                    .header("Accept-Version", "3.0")
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code} from Reverb API"
                        )
                    }
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val arr = root.optJSONArray("listings") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val title = item.optString("title")
                        val priceObj = item.optJSONObject("price") ?: continue
                        val amount = priceObj.optString("amount").toDoubleOrNull() ?: continue
                        val currency = priceObj.optString("currency", "USD").uppercase()
                        if (title.isBlank() || amount <= 0.0) continue

                        val condRaw = item.optJSONObject("condition")?.optString("display_name") ?: "Used"
                        val shopName = item.optString("shop_name").ifBlank { "Reverb Seller" }
                        val webUrl = item.optJSONObject("_links")?.optJSONObject("web")?.optString("href")
                            ?: "https://reverb.com"
                        val img = item.optJSONArray("photos")?.optJSONObject(0)
                            ?.optJSONObject("_links")?.optJSONObject("large_crop")?.optString("href")

                        val shippingObj = item.optJSONObject("shipping")
                        val ratesArr = shippingObj?.optJSONArray("rates")
                        var shippingPrice: Double? = null
                        if (ratesArr != null && ratesArr.length() > 0) {
                            for (r in 0 until ratesArr.length()) {
                                val rateObj = ratesArr.optJSONObject(r) ?: continue
                                val regionCode = rateObj.optString("region_code")
                                if (regionCode.equals(request.countryCode, ignoreCase = true) ||
                                    regionCode == "EUR" || regionCode == "XX" || r == 0
                                ) {
                                    val amt = rateObj.optJSONObject("rate")?.optString("amount")?.toDoubleOrNull()
                                    if (amt != null) {
                                        shippingPrice = amt
                                        break
                                    }
                                }
                            }
                        }

                        val loc = item.optJSONObject("shop")?.optJSONObject("address")?.let { addr ->
                            listOfNotNull(
                                addr.optString("locality").takeIf { it.isNotBlank() },
                                addr.optString("country_code").takeIf { it.isNotBlank() }
                            ).joinToString(", ")
                        }.orEmpty().ifBlank { "International Seller" }

                        val originCountry = item.optJSONObject("shop")?.optJSONObject("address")
                            ?.optString("country_code")?.ifBlank { "US" } ?: "US"

                        hits += RawMarketplaceHit(
                            title = title,
                            marketplaceId = descriptor.id,
                            marketplaceName = descriptor.name,
                            sellerName = shopName,
                            conditionRaw = condRaw,
                            acquisitionTypeRaw = "PURCHASE",
                            price = amount,
                            currency = currency,
                            shippingCost = shippingPrice,
                            sourceCountryCode = originCountry,
                            locationText = loc,
                            availabilityText = item.optJSONObject("state")?.optString("description") ?: "Available",
                            listingDateText = item.optString("published_at").take(10).takeIf { it.isNotBlank() },
                            sourceUrl = webUrl,
                            imageUrl = img
                        )
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No Reverb listings found" else "${hits.size} live listings"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Connection failed"
                )
            }
        }
}

class CheapSharkConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "cheapshark",
        name = "CheapShark Digital Stores",
        category = SourceCategory.DISCOVERY,
        regionLabel = "Global Digital",
        integrationMethod = "CheapShark Public Deals API (Steam/GOG/Humble/Fanatical)"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            try {
                // Fetch store names map + deals
                val storesReq = Request.Builder()
                    .url("https://www.cheapshark.com/api/1.0/stores")
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                    .header("Accept", "application/json")
                    .build()
                val storeMap = mutableMapOf<String, String>()
                httpClient.newCall(storesReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            storeMap[s.optString("storeID")] = s.optString("storeName")
                        }
                    }
                }

                val dealsReq = Request.Builder()
                    .url("https://www.cheapshark.com/api/1.0/deals?title=${encodeQuery(query)}&pageSize=12")
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(dealsReq).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code} from CheapShark"
                        )
                    }
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until arr.length()) {
                        val d = arr.optJSONObject(i) ?: continue
                        val title = d.optString("title")
                        val salePrice = d.optString("salePrice").toDoubleOrNull() ?: continue
                        val normalPrice = d.optString("normalPrice").toDoubleOrNull()
                        val storeId = d.optString("storeID")
                        val storeName = storeMap[storeId] ?: "Digital Store #$storeId"
                        val dealId = d.optString("dealID")
                        val safeDealId = if (dealId.contains("%")) dealId else encodeQuery(dealId)
                        val thumb = d.optString("thumb").takeIf { it.startsWith("http") }
                        val isOnSale = d.optString("isOnSale") == "1"

                        hits += RawMarketplaceHit(
                            title = if (normalPrice != null && isOnSale && normalPrice > salePrice) {
                                "$title (Digital Key • RRP $$normalPrice)"
                            } else "$title (Digital Key)",
                            marketplaceId = "cheapshark_$storeId",
                            marketplaceName = storeName,
                            sellerName = storeName,
                            sellerReputation = "Authorized Key Store",
                            conditionRaw = if (isOnSale) "CLEARANCE" else "NEW",
                            acquisitionTypeRaw = "PURCHASE",
                            price = salePrice,
                            currency = "USD",
                            shippingCost = 0.0, // Instant digital delivery
                            sourceCountryCode = "US",
                            locationText = "Digital Delivery",
                            availabilityText = "Instant Key",
                            sourceUrl = "https://www.cheapshark.com/redirect?dealID=$safeDealId",
                            imageUrl = thumb
                        )
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No digital software/game matches" else "${hits.size} live digital store prices"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class PokemonTcgConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "pokemontcg",
        name = "TCGplayer & Cardmarket (Pokémon)",
        category = SourceCategory.SPECIALIST,
        regionLabel = "US / EU",
        integrationMethod = "Pokémon TCG API v2 (Live TCGplayer + Cardmarket Feeds)"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val qLower = (intent.canonicalProductName + " " + request.rawQuery).lowercase()
            val looksLikeCardQuery = intent.category.equals("TRADING_CARDS", ignoreCase = true) ||
                listOf("pokemon", "pokémon", "charizard", "pikachu", "booster", "etb", "card", "vmax", "ex ", "gx ").any { qLower.contains(it) }
            if (!looksLikeCardQuery) {
                return@withContext ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.NO_RESULTS,
                    latencyMs = 0L,
                    message = "Skipped (query not Pokémon TCG)"
                )
            }

            val cleanName = intent.canonicalProductName
                .replace("pokemon", "", ignoreCase = true)
                .replace("pokémon", "", ignoreCase = true)
                .replace("card", "", ignoreCase = true)
                .trim()
                .ifBlank { request.rawQuery }

            try {
                val req = Request.Builder()
                    .url("https://api.pokemontcg.io/v2/cards?q=name:\"${encodeQuery(cleanName)}\"&pageSize=8")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code} from pokemontcg.io"
                        )
                    }
                    val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until data.length()) {
                        val card = data.optJSONObject(i) ?: continue
                        val name = card.optString("name")
                        val setName = card.optJSONObject("set")?.optString("name").orEmpty()
                        val number = card.optString("number")
                        val img = card.optJSONObject("images")?.optString("small")
                        val fullTitle = "$name ($setName #$number)"

                        // Cardmarket EUR prices
                        val cm = card.optJSONObject("cardmarket")
                        val cmUrl = cm?.optString("url")
                        val cmPrices = cm?.optJSONObject("prices")
                        val cmLow = cmPrices?.optDouble("lowPrice", 0.0)?.takeIf { it > 0.0 }
                        if (cmLow != null && !cmUrl.isNullOrBlank()) {
                            hits += RawMarketplaceHit(
                                title = fullTitle,
                                marketplaceId = "cardmarket",
                                marketplaceName = "Cardmarket",
                                sellerName = "Cardmarket EU Sellers",
                                conditionRaw = "UNGRADED",
                                acquisitionTypeRaw = "PURCHASE",
                                price = cmLow,
                                currency = "EUR",
                                shippingCost = null, // Varies by seller
                                sourceCountryCode = "DE",
                                locationText = "Europe (Cardmarket)",
                                availabilityText = "Live Market Low",
                                listingDateText = cm.optString("updatedAt"),
                                sourceUrl = cmUrl,
                                imageUrl = img
                            )
                        }

                        // TCGplayer USD prices
                        val tcg = card.optJSONObject("tcgplayer")
                        val tcgUrl = tcg?.optString("url")
                        val tcgPrices = tcg?.optJSONObject("prices")
                        val variantKey = tcgPrices?.keys()?.asSequence()?.firstOrNull()
                        val tcgLow = variantKey?.let { tcgPrices.optJSONObject(it)?.optDouble("low", 0.0) }
                            ?.takeIf { it > 0.0 }
                        if (tcgLow != null && !tcgUrl.isNullOrBlank()) {
                            hits += RawMarketplaceHit(
                                title = "$fullTitle [$variantKey]",
                                marketplaceId = "tcgplayer",
                                marketplaceName = "TCGplayer",
                                sellerName = "TCGplayer Verified Sellers",
                                conditionRaw = "UNGRADED",
                                acquisitionTypeRaw = "PURCHASE",
                                price = tcgLow,
                                currency = "USD",
                                shippingCost = null,
                                sourceCountryCode = "US",
                                locationText = "United States (TCGplayer)",
                                availabilityText = "Live Low Price",
                                listingDateText = tcg.optString("updatedAt"),
                                sourceUrl = tcgUrl,
                                imageUrl = img
                            )
                        }
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No matching cards" else "${hits.size} live Cardmarket/TCGplayer prices"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class ScryfallConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "scryfall",
        name = "Cardmarket & TCGplayer (MTG)",
        category = SourceCategory.SPECIALIST,
        regionLabel = "US / EU",
        integrationMethod = "Scryfall Live Market API"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val qLower = (intent.canonicalProductName + " " + request.rawQuery).lowercase()
            val isMtg = intent.category.equals("TRADING_CARDS", ignoreCase = true) ||
                listOf("magic", "mtg", "lotus", "commander", "planeswalker", "foil").any { qLower.contains(it) }
            if (!isMtg) {
                return@withContext ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.NO_RESULTS,
                    latencyMs = 0L,
                    message = "Skipped (query not MTG)"
                )
            }

            try {
                val req = Request.Builder()
                    .url("https://api.scryfall.com/cards/search?q=${encodeQuery(intent.canonicalProductName)}")
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = if (resp.code == 404) SourceStatus.NO_RESULTS else SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = if (resp.code == 404) "No matching MTG cards" else "HTTP ${resp.code}"
                        )
                    }
                    val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    val limit = minOf(data.length(), 8)
                    for (i in 0 until limit) {
                        val card = data.optJSONObject(i) ?: continue
                        val name = card.optString("name")
                        val setName = card.optString("set_name")
                        val prices = card.optJSONObject("prices") ?: continue
                        val uris = card.optJSONObject("purchase_uris")
                        val img = card.optJSONObject("image_uris")?.optString("normal")

                        val eurPrice = prices.optString("eur").toDoubleOrNull()
                        val cmUri = uris?.optString("cardmarket")
                        if (eurPrice != null && eurPrice > 0.0 && !cmUri.isNullOrBlank()) {
                            hits += RawMarketplaceHit(
                                title = "$name ($setName)",
                                marketplaceId = "cardmarket",
                                marketplaceName = "Cardmarket",
                                sellerName = "Cardmarket EU",
                                conditionRaw = "UNGRADED",
                                price = eurPrice,
                                currency = "EUR",
                                shippingCost = null,
                                sourceCountryCode = "DE",
                                locationText = "Europe",
                                sourceUrl = cmUri,
                                imageUrl = img
                            )
                        }

                        val usdPrice = prices.optString("usd").toDoubleOrNull()
                        val tcgUri = uris?.optString("tcgplayer")
                        if (usdPrice != null && usdPrice > 0.0 && !tcgUri.isNullOrBlank()) {
                            hits += RawMarketplaceHit(
                                title = "$name ($setName)",
                                marketplaceId = "tcgplayer",
                                marketplaceName = "TCGplayer",
                                sellerName = "TCGplayer US",
                                conditionRaw = "UNGRADED",
                                price = usdPrice,
                                currency = "USD",
                                shippingCost = null,
                                sourceCountryCode = "US",
                                locationText = "United States",
                                sourceUrl = tcgUri,
                                imageUrl = img
                            )
                        }
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = "${hits.size} live MTG market listings"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class GoogleBooksConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "google_books",
        name = "Google Books & Textbooks",
        category = SourceCategory.DISCOVERY,
        regionLabel = "Global",
        integrationMethod = "Google Books Public Volumes API"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            try {
                val url = "https://www.googleapis.com/books/v1/volumes?q=${encodeQuery(query)}&maxResults=10"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code}"
                        )
                    }
                    val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val info = item.optJSONObject("volumeInfo") ?: continue
                        val sale = item.optJSONObject("saleInfo") ?: continue
                        val priceObj = sale.optJSONObject("retailPrice") ?: sale.optJSONObject("listPrice") ?: continue
                        val amount = priceObj.optDouble("amount", -1.0)
                        val currency = priceObj.optString("currencyCode", "EUR").uppercase()
                        if (amount <= 0.0) continue

                        val title = info.optString("title")
                        val authors = info.optJSONArray("authors")?.let { a ->
                            (0 until a.length()).map { a.optString(it) }.joinToString(", ")
                        }.orEmpty()
                        val fullTitle = if (authors.isNotBlank()) "$title — $authors" else title
                        val isEbook = sale.optBoolean("isEbook", false)
                        val buyLink = sale.optString("buyLink").ifBlank { info.optString("infoLink") }
                        val thumb = info.optJSONObject("imageLinks")?.optString("thumbnail")
                            ?.replace("http://", "https://")

                        hits += RawMarketplaceHit(
                            title = if (isEbook) "$fullTitle (eBook)" else fullTitle,
                            marketplaceId = descriptor.id,
                            marketplaceName = "Google Play Books",
                            sellerName = info.optString("publisher").ifBlank { "Google Books" },
                            conditionRaw = "NEW",
                            acquisitionTypeRaw = "PURCHASE",
                            price = amount,
                            currency = currency,
                            shippingCost = if (isEbook) 0.0 else null,
                            sourceCountryCode = request.countryCode,
                            locationText = if (isEbook) "Digital eBook" else "Online",
                            availabilityText = sale.optString("saleability").replace("_", " "),
                            listingDateText = info.optString("publishedDate"),
                            sourceUrl = buyLink,
                            imageUrl = thumb
                        )
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No priced book listings" else "${hits.size} live priced editions"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class UpcItemDbConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "upcitemdb",
        name = "UPCitemdb Multi-Retailer Offers",
        category = SourceCategory.DISCOVERY,
        regionLabel = "Global / US / EU",
        integrationMethod = "UPCitemdb Trial GTIN & Offer Search API"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            try {
                val url = "https://api.upcitemdb.com/prod/trial/search?s=${encodeQuery(query)}"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        val reason = if (resp.code == 429) "Trial rate limit reached (HTTP 429)" else "HTTP ${resp.code}"
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = reason
                        )
                    }
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val items = root.optJSONArray("items") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until minOf(items.length(), 8)) {
                        val item = items.optJSONObject(i) ?: continue
                        val title = item.optString("title")
                        val ean = item.optString("ean")
                        val img = item.optJSONArray("images")?.optString(0)
                            ?.takeIf { it.startsWith("http") }
                        val offers = item.optJSONArray("offers") ?: continue
                        for (j in 0 until offers.length()) {
                            val o = offers.optJSONObject(j) ?: continue
                            val price = o.optDouble("price", 0.0)
                            val link = o.optString("link")
                            val merchant = o.optString("merchant").ifBlank { o.optString("domain") }
                            if (price <= 0.0 || link.isBlank() || merchant.isBlank()) continue

                            val currency = o.optString("currency").ifBlank { "USD" }.uppercase()
                            val cond = o.optString("condition").ifBlank { "New" }
                            val shippingStr = o.optString("shipping")
                            val shippingCost = when {
                                shippingStr.equals("Free", ignoreCase = true) ||
                                    shippingStr.contains("Free", ignoreCase = true) -> 0.0
                                else -> shippingStr.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                            }

                            hits += RawMarketplaceHit(
                                title = if (ean.isNotBlank()) "$title (EAN $ean)" else title,
                                marketplaceId = "upc_${merchant.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                                marketplaceName = merchant,
                                sellerName = merchant,
                                conditionRaw = cond,
                                acquisitionTypeRaw = "PURCHASE",
                                price = price,
                                currency = currency,
                                shippingCost = shippingCost,
                                sourceCountryCode = if (currency == "EUR") "DE" else if (currency == "GBP") "GB" else "US",
                                locationText = o.optString("domain").ifBlank { merchant },
                                availabilityText = o.optString("availability").ifBlank { "Check merchant" },
                                sourceUrl = link,
                                imageUrl = img
                            )
                        }
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No GTIN merchant offers" else "${hits.size} live retailer offers"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class ItunesStoreConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "itunes_store",
        name = "Apple Store Media & Software",
        category = SourceCategory.DISCOVERY,
        regionLabel = "Localized",
        integrationMethod = "Apple iTunes Public Search API"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            val cc = request.countryCode.lowercase()
            try {
                val url = "https://itunes.apple.com/search?term=${encodeQuery(query)}&country=$cc&limit=8"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code}"
                        )
                    }
                    val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until arr.length()) {
                        val r = arr.optJSONObject(i) ?: continue
                        val price = r.optDouble("price", -1.0).takeIf { it > 0.0 }
                            ?: r.optDouble("trackPrice", -1.0).takeIf { it > 0.0 }
                            ?: r.optDouble("collectionPrice", -1.0).takeIf { it > 0.0 }
                            ?: continue
                        val title = r.optString("trackName").ifBlank { r.optString("collectionName") }
                        val urlView = r.optString("trackViewUrl").ifBlank { r.optString("collectionViewUrl") }
                        if (title.isBlank() || urlView.isBlank()) continue
                        val currency = r.optString("currency", request.targetCurrency).uppercase()
                        val kind = r.optString("kind").ifBlank { r.optString("wrapperType") }
                        val img = r.optString("artworkUrl100").takeIf { it.startsWith("http") }

                        hits += RawMarketplaceHit(
                            title = "$title ($kind)",
                            marketplaceId = descriptor.id,
                            marketplaceName = "Apple Store",
                            sellerName = r.optString("artistName").ifBlank { "Apple" },
                            conditionRaw = "NEW",
                            acquisitionTypeRaw = "PURCHASE",
                            price = price,
                            currency = currency,
                            shippingCost = 0.0,
                            sourceCountryCode = request.countryCode,
                            locationText = "Digital Download",
                            availabilityText = "Digital Instant",
                            sourceUrl = urlView,
                            imageUrl = img
                        )
                    }
                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "No digital items found" else "${hits.size} digital store prices"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}

class DiscogsConnector(private val httpClient: OkHttpClient) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "discogs",
        name = "Discogs",
        category = SourceCategory.SPECIALIST,
        regionLabel = "Global",
        integrationMethod = "Discogs Database & Marketplace API"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val token = BuildConfig.DISCOGS_TOKEN
            if (token.isBlank() || token.startsWith("MY_")) {
                return@withContext ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.REQUIRES_AUTH,
                    latencyMs = 0L,
                    message = "Requires DISCOGS_TOKEN in Secrets panel for marketplace pricing",
                    requiredSecretNames = listOf("DISCOGS_TOKEN")
                )
            }
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            try {
                val url = "https://api.discogs.com/database/search?q=${encodeQuery(query)}&type=release&per_page=8"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                    .header("Authorization", "Discogs token=$token")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "HTTP ${resp.code} from Discogs"
                        )
                    }
                    val results = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until minOf(results.length(), 5)) {
                        val release = results.optJSONObject(i) ?: continue
                        val releaseId = release.optLong("id", 0L)
                        val title = release.optString("title")
                        val thumb = release.optString("cover_image").takeIf { it.startsWith("http") }
                        if (releaseId <= 0L || title.isBlank()) continue

                        // Fetch live marketplace stats for lowest price
                        val statsReq = Request.Builder()
                            .url("https://api.discogs.com/marketplace/stats/$releaseId?curr_abbr=${request.targetCurrency}")
                            .header("User-Agent", "PriceFloorAndroid/1.0")
                            .header("Authorization", "Discogs token=$token")
                            .build()
                        httpClient.newCall(statsReq).execute().use { statsResp ->
                            if (statsResp.isSuccessful) {
                                val stats = JSONObject(statsResp.body?.string().orEmpty())
                                val lowest = stats.optJSONObject("lowest_price")
                                val value = lowest?.optDouble("value", 0.0) ?: 0.0
                                val currency = lowest?.optString("currency", request.targetCurrency) ?: request.targetCurrency
                                if (value > 0.0) {
                                    hits += RawMarketplaceHit(
                                        title = title,
                                        marketplaceId = descriptor.id,
                                        marketplaceName = descriptor.name,
                                        sellerName = "${stats.optInt("num_for_sale", 1)} sellers on Discogs",
                                        conditionRaw = "USED",
                                        price = value,
                                        currency = currency,
                                        shippingCost = null,
                                        sourceCountryCode = "US",
                                        locationText = "Discogs Marketplace",
                                        availabilityText = "${stats.optInt("num_for_sale", 0)} for sale",
                                        sourceUrl = "https://www.discogs.com/sell/release/$releaseId",
                                        imageUrl = thumb
                                    )
                                }
                            }
                        }
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = System.currentTimeMillis() - start,
                        message = if (hits.isEmpty()) "No priced Discogs releases" else "${hits.size} live Discogs lowest prices"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Network error"
                )
            }
        }
}
