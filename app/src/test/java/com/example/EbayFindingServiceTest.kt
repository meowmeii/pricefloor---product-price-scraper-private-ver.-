package com.example.data.network.ebay

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbayFindingServiceTest {

    @Test
    fun `missing or placeholder credentials return MissingCredentials without making HTTP calls`() = runBlocking {
        var networkCalled = false
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCalled = true
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Should not be called")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = EbayFindingService.create(
            baseClient = client,
            appIdProvider = { "MY_EBAY_CLIENT_ID" }
        )

        assertFalse(service.hasValidCredentials())
        val outcome = service.findItems("iPhone 15 Pro", countryCode = "IE")
        assertTrue(outcome is EbayFindingOutcome.MissingCredentials)
        assertFalse(networkCalled)
    }

    @Test
    fun `Retrofit and Moshi query Finding API with credentials and parse fixed price, auction, and shipping`() = runBlocking {
        var capturedUrl = ""
        var capturedAppHeader: String? = null

        val mockJson = """
            {
              "findItemsByKeywordsResponse": [
                {
                  "ack": ["Success"],
                  "searchResult": [
                    {
                      "@count": "3",
                      "item": [
                        {
                          "itemId": ["1110001"],
                          "title": ["Apple iPhone 15 Pro 256GB Natural Titanium"],
                          "globalId": ["EBAY-IE"],
                          "galleryURL": ["https://i.ebayimg.com/thumbs/1.jpg"],
                          "viewItemURL": ["https://www.ebay.ie/itm/1110001"],
                          "location": ["Dublin, Ireland"],
                          "country": ["IE"],
                          "condition": [{"conditionDisplayName": ["Used"]}],
                          "listingInfo": [{"listingType": ["FixedPrice"]}],
                          "sellingStatus": [{
                            "currentPrice": [{"@currencyId": "EUR", "__value__": "749.00"}],
                            "sellingState": ["Active"]
                          }],
                          "shippingInfo": [{
                            "shippingType": ["Free"],
                            "shippingServiceCost": [{"@currencyId": "EUR", "__value__": "0.0"}]
                          }]
                        },
                        {
                          "itemId": ["2220002"],
                          "title": ["Apple iPhone 15 Pro 256GB Blue Auction"],
                          "globalId": ["EBAY-IE"],
                          "viewItemURL": ["https://www.ebay.ie/itm/2220002"],
                          "location": ["Cork, Ireland"],
                          "country": ["IE"],
                          "condition": [{"conditionDisplayName": ["Open box"]}],
                          "listingInfo": [{
                            "listingType": ["AuctionWithBIN"],
                            "buyItNowPrice": [{"@currencyId": "EUR", "__value__": "799.00"}],
                            "endTime": ["2026-11-25T18:00:00.000Z"]
                          }],
                          "sellingStatus": [{
                            "currentPrice": [{"@currencyId": "EUR", "__value__": "520.00"}],
                            "bidCount": ["12"],
                            "sellingState": ["Active"]
                          }],
                          "shippingInfo": [{
                            "shippingType": ["Flat"],
                            "shippingServiceCost": [{"@currencyId": "EUR", "__value__": "12.50"}]
                          }]
                        },
                        {
                          "itemId": ["3330003"],
                          "title": ["Apple iPhone 15 Pro 256GB Calculated Ship"],
                          "viewItemURL": ["https://www.ebay.ie/itm/3330003"],
                          "country": ["GB"],
                          "listingInfo": [{"listingType": ["FixedPrice"]}],
                          "sellingStatus": [{
                            "currentPrice": [{"@currencyId": "GBP", "__value__": "610.00"}]
                          }],
                          "shippingInfo": [{
                            "shippingType": ["Calculated"]
                          }]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedUrl = req.url.toString()
                capturedAppHeader = req.header("X-EBAY-SOA-SECURITY-APPNAME")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val service = EbayFindingService.create(
            baseClient = client,
            appIdProvider = { "TestAppId-12345" }
        )

        assertTrue(service.hasValidCredentials())
        val outcome = service.findItems(
            keywords = "iPhone 15 Pro 256GB",
            countryCode = "IE",
            entriesPerPage = 15
        )

        assertTrue(outcome is EbayFindingOutcome.Success)
        val items = (outcome as EbayFindingOutcome.Success).items
        assertEquals(3, items.size)

        // Verify query parameters and security header injected via Retrofit + OkHttp
        assertTrue(capturedUrl.contains("services/search/FindingService/v1"))
        assertTrue(capturedUrl.contains("OPERATION-NAME=findItemsByKeywords"))
        assertTrue(capturedUrl.contains("SECURITY-APPNAME=TestAppId-12345"))
        assertTrue(capturedUrl.contains("GLOBAL-ID=EBAY-IE"))
        assertTrue(capturedUrl.contains("RESPONSE-DATA-FORMAT=JSON"))
        assertEquals("TestAppId-12345", capturedAppHeader)

        // Item 1: FixedPrice + Free shipping
        assertEquals(749.0, items[0].price, 0.001)
        assertEquals("EUR", items[0].currency)
        assertEquals(0.0, items[0].shippingCost!!, 0.001)
        assertFalse(items[0].isAuction)

        // Item 2: AuctionWithBIN -> RawMarketplaceHit preserves bids & Buy It Now
        assertTrue(items[1].isAuction)
        assertEquals(12, items[1].bidCount)
        assertEquals(799.0, items[1].buyItNowPrice!!, 0.001)
        assertEquals(12.50, items[1].shippingCost!!, 0.001)
        val auctionRaw = items[1].toRawMarketplaceHit()
        assertEquals("AUCTION", auctionRaw.acquisitionTypeRaw)
        assertEquals(520.0, auctionRaw.auctionCurrentBid!!, 0.001)

        // Item 3: Calculated shipping -> null (unknown)
        assertNull(items[2].shippingCost)
        assertEquals("GBP", items[2].currency)
    }

    @Test
    fun `eBay Ack Failure message is surfaced cleanly as EbayFindingOutcome Error`() = runBlocking {
        val failureJson = """
            {
              "findItemsByKeywordsResponse": [
                {
                  "ack": ["Failure"],
                  "errorMessage": [
                    {
                      "error": [
                        {
                          "errorId": ["10001"],
                          "message": ["Invalid application ID."]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(failureJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = EbayFindingService.create(
            baseClient = client,
            appIdProvider = { "BadAppId" }
        )

        val outcome = service.findItems("RTX 4070", countryCode = "GB")
        assertTrue(outcome is EbayFindingOutcome.Error)
        assertEquals("Invalid application ID.", (outcome as EbayFindingOutcome.Error).message)
    }
}
