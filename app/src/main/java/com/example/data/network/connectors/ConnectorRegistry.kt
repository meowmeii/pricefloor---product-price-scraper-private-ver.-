package com.example.data.network.connectors

import com.example.data.network.HtmlStructuredExtractor
import com.example.data.network.MarketplaceConnector
import com.example.data.network.RawMarketplaceHit
import com.example.domain.model.SourceCategory
import com.example.domain.model.SourceDescriptor
import com.example.domain.model.SourceStatus
import okhttp3.OkHttpClient

object ConnectorRegistry {

    fun buildAllConnectors(httpClient: OkHttpClient): List<MarketplaceConnector> = listOf(
        // --- 1. Live Public APIs (Always active, zero auth) ---
        CexConnector(httpClient, "IE"),
        CexConnector(httpClient, "UK"),
        ReverbConnector(httpClient),
        CheapSharkConnector(httpClient),
        PokemonTcgConnector(httpClient),
        ScryfallConnector(httpClient),
        GoogleBooksConnector(httpClient),
        UpcItemDbConnector(httpClient),
        ItunesStoreConnector(httpClient),

        // --- 2. Official / Key-Gated APIs (Live when configured, else transparent REQUIRES_AUTH) ---
        EbayConnector(httpClient),
        SerpApiShoppingConnector(
            httpClient = httpClient,
            engine = "google_shopping",
            descriptor = SourceDescriptor(
                id = "google_shopping",
                name = "Google Shopping",
                category = SourceCategory.DISCOVERY,
                regionLabel = "Localized",
                integrationMethod = "SerpApi Google Shopping Engine"
            )
        ),
        SerpApiShoppingConnector(
            httpClient = httpClient,
            engine = "bing_shopping",
            descriptor = SourceDescriptor(
                id = "bing_shopping",
                name = "Bing Shopping",
                category = SourceCategory.DISCOVERY,
                regionLabel = "Localized",
                integrationMethod = "SerpApi Bing Shopping Engine"
            )
        ),
        DiscogsConnector(httpClient),

        // --- 3. Ireland & UK Classifieds + Refurbished + Retailers + Rentals (Live HTTP + JSON-LD/HTML) ---
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "adverts_ie",
                name = "Adverts.ie",
                category = SourceCategory.GENERAL,
                regionLabel = "IE",
                integrationMethod = "Live Adverts.ie Search + HTML/JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.adverts.ie/for-sale/q_$q/" },
            defaultCurrency = "EUR",
            defaultCountryCode = "IE",
            defaultConditionRaw = "USED",
            customHtmlParser = ::parseAdvertsIeHtml
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "donedeal_ie",
                name = "DoneDeal.ie",
                category = SourceCategory.GENERAL,
                regionLabel = "IE",
                integrationMethod = "Live DoneDeal.ie Search + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.donedeal.ie/all?words=$q" },
            defaultCurrency = "EUR",
            defaultCountryCode = "IE",
            defaultConditionRaw = "USED"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "gumtree_uk",
                name = "Gumtree UK",
                category = SourceCategory.GENERAL,
                regionLabel = "UK",
                integrationMethod = "Live Gumtree Search + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.gumtree.com/search?search_category=all&q=$q" },
            defaultCurrency = "GBP",
            defaultCountryCode = "GB",
            defaultConditionRaw = "USED"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "backmarket",
                name = "Back Market",
                category = SourceCategory.TECH,
                regionLabel = "IE / EU",
                integrationMethod = "Live Back Market IE + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.backmarket.ie/en-ie/search?q=$q" },
            defaultCurrency = "EUR",
            defaultCountryCode = "IE",
            defaultConditionRaw = "REFURBISHED",
            knownShippingCost = 0.0
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "refurbed",
                name = "Refurbed.ie",
                category = SourceCategory.TECH,
                regionLabel = "IE / EU",
                integrationMethod = "Live Refurbed IE + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.refurbed.ie/s/?q=$q" },
            defaultCurrency = "EUR",
            defaultCountryCode = "IE",
            defaultConditionRaw = "REFURBISHED",
            knownShippingCost = 0.0
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "musicmagpie",
                name = "MusicMagpie",
                category = SourceCategory.TECH,
                regionLabel = "UK",
                integrationMethod = "Live MusicMagpie Store + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.musicmagpie.co.uk/store/search?keyword=$q" },
            defaultCurrency = "GBP",
            defaultCountryCode = "GB",
            defaultConditionRaw = "REFURBISHED"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "reebelo",
                name = "Reebelo",
                category = SourceCategory.TECH,
                regionLabel = "Global",
                integrationMethod = "Live Reebelo + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://reebelo.com/search?query=$q" },
            defaultCurrency = "USD",
            defaultCountryCode = "US",
            defaultConditionRaw = "REFURBISHED"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "currys_ie",
                name = "Currys Ireland",
                category = SourceCategory.TECH,
                regionLabel = "IE",
                integrationMethod = "Live Currys.ie + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.currys.ie/search?q=$q" },
            defaultCurrency = "EUR",
            defaultCountryCode = "IE",
            defaultConditionRaw = "NEW"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "scan_uk",
                name = "Scan Computers UK",
                category = SourceCategory.TECH,
                regionLabel = "UK",
                integrationMethod = "Live Scan.co.uk + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.scan.co.uk/search?q=$q" },
            defaultCurrency = "GBP",
            defaultCountryCode = "GB",
            defaultConditionRaw = "NEW"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "overclockers_uk",
                name = "Overclockers UK",
                category = SourceCategory.TECH,
                regionLabel = "UK",
                integrationMethod = "Live Overclockers.co.uk + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.overclockers.co.uk/search?sSearch=$q" },
            defaultCurrency = "GBP",
            defaultCountryCode = "GB",
            defaultConditionRaw = "NEW"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "grover",
                name = "Grover Tech Rentals",
                category = SourceCategory.RENTALS,
                regionLabel = "EU / DE / US",
                integrationMethod = "Live Grover Search + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://www.grover.com/de-en/search?q=$q" },
            defaultCurrency = "EUR",
            defaultCountryCode = "DE",
            defaultConditionRaw = "RENTAL",
            defaultAcquisitionRaw = "RENTAL"
        ),
        StructuredWebConnector(
            httpClient = httpClient,
            descriptor = SourceDescriptor(
                id = "fat_llama",
                name = "Fat Llama Rentals",
                category = SourceCategory.RENTALS,
                regionLabel = "UK / IE / US",
                integrationMethod = "Live Fat Llama Search + JSON-LD"
            ),
            searchUrlBuilder = { q -> "https://fatllama.com/search?q=$q" },
            defaultCurrency = "GBP",
            defaultCountryCode = "GB",
            defaultConditionRaw = "RENTAL",
            defaultAcquisitionRaw = "RENTAL"
        ),

        // --- 4. Transparently Declared Auth-Required & Unsupported Platforms ---
        StaticRegistryConnector(
            descriptor = SourceDescriptor("amazon", "Amazon", SourceCategory.GENERAL, "IE / UK / DE / US", "Amazon PA-API 5.0"),
            fixedStatus = SourceStatus.REQUIRES_AUTH,
            reasonMessage = "Requires Amazon Creators/PA-API 5.0 Partner credentials or SERPAPI_KEY (direct scraping prohibited by ToS)",
            requiredSecrets = listOf("SERPAPI_KEY")
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("aliexpress", "AliExpress", SourceCategory.GENERAL, "Global", "AliExpress Open Platform API"),
            fixedStatus = SourceStatus.REQUIRES_AUTH,
            reasonMessage = "Requires AliExpress Affiliate/Open Platform API key",
            requiredSecrets = listOf("SERPAPI_KEY")
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("etsy", "Etsy", SourceCategory.GENERAL, "Global", "Etsy Open API v3"),
            fixedStatus = SourceStatus.REQUIRES_AUTH,
            reasonMessage = "Requires Etsy Open API v3 x-api-key",
            requiredSecrets = listOf("SERPAPI_KEY")
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("bricklink", "BrickLink & BrickOwl", SourceCategory.SPECIALIST, "Global", "BrickLink OAuth 1.0a API"),
            fixedStatus = SourceStatus.REQUIRES_AUTH,
            reasonMessage = "Requires BrickLink/BrickOwl API consumer keys"
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("facebook_marketplace", "Facebook Marketplace", SourceCategory.GENERAL, "Local", "No Public API"),
            fixedStatus = SourceStatus.UNSUPPORTED,
            reasonMessage = "Unsupported: Meta provides no public search API and prohibits automated scraping"
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("vinted", "Vinted", SourceCategory.GENERAL, "IE / UK / EU", "No Public API"),
            fixedStatus = SourceStatus.UNSUPPORTED,
            reasonMessage = "Unsupported: No public search API; protected against automated scraping"
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("depop", "Depop", SourceCategory.GENERAL, "IE / UK / US", "No Public API"),
            fixedStatus = SourceStatus.UNSUPPORTED,
            reasonMessage = "Unsupported: No public search API; scraping prohibited"
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("temu", "Temu", SourceCategory.GENERAL, "Global", "Closed App API"),
            fixedStatus = SourceStatus.UNSUPPORTED,
            reasonMessage = "Unsupported: Closed platform API without public search access"
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("stockx_goat", "StockX & GOAT", SourceCategory.SPECIALIST, "Global", "Partner API Only"),
            fixedStatus = SourceStatus.UNSUPPORTED,
            reasonMessage = "Unsupported: Requires enterprise partner API approval"
        ),
        StaticRegistryConnector(
            descriptor = SourceDescriptor("argos", "Argos", SourceCategory.TECH, "UK", "No Public API"),
            fixedStatus = SourceStatus.UNSUPPORTED,
            reasonMessage = "Unsupported: No public search API (covered via Google Shopping when SERPAPI_KEY is set)"
        )
    )

    private fun parseAdvertsIeHtml(html: String, baseUrl: String): List<RawMarketplaceHit> {
        val hits = mutableListOf<RawMarketplaceHit>()
        // Adverts.ie embeds item links with data attributes or title + price blocks
        val cardRegex = Regex(
            """href=["'](/[\w-]+/[\w-]+/\d+)["'][^>]*>.*?title=["']([^"']{4,180})["'].*?€\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val seenUrls = mutableSetOf<String>()
        cardRegex.findAll(html).take(15).forEach { m ->
            val path = m.groupValues[1]
            val title = HtmlStructuredExtractor.decodeHtml(m.groupValues[2])
            val price = m.groupValues[3].replace(",", "").toDoubleOrNull() ?: return@forEach
            val fullUrl = "https://www.adverts.ie$path"
            if (price > 0.0 && seenUrls.add(fullUrl)) {
                hits += RawMarketplaceHit(
                    title = title,
                    marketplaceId = "adverts_ie",
                    marketplaceName = "Adverts.ie",
                    sellerName = "Adverts.ie Seller",
                    conditionRaw = "USED",
                    price = price,
                    currency = "EUR",
                    shippingCost = null, // Collection or seller-agreed postage
                    sourceCountryCode = "IE",
                    locationText = "Ireland (Adverts.ie)",
                    availabilityText = "Listed",
                    sourceUrl = fullUrl
                )
            }
        }
        return hits
    }
}
