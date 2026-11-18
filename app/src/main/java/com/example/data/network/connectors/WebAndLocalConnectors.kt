package com.example.data.network.connectors

import android.util.Base64
import com.example.BuildConfig
import com.example.data.network.ConnectorOutcome
import com.example.data.network.HtmlStructuredExtractor
import com.example.data.network.MarketplaceConnector
import com.example.data.network.RawMarketplaceHit
import com.example.data.network.ebay.EbayFindingOutcome
import com.example.data.network.ebay.EbayFindingService
import com.example.domain.model.ParsedSearchIntent
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.SourceCategory
import com.example.domain.model.SourceDescriptor
import com.example.domain.model.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

class EbayConnector(
    private val httpClient: OkHttpClient,
    private val findingService: EbayFindingService = EbayFindingService.create(httpClient)
) : MarketplaceConnector {
    override val descriptor = SourceDescriptor(
        id = "ebay",
        name = "eBay (Buy It Now & Auctions)",
        category = SourceCategory.GENERAL,
        regionLabel = "IE / UK / US / DE",
        integrationMethod = "eBay Browse API v1 & Finding API (+ Public RSS Check)"
    )

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val clientId = BuildConfig.EBAY_CLIENT_ID
            val clientSecret = BuildConfig.EBAY_CLIENT_SECRET
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }

            val hasClientId = clientId.isNotBlank() && !clientId.startsWith("MY_")
            val hasSecret = clientSecret.isNotBlank() && !clientSecret.startsWith("MY_")

            if (hasClientId && hasSecret) {
                val browseResult = searchOfficialBrowseApi(clientId, clientSecret, query, request, start)
                if (browseResult.status == SourceStatus.SEARCHED || browseResult.status == SourceStatus.NO_RESULTS) {
                    return@withContext browseResult
                }
            }

            if (hasClientId) {
                when (val findingResult = findingService.findItems(query, request.countryCode)) {
                    is EbayFindingOutcome.Success -> {
                        val hits = findingResult.items.map { it.toRawMarketplaceHit() }
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                            hits = hits,
                            latencyMs = System.currentTimeMillis() - start,
                            message = "${hits.size} eBay Finding API listings"
                        )
                    }
                    is EbayFindingOutcome.Error -> {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = System.currentTimeMillis() - start,
                            message = findingResult.message
                        )
                    }
                    is EbayFindingOutcome.MissingCredentials -> Unit
                }
            }

            // Attempt public eBay RSS search feed if available; otherwise report REQUIRES_AUTH honestly
            val host = when (request.countryCode.uppercase()) {
                "IE" -> "www.ebay.ie"
                "GB" -> "www.ebay.co.uk"
                "DE" -> "www.ebay.de"
                "FR" -> "www.ebay.fr"
                "CA" -> "www.ebay.ca"
                "AU" -> "www.ebay.com.au"
                else -> "www.ebay.com"
            }
            val defaultCurr = when (request.countryCode.uppercase()) {
                "GB" -> "GBP"
                "US" -> "USD"
                "CA" -> "CAD"
                "AU" -> "AUD"
                else -> "EUR"
            }

            try {
                val rssUrl = "https://$host/sch/i.html?_nkw=${urlEncode(query)}&_sop=15&_rss=1"
                val req = Request.Builder()
                    .url(rssUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) PriceFloor/1.0")
                    .header("Accept", "application/rss+xml, application/xml, text/xml")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || !body.contains("<item>")) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.REQUIRES_AUTH,
                            latencyMs = latency,
                            message = "Configure EBAY_CLIENT_ID & EBAY_CLIENT_SECRET in Secrets panel for official eBay Browse API (public RSS returned HTTP ${resp.code})",
                            requiredSecretNames = listOf("EBAY_CLIENT_ID", "EBAY_CLIENT_SECRET")
                        )
                    }
                    val hits = parseEbayRss(body, request.countryCode, defaultCurr)
                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = "${hits.size} live eBay RSS listings"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.REQUIRES_AUTH,
                    latencyMs = System.currentTimeMillis() - start,
                    message = "Configure EBAY_CLIENT_ID & EBAY_CLIENT_SECRET in Secrets panel (${e.localizedMessage})",
                    requiredSecretNames = listOf("EBAY_CLIENT_ID", "EBAY_CLIENT_SECRET")
                )
            }
        }

    private fun searchOfficialBrowseApi(
        clientId: String,
        clientSecret: String,
        query: String,
        request: SearchQueryRequest,
        start: Long
    ): ConnectorOutcome {
        return try {
            val creds = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
            val tokenReq = Request.Builder()
                .url("https://api.ebay.com/identity/v1/oauth2/token")
                .header("Authorization", "Basic $creds")
                .post(
                    FormBody.Builder()
                        .add("grant_type", "client_credentials")
                        .add("scope", "https://api.ebay.com/oauth/api_scope")
                        .build()
                )
                .build()
            val accessToken = httpClient.newCall(tokenReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return ConnectorOutcome(
                        descriptor = descriptor,
                        status = SourceStatus.UNAVAILABLE,
                        latencyMs = System.currentTimeMillis() - start,
                        message = "eBay OAuth failed (HTTP ${resp.code})"
                    )
                }
                JSONObject(resp.body?.string().orEmpty()).optString("access_token")
            }

            val marketplaceHeader = when (request.countryCode.uppercase()) {
                "IE" -> "EBAY_IE"
                "GB" -> "EBAY_GB"
                "DE" -> "EBAY_DE"
                "FR" -> "EBAY_FR"
                "CA" -> "EBAY_CA"
                "AU" -> "EBAY_AU"
                else -> "EBAY_US"
            }

            val searchUrl = "https://api.ebay.com/buy/browse/v1/item_summary/search?q=${urlEncode(query)}&limit=20&sort=price"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("X-EBAY-C-MARKETPLACE-ID", marketplaceHeader)
                .build()
            httpClient.newCall(searchReq).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                if (!resp.isSuccessful) {
                    return ConnectorOutcome(
                        descriptor = descriptor,
                        status = SourceStatus.UNAVAILABLE,
                        latencyMs = latency,
                        message = "eBay Browse API HTTP ${resp.code}"
                    )
                }
                val summaries = JSONObject(resp.body?.string().orEmpty()).optJSONArray("itemSummaries") ?: JSONArray()
                val hits = mutableListOf<RawMarketplaceHit>()
                for (i in 0 until summaries.length()) {
                    val s = summaries.optJSONObject(i) ?: continue
                    val title = s.optString("title")
                    val url = s.optString("itemWebUrl")
                    val buyingOptions = s.optJSONArray("buyingOptions")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    } ?: emptyList()

                    val isAuction = buyingOptions.contains("AUCTION") && !buyingOptions.contains("FIXED_PRICE")
                    val priceObj = if (isAuction) s.optJSONObject("currentBidPrice") ?: s.optJSONObject("price")
                    else s.optJSONObject("price") ?: s.optJSONObject("currentBidPrice")
                    val priceVal = priceObj?.optString("value")?.toDoubleOrNull() ?: continue
                    val currency = priceObj.optString("currency", request.targetCurrency).uppercase()

                    val shipObj = s.optJSONArray("shippingOptions")?.optJSONObject(0)?.optJSONObject("shippingCost")
                    val shipCost = shipObj?.optString("value")?.toDoubleOrNull()
                    val sellerObj = s.optJSONObject("seller")
                    val sellerName = sellerObj?.optString("username").orEmpty().ifBlank { "eBay Seller" }
                    val rep = sellerObj?.optString("feedbackPercentage")?.let { "$it% positive" }

                    val loc = s.optJSONObject("itemLocation")
                    val country = loc?.optString("country")?.ifBlank { request.countryCode } ?: request.countryCode
                    val city = loc?.optString("city").orEmpty()
                    val locText = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")

                    hits += RawMarketplaceHit(
                        title = title,
                        marketplaceId = "ebay",
                        marketplaceName = "eBay",
                        sellerName = sellerName,
                        sellerReputation = rep,
                        conditionRaw = if (isAuction) "AUCTION" else s.optString("condition", "USED"),
                        acquisitionTypeRaw = if (isAuction) "AUCTION" else "PURCHASE",
                        price = priceVal,
                        currency = currency,
                        shippingCost = shipCost,
                        sourceCountryCode = country,
                        locationText = locText.ifBlank { country },
                        availabilityText = if (isAuction) "Active Auction" else "Available",
                        listingDateText = s.optString("itemEndDate").take(10).takeIf { isAuction && it.isNotBlank() },
                        sourceUrl = url,
                        imageUrl = s.optJSONObject("image")?.optString("imageUrl"),
                        auctionCurrentBid = if (isAuction) priceVal else null,
                        auctionBidCount = if (isAuction) s.optInt("bidCount", 0) else null,
                        auctionTimeRemaining = s.optString("itemEndDate").takeIf { isAuction && it.isNotBlank() },
                        auctionBuyItNow = s.optJSONObject("price")?.optString("value")?.toDoubleOrNull()
                            ?.takeIf { isAuction && buyingOptions.contains("FIXED_PRICE") }
                    )
                }
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                    hits = hits,
                    latencyMs = latency,
                    message = "${hits.size} official eBay Browse API listings"
                )
            }
        } catch (e: Exception) {
            ConnectorOutcome(
                descriptor = descriptor,
                status = SourceStatus.UNAVAILABLE,
                latencyMs = System.currentTimeMillis() - start,
                message = e.localizedMessage ?: "eBay API error"
            )
        }
    }

    private fun parseEbayRss(xml: String, countryCode: String, defaultCurrency: String): List<RawMarketplaceHit> {
        val hits = mutableListOf<RawMarketplaceHit>()
        val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("<title><!\\[CDATA\\[(.*?)]]></title>|<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val linkRegex = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
        val descRegex = Regex("<description><!\\[CDATA\\[(.*?)]]></description>", RegexOption.DOT_MATCHES_ALL)
        itemRegex.findAll(xml).take(15).forEach { m ->
            val block = m.groupValues[1]
            val titleMatch = titleRegex.find(block)
            val title = HtmlStructuredExtractor.decodeHtml(
                (titleMatch?.groupValues?.getOrNull(1).orEmpty().ifBlank { titleMatch?.groupValues?.getOrNull(2).orEmpty() })
            )
            val link = linkRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val desc = descRegex.find(block)?.groupValues?.get(1).orEmpty()
            val parsed = HtmlStructuredExtractor.parsePriceWithCurrency(desc, defaultCurrency) ?: return@forEach
            val isAuction = desc.contains("Bid", ignoreCase = true) && !desc.contains("Buy It Now", ignoreCase = true)
            if (title.isNotBlank() && link.startsWith("http")) {
                hits += RawMarketplaceHit(
                    title = title,
                    marketplaceId = "ebay",
                    marketplaceName = "eBay",
                    sellerName = "eBay Seller",
                    conditionRaw = if (isAuction) "AUCTION" else "USED",
                    acquisitionTypeRaw = if (isAuction) "AUCTION" else "PURCHASE",
                    price = parsed.first,
                    currency = parsed.second,
                    shippingCost = null,
                    sourceCountryCode = countryCode,
                    locationText = countryCode,
                    sourceUrl = link
                )
            }
        }
        return hits
    }
}

