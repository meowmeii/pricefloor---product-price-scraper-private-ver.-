package com.example.data.network.ebay

import com.example.BuildConfig
import com.example.data.network.RawMarketplaceHit
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Retrofit interface for the eBay Finding API v1.
 * Documentation: https://developer.ebay.com/Devzone/finding/CallRef/findItemsByKeywords.html
 */
interface EbayFindingApi {

    @GET("services/search/FindingService/v1")
    suspend fun findItemsByKeywords(
        @Query("OPERATION-NAME") operationName: String = "findItemsByKeywords",
        @Query("SERVICE-VERSION") serviceVersion: String = "1.13.0",
        @Query("SECURITY-APPNAME") securityAppName: String,
        @Query("GLOBAL-ID") globalId: String = "EBAY-IE",
        @Query("RESPONSE-DATA-FORMAT") responseFormat: String = "JSON",
        @Query("REST-PAYLOAD") restPayload: String = "",
        @Query("keywords") keywords: String,
        @Query("paginationInput.entriesPerPage") entriesPerPage: Int = 20,
        @Query("sortOrder") sortOrder: String = "PricePlusShippingLowest"
    ): Response<EbayFindingResponseEnvelope>
}

@JsonClass(generateAdapter = true)
data class EbayFindingResponseEnvelope(
    @Json(name = "findItemsByKeywordsResponse")
    val findItemsByKeywordsResponse: List<EbayFindingResponseBlock>? = null
)

@JsonClass(generateAdapter = true)
data class EbayFindingResponseBlock(
    @Json(name = "ack")
    val ack: List<String>? = null,
    @Json(name = "searchResult")
    val searchResult: List<EbaySearchResultBlock>? = null,
    @Json(name = "errorMessage")
    val errorMessage: List<EbayErrorMessageBlock>? = null
)

@JsonClass(generateAdapter = true)
data class EbayErrorMessageBlock(
    @Json(name = "error")
    val error: List<EbayErrorDetail>? = null
)

