package com.example.data.repository

import com.example.data.ai.GeminiSearchAssistant
import com.example.data.network.ConnectorOutcome
import com.example.data.network.CurrencyConverter
import com.example.data.network.ProductMatchingEngine
import com.example.data.network.RawMarketplaceHit
import com.example.data.network.connectors.ConnectorRegistry
import com.example.domain.model.LiveSearchSnapshot
import com.example.domain.model.ParsedSearchIntent
import com.example.domain.model.SearchQueryRequest
import com.example.domain.model.SourceStatus
import com.example.domain.model.SourceTelemetry
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LiveSearchOrchestrator(
    private val geminiAssistant: GeminiSearchAssistant
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val currencyConverter = CurrencyConverter(httpClient)
    private val matchingEngine = ProductMatchingEngine(currencyConverter)
    private val connectors = ConnectorRegistry.buildAllConnectors(httpClient)

    fun executeLiveSearch(request: SearchQueryRequest): Flow<LiveSearchSnapshot> = channelFlow {
        val startedAt = System.currentTimeMillis()
        val initialIntent = ParsedSearchIntent(
            originalQuery = request.rawQuery,
            canonicalProductName = request.rawQuery.trim()
        )
        val initialTelemetry = connectors.map { c ->
            SourceTelemetry(
                descriptor = c.descriptor,
                status = SourceStatus.SEARCHING,
                message = "Queued..."
            )
        }

        send(
            LiveSearchSnapshot(
                request = request,
                intent = initialIntent,
                listings = emptyList(),
                telemetry = initialTelemetry,
                currencySnapshot = null,
                isSearching = true,
                startedAtMillis = startedAt,
                completedAtMillis = null
            )
        )

        // Step 1: Fetch live FX rates + Gemini query understanding in parallel
        val (rates, parsedIntent) = coroutineScope {
            val fxDeferred = async { currencyConverter.fetchLiveRates() }
            val aiDeferred = async {
                geminiAssistant.parseAndExpandQuery(request.rawQuery, request.countryCode)
            }
            fxDeferred.await() to aiDeferred.await()
        }

        val effectiveRequest = request.copy(
            countryCode = parsedIntent.targetCountryHint ?: request.countryCode,
            rentalDurationDays = parsedIntent.rentalDurationDaysHint ?: request.rentalDurationDays
        )

        val telemetryMap = LinkedHashMap<String, SourceTelemetry>()
        connectors.forEach { c ->
            telemetryMap[c.descriptor.id] = SourceTelemetry(
                descriptor = c.descriptor,
                status = SourceStatus.SEARCHING,
                message = "Querying live..."
            )
        }

        val accumulatedHits = mutableListOf<RawMarketplaceHit>()

        send(
            LiveSearchSnapshot(
                request = effectiveRequest,
                intent = parsedIntent,
                listings = emptyList(),
                telemetry = telemetryMap.values.toList(),
                currencySnapshot = rates,
                isSearching = true,
                startedAtMillis = startedAt,
                completedAtMillis = null
            )
        )

        // Step 2: Execute every connector concurrently in a supervisorScope with per-connector timeout
        val outcomeChannel = Channel<ConnectorOutcome>(Channel.UNLIMITED)

        supervisorScope {
            connectors.forEach { connector ->
                launch {
                    val outcome = withTimeoutOrNull(12_000L) {
                        connector.search(parsedIntent, effectiveRequest)
                    } ?: ConnectorOutcome(
                        descriptor = connector.descriptor,
                        status = SourceStatus.UNAVAILABLE,
                        hits = emptyList(),
                        latencyMs = 12_000L,
                        message = "Timed out after 12s"
                    )
                    outcomeChannel.send(outcome)
                }
            }

            var completed = 0
            while (completed < connectors.size) {
                val outcome = outcomeChannel.receive()
                completed++
                telemetryMap[outcome.descriptor.id] = outcome.toTelemetry()
                if (outcome.hits.isNotEmpty()) {
                    accumulatedHits.addAll(outcome.hits)
                }

                val ranked = if (accumulatedHits.isNotEmpty()) {
                    matchingEngine.normalizeAndRank(
                        rawHits = accumulatedHits.toList(),
                        intent = parsedIntent,
                        request = effectiveRequest,
                        rates = rates
                    )
                } else emptyList()

                val finishedAll = completed >= connectors.size
                send(
                    LiveSearchSnapshot(
                        request = effectiveRequest,
                        intent = parsedIntent,
                        listings = ranked,
                        telemetry = telemetryMap.values.toList(),
                        currencySnapshot = rates,
                        isSearching = !finishedAll,
                        startedAtMillis = startedAt,
                        completedAtMillis = if (finishedAll) System.currentTimeMillis() else null
                    )
                )
            }
        }
        outcomeChannel.close()
    }
}