class SerpApiShoppingConnector(
    private val httpClient: OkHttpClient,
    private val engine: String, // "google_shopping" or "bing_shopping"
    override val descriptor: SourceDescriptor
) : MarketplaceConnector {

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val key = BuildConfig.SERPAPI_KEY
            if (key.isBlank() || key.startsWith("MY_")) {
                return@withContext ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.REQUIRES_AUTH,
                    latencyMs = 0L,
                    message = "Configure SERPAPI_KEY in Secrets panel to unlock live ${descriptor.name}",
                    requiredSecretNames = listOf("SERPAPI_KEY")
                )
            }

            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            val gl = request.countryCode.lowercase()
            val url = "https://serpapi.com/search.json?engine=$engine&q=${urlEncode(query)}&gl=$gl&hl=en&api_key=${urlEncode(key)}"
            try {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "SerpApi HTTP ${resp.code}"
                        )
                    }
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val arr = root.optJSONArray("shopping_results") ?: JSONArray()
                    val hits = mutableListOf<RawMarketplaceHit>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val title = item.optString("title")
                        val extractedPrice = item.optDouble("extracted_price", 0.0)
                        val link = item.optString("link").ifBlank { item.optString("product_link") }
                        val source = item.optString("source").ifBlank { descriptor.name }
                        if (title.isBlank() || extractedPrice <= 0.0 || link.isBlank()) continue

                        val deliveryStr = item.optString("delivery")
                        val shippingCost = when {
                            deliveryStr.contains("Free", ignoreCase = true) -> 0.0
                            else -> HtmlStructuredExtractor.parsePriceWithCurrency(deliveryStr, request.targetCurrency)?.first
                        }
                        val condRaw = item.optString("second_hand_condition").ifBlank {
                            if (title.contains("refurb", ignoreCase = true)) "REFURBISHED" else "NEW"
                        }

                        hits += RawMarketplaceHit(
                            title = title,
                            marketplaceId = "${engine}_${source.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                            marketplaceName = source,
                            sellerName = source,
                            sellerReputation = item.optDouble("rating", 0.0).takeIf { it > 0 }?.let { "$it★" },
                            conditionRaw = condRaw,
                            acquisitionTypeRaw = "PURCHASE",
                            price = extractedPrice,
                            currency = request.targetCurrency,
                            shippingCost = shippingCost,
                            sourceCountryCode = request.countryCode,
                            locationText = request.countryCode,
                            availabilityText = deliveryStr.ifBlank { "Online" },
                            sourceUrl = link,
                            imageUrl = item.optString("thumbnail").takeIf { it.startsWith("http") }
                        )
                    }
                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = "${hits.size} live ${descriptor.name} offers"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "SerpApi error"
                )
            }
        }
}