@JsonClass(generateAdapter = true)
data class EbayErrorDetail(
    @Json(name = "errorId")
    val errorId: List<String>? = null,
    @Json(name = "message")
    val message: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class EbaySearchResultBlock(
    @Json(name = "@count")
    val count: String? = null,
    @Json(name = "item")
    val items: List<EbayFindingItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class EbayFindingItemDto(
    @Json(name = "itemId")
    val itemId: List<String>? = null,
    @Json(name = "title")
    val title: List<String>? = null,
    @Json(name = "globalId")
    val globalId: List<String>? = null,
    @Json(name = "galleryURL")
    val galleryUrl: List<String>? = null,
    @Json(name = "viewItemURL")
    val viewItemUrl: List<String>? = null,
    @Json(name = "location")
    val location: List<String>? = null,
    @Json(name = "country")
    val country: List<String>? = null,
    @Json(name = "condition")
    val condition: List<EbayConditionBlock>? = null,
    @Json(name = "listingInfo")
    val listingInfo: List<EbayListingInfoBlock>? = null,
    @Json(name = "sellingStatus")
    val sellingStatus: List<EbaySellingStatusBlock>? = null,
    @Json(name = "shippingInfo")
    val shippingInfo: List<EbayShippingInfoBlock>? = null
)

@JsonClass(generateAdapter = true)
data class EbayConditionBlock(
    @Json(name = "conditionDisplayName")
    val conditionDisplayName: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class EbayListingInfoBlock(
    @Json(name = "listingType")
    val listingType: List<String>? = null,
    @Json(name = "buyItNowAvailable")
    val buyItNowAvailable: List<String>? = null,
    @Json(name = "buyItNowPrice")
    val buyItNowPrice: List<EbayAmountDto>? = null,
    @Json(name = "endTime")
    val endTime: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class EbaySellingStatusBlock(
    @Json(name = "currentPrice")
    val currentPrice: List<EbayAmountDto>? = null,
    @Json(name = "convertedCurrentPrice")
    val convertedCurrentPrice: List<EbayAmountDto>? = null,
    @Json(name = "bidCount")
    val bidCount: List<String>? = null,
    @Json(name = "sellingState")
    val sellingState: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class EbayShippingInfoBlock(
    @Json(name = "shippingServiceCost")
    val shippingServiceCost: List<EbayAmountDto>? = null,
    @Json(name = "shippingType")
    val shippingType: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class EbayAmountDto(
    @Json(name = "@currencyId")
    val currencyId: String? = null,
    @Json(name = "__value__")
    val value: String? = null
)

/**
 * Clean domain model returned by [EbayFindingService].
 */
data class EbayFindingItem(
    val itemId: String,
    val title: String,
    val price: Double,
    val currency: String,
    val shippingCost: Double?, // null when Calculated / NotSpecified
    val shippingType: String?,
    val conditionName: String,
    val listingType: String,
    val isAuction: Boolean,
    val bidCount: Int?,
    val buyItNowPrice: Double?,
    val endTime: String?,
    val countryCode: String,
    val location: String,
    val itemUrl: String,
    val imageUrl: String?
) {
    fun toRawMarketplaceHit(): RawMarketplaceHit = RawMarketplaceHit(
        title = title,
        marketplaceId = "ebay",
        marketplaceName = "eBay",
        sellerName = "eBay Seller",
        conditionRaw = if (isAuction) "AUCTION" else conditionName,
        acquisitionTypeRaw = if (isAuction) "AUCTION" else "PURCHASE",
        price = price,
        currency = currency,
        shippingCost = shippingCost,
        sourceCountryCode = countryCode,
        locationText = location,
        availabilityText = if (isAuction) "Active Auction" else "Available",
        listingDateText = endTime?.take(10),
        sourceUrl = itemUrl,
        imageUrl = imageUrl,
        auctionCurrentBid = if (isAuction) price else null,
        auctionBidCount = if (isAuction) bidCount else null,
        auctionTimeRemaining = if (isAuction) endTime else null,
        auctionBuyItNow = if (isAuction) buyItNowPrice else null
    )
}

sealed interface EbayFindingOutcome {
    data class Success(val items: List<EbayFindingItem>) : EbayFindingOutcome
    data class MissingCredentials(val message: String) : EbayFindingOutcome
    data class Error(val message: String, val httpCode: Int? = null) : EbayFindingOutcome
}

/**
 * Standalone service encapsulating Retrofit + OkHttp execution for the eBay Finding API.
 */
class EbayFindingService(
    private val api: EbayFindingApi,
    private val appIdProvider: () -> String = { BuildConfig.EBAY_CLIENT_ID }
) {

    fun hasValidCredentials(): Boolean {
        val appId = appIdProvider().trim()
        return appId.isNotBlank() && !appId.startsWith("MY_")
    }

    suspend fun findItems(
        keywords: String,
        countryCode: String = "IE",
        entriesPerPage: Int = 20,
        sortOrder: String = "PricePlusShippingLowest"
    ): EbayFindingOutcome = withContext(Dispatchers.IO) {
        val appId = appIdProvider().trim()
        if (appId.isBlank() || appId.startsWith("MY_")) {
            return@withContext EbayFindingOutcome.MissingCredentials(
                "Configure EBAY_CLIENT_ID (App ID) in the Secrets panel to query the eBay Finding API."
            )
        }
        val cleanKeywords = keywords.trim()
        if (cleanKeywords.isBlank()) {
            return@withContext EbayFindingOutcome.Error("Search keywords must not be blank.")
        }

        try {
            val response = api.findItemsByKeywords(
                securityAppName = appId,
                globalId = mapCountryToGlobalId(countryCode),
                keywords = cleanKeywords,
                entriesPerPage = entriesPerPage.coerceIn(1, 100),
                sortOrder = sortOrder
            )
            if (!response.isSuccessful) {
                return@withContext EbayFindingOutcome.Error(
                    message = "eBay Finding API HTTP ${response.code()}: ${response.message()}",
                    httpCode = response.code()
                )
            }

            val block = response.body()?.findItemsByKeywordsResponse?.firstOrNull()
                ?: return@withContext EbayFindingOutcome.Error("Malformed eBay Finding API response envelope.")

            val ack = block.ack?.firstOrNull().orEmpty()
            if (ack.equals("Failure", ignoreCase = true)) {
                val errMsg = block.errorMessage?.firstOrNull()?.error?.firstOrNull()?.message?.firstOrNull()
                    ?: "eBay Finding API returned Ack=Failure"
                return@withContext EbayFindingOutcome.Error(errMsg)
            }

            val dtos = block.searchResult?.firstOrNull()?.items.orEmpty()
            val mapped = dtos.mapNotNull { dto -> mapDtoToDomain(dto, countryCode) }
            EbayFindingOutcome.Success(mapped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EbayFindingOutcome.Error(e.localizedMessage ?: "eBay Finding API network failure")
        }
    }

    suspend fun findItemsResult(
        keywords: String,
        countryCode: String = "IE",
        entriesPerPage: Int = 20,
        sortOrder: String = "PricePlusShippingLowest"
    ): Result<List<EbayFindingItem>> = when (val outcome = findItems(keywords, countryCode, entriesPerPage, sortOrder)) {
        is EbayFindingOutcome.Success -> Result.success(outcome.items)
        is EbayFindingOutcome.MissingCredentials -> Result.failure(IllegalStateException(outcome.message))
        is EbayFindingOutcome.Error -> Result.failure(IllegalStateException(outcome.message))
    }

    private fun mapDtoToDomain(dto: EbayFindingItemDto, defaultCountry: String): EbayFindingItem? {
        val title = dto.title?.firstOrNull()?.trim().orEmpty()
        val url = dto.viewItemUrl?.firstOrNull()?.trim().orEmpty()
        if (title.isBlank() || url.isBlank()) return null

        val selling = dto.sellingStatus?.firstOrNull()
        val priceObj = selling?.currentPrice?.firstOrNull()
            ?: selling?.convertedCurrentPrice?.firstOrNull()
        val price = priceObj?.value?.toDoubleOrNull() ?: return null
        if (price <= 0.0) return null
        val currency = priceObj.currencyId?.uppercase()?.ifBlank { "EUR" } ?: "EUR"

        val shippingBlock = dto.shippingInfo?.firstOrNull()
        val shippingType = shippingBlock?.shippingType?.firstOrNull()
        val shippingCost = when {
            shippingType.equals("Free", ignoreCase = true) ||
                shippingType.equals("FreePickup", ignoreCase = true) -> 0.0
            else -> shippingBlock?.shippingServiceCost?.firstOrNull()?.value?.toDoubleOrNull()
        }

        val listingInfo = dto.listingInfo?.firstOrNull()
        val listingType = listingInfo?.listingType?.firstOrNull().orEmpty().ifBlank { "FixedPrice" }
        val isAuction = listingType.contains("Auction", ignoreCase = true) &&
            !listingType.equals("FixedPrice", ignoreCase = true)
        val bidCount = selling?.bidCount?.firstOrNull()?.toIntOrNull()
        val buyItNow = listingInfo?.buyItNowPrice?.firstOrNull()?.value?.toDoubleOrNull()
        val endTime = listingInfo?.endTime?.firstOrNull()

        val condition = dto.condition?.firstOrNull()?.conditionDisplayName?.firstOrNull() ?: "Used"
        val country = dto.country?.firstOrNull()?.uppercase()?.ifBlank { defaultCountry } ?: defaultCountry
        val location = dto.location?.firstOrNull()?.ifBlank { country } ?: country
        val itemId = dto.itemId?.firstOrNull().orEmpty()
        val imageUrl = dto.galleryUrl?.firstOrNull()?.takeIf { it.startsWith("http") }

        return EbayFindingItem(
            itemId = itemId,
            title = title,
            price = price,
            currency = currency,
            shippingCost = shippingCost,
            shippingType = shippingType,
            conditionName = condition,
            listingType = listingType,
            isAuction = isAuction,
            bidCount = bidCount,
            buyItNowPrice = buyItNow,
            endTime = endTime,
            countryCode = country,
            location = location,
            itemUrl = url,
            imageUrl = imageUrl
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://svcs.ebay.com/"

        fun mapCountryToGlobalId(countryCode: String): String = when (countryCode.uppercase()) {
            "IE" -> "EBAY-IE"
            "GB" -> "EBAY-GB"
            "DE" -> "EBAY-DE"
            "FR" -> "EBAY-FR"
            "CA" -> "EBAY-ENCA"
            "AU" -> "EBAY-AU"
            else -> "EBAY-US"
        }

        fun create(
            baseClient: OkHttpClient? = null,
            baseUrl: String = DEFAULT_BASE_URL,
            appIdProvider: () -> String = { BuildConfig.EBAY_CLIENT_ID }
        ): EbayFindingService {
            val headerInterceptor = Interceptor { chain ->
                val appId = appIdProvider().trim()
                val reqBuilder = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "PriceFloorAndroid/1.0")
                if (appId.isNotBlank() && !appId.startsWith("MY_")) {
                    reqBuilder.header("X-EBAY-SOA-SECURITY-APPNAME", appId)
                }
                chain.proceed(reqBuilder.build())
            }

            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            }

            val okHttpBuilder = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
            okHttpBuilder.interceptors().add(0, headerInterceptor)
            val okHttp = okHttpBuilder
                .addInterceptor(loggingInterceptor)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttp)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            return EbayFindingService(
                api = retrofit.create(EbayFindingApi::class.java),
                appIdProvider = appIdProvider
            )
        }
    }
}
