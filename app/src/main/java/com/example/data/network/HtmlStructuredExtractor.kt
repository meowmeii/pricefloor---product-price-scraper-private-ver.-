package com.example.data.network

import com.example.domain.model.ListingCondition
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class ExtractedJsonLdOffer(
    val title: String,
    val price: Double,
    val currency: String,
    val url: String,
    val imageUrl: String?,
    val condition: ListingCondition,
    val availability: String,
    val sellerName: String?
)

object HtmlStructuredExtractor {

    private val ldJsonRegex = Regex(
        """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val pricePattern = Regex("""([€£$]|EUR|GBP|USD)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""")

    fun extractJsonLdOffers(html: String, baseUrl: String, defaultCurrency: String): List<ExtractedJsonLdOffer> {
        val results = mutableListOf<ExtractedJsonLdOffer>()
        ldJsonRegex.findAll(html).forEach { match ->
            val rawJson = match.groupValues[1].trim()
            try {
                when {
                    rawJson.startsWith("{") -> parseJsonLdNode(JSONObject(rawJson), baseUrl, defaultCurrency, results)
                    rawJson.startsWith("[") -> {
                        val arr = JSONArray(rawJson)
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { parseJsonLdNode(it, baseUrl, defaultCurrency, results) }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore malformed JSON-LD blocks
            }
        }
        return results
    }

    private fun parseJsonLdNode(
        obj: JSONObject,
        baseUrl: String,
        defaultCurrency: String,
        out: MutableList<ExtractedJsonLdOffer>
    ) {
        val type = obj.optString("@type")
        if (type.equals("ItemList", ignoreCase = true)) {
            val items = obj.optJSONArray("itemListElement") ?: return
            for (i in 0 until items.length()) {
                val el = items.optJSONObject(i) ?: continue
                val inner = el.optJSONObject("item") ?: el
                parseJsonLdNode(inner, baseUrl, defaultCurrency, out)
            }
            return
        }

        if (obj.has("@graph")) {
            val graph = obj.optJSONArray("@graph")
            if (graph != null) {
                for (i in 0 until graph.length()) {
                    graph.optJSONObject(i)?.let { parseJsonLdNode(it, baseUrl, defaultCurrency, out) }
                }
            }
        }

        if (type.contains("Product", ignoreCase = true) || obj.has("offers")) {
            val name = decodeHtml(obj.optString("name")).trim()
            if (name.isBlank()) return
            val imageRaw = when (val img = obj.opt("image")) {
                is String -> img
                is JSONArray -> img.optString(0)
                is JSONObject -> img.optString("url")
                null -> null
                else -> null
            }
            val offersRaw = obj.opt("offers")
            val offerObjects = when (offersRaw) {
                is JSONObject -> listOf(offersRaw)
                is JSONArray -> (0 until offersRaw.length()).mapNotNull { offersRaw.optJSONObject(it) }
                null -> emptyList()
                else -> emptyList()
            }
            offerObjects.forEach { offer ->
                val price = parseDoubleSafe(offer.opt("price"))
                    ?: parseDoubleSafe(offer.opt("lowPrice"))
                    ?: return@forEach
                if (price <= 0.0) return@forEach
                val currency = offer.optString("priceCurrency").ifBlank { defaultCurrency }.uppercase()
                val rawUrl = offer.optString("url").ifBlank { obj.optString("url") }.ifBlank { baseUrl }
                val resolvedUrl = resolveUrl(baseUrl, rawUrl)
                val cond = ListingCondition.fromRaw(offer.optString("itemCondition").ifBlank { name })
                val avail = offer.optString("availability").substringAfterLast("/").ifBlank { "Check seller" }
                val seller = offer.optJSONObject("seller")?.optString("name")?.takeIf { it.isNotBlank() }
                out += ExtractedJsonLdOffer(
                    title = name,
                    price = price,
                    currency = currency,
                    url = resolvedUrl,
                    imageUrl = imageRaw?.takeIf { it.startsWith("http") },
                    condition = cond,
                    availability = avail,
                    sellerName = seller
                )
            }
        }
    }

    fun parsePriceWithCurrency(text: String, defaultCurrency: String): Pair<Double, String>? {
        val m = pricePattern.find(text.replace("\u00A0", " ")) ?: return null
        val sym = m.groupValues[1].uppercase()
        val num = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val currency = when (sym) {
            "€", "EUR" -> "EUR"
            "£", "GBP" -> "GBP"
            "$", "USD" -> "USD"
            else -> defaultCurrency
        }
        return num to currency
    }

    fun parseDoubleSafe(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.replace(",", "").replace("€", "").replace("£", "").replace("$", "").trim().toDoubleOrNull()
        else -> null
    }

    fun decodeHtml(input: String): String =
        input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun resolveUrl(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val origin = base.substringBefore("/", missingDelimiterValue = base).let {
            val schemeEnd = base.indexOf("://")
            if (schemeEnd >= 0) {
                val slash = base.indexOf('/', schemeEnd + 3)
                if (slash > 0) base.substring(0, slash) else base
            } else base
        }
        return if (href.startsWith("/")) "$origin$href" else "$origin/$href"
    }

    fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}