/**
 * Generic Schema.org JSON-LD + OpenGraph/listing HTML connector for live public retailer & classifieds pages.
 * Respects HTTP 403/429/503 responses transparently without attempting anti-bot bypasses.
 */
class StructuredWebConnector(
    private val httpClient: OkHttpClient,
    override val descriptor: SourceDescriptor,
    private val searchUrlBuilder: (query: String) -> String,
    private val defaultCurrency: String,
    private val defaultCountryCode: String,
    private val defaultConditionRaw: String = "USED",
    private val defaultAcquisitionRaw: String = "PURCHASE",
    private val knownShippingCost: Double? = null,
    private val customHtmlParser: ((html: String, baseUrl: String) -> List<RawMarketplaceHit>)? = null
) : MarketplaceConnector {

    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val query = intent.canonicalProductName.ifBlank { request.rawQuery }
            val url = searchUrlBuilder(urlEncode(query))

            try {
                val req = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-IE,en-GB;q=0.9,en;q=0.8")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    if (!resp.isSuccessful) {
                        val reason = when (resp.code) {
                            403, 429, 503 -> "Protected by WAF / rate limit (HTTP ${resp.code})"
                            else -> "HTTP ${resp.code}"
                        }
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = reason
                        )
                    }

                    val html = resp.body?.string().orEmpty()
                    if (html.contains("Just a moment...", ignoreCase = true) ||
                        html.contains("cf-browser-verification", ignoreCase = true)
                    ) {
                        return@withContext ConnectorOutcome(
                            descriptor = descriptor,
                            status = SourceStatus.UNAVAILABLE,
                            latencyMs = latency,
                            message = "Blocked by anti-bot challenge"
                        )
                    }

                    val hits = mutableListOf<RawMarketplaceHit>()
                    // 1. Custom site-specific structured parser if supplied
                    if (customHtmlParser != null) {
                        hits += customHtmlParser.invoke(html, url)
                    }
                    // 2. Standard Schema.org JSON-LD Product/Offer extraction
                    if (hits.isEmpty()) {
                        val offers = HtmlStructuredExtractor.extractJsonLdOffers(html, url, defaultCurrency)
                        offers.take(15).forEach { o ->
                            hits += RawMarketplaceHit(
                                title = o.title,
                                marketplaceId = descriptor.id,
                                marketplaceName = descriptor.name,
                                sellerName = o.sellerName ?: descriptor.name,
                                conditionRaw = o.condition.name.ifBlank { defaultConditionRaw },
                                acquisitionTypeRaw = defaultAcquisitionRaw,
                                price = o.price,
                                currency = o.currency,
                                shippingCost = knownShippingCost,
                                sourceCountryCode = defaultCountryCode,
                                locationText = descriptor.regionLabel,
                                availabilityText = o.availability,
                                sourceUrl = o.url,
                                imageUrl = o.imageUrl
                            )
                        }
                    }

                    ConnectorOutcome(
                        descriptor = descriptor,
                        status = if (hits.isEmpty()) SourceStatus.NO_RESULTS else SourceStatus.SEARCHED,
                        hits = hits,
                        latencyMs = latency,
                        message = if (hits.isEmpty()) "0 structured listings matched" else "${hits.size} live listings"
                    )
                }
            } catch (e: Exception) {
                ConnectorOutcome(
                    descriptor = descriptor,
                    status = SourceStatus.UNAVAILABLE,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: "Request timed out"
                )
            }
        }
}

class StaticRegistryConnector(
    override val descriptor: SourceDescriptor,
    private val fixedStatus: SourceStatus,
    private val reasonMessage: String,
    private val requiredSecrets: List<String> = emptyList()
) : MarketplaceConnector {
    override suspend fun search(intent: ParsedSearchIntent, request: SearchQueryRequest): ConnectorOutcome {
        return ConnectorOutcome(
            descriptor = descriptor,
            status = fixedStatus,
            hits = emptyList(),
            latencyMs = 0L,
            message = reasonMessage,
            requiredSecretNames = requiredSecrets
        )
    }
}
