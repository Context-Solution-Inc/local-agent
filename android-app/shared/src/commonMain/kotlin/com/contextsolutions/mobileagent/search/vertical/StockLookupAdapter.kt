package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * STOCKS vertical adapter. Resolves a company name to a ticker via
 * stockanalysis.com's search API, then fetches that ticker's page and extracts
 * readable text for Gemma to summarise.
 *
 * Unlike [FeedAdapter] this uses a FIXED endpoint, so it ignores [prefs] /
 * [location] / [gps] (STOCKS has no user-editable source list — see
 * [com.contextsolutions.mobileagent.search.SearchSubtype.STOCKS]).
 *
 * Flow (all on `Dispatchers.IO`, invariant #1):
 *  1. Strip price/quote/question stopwords from the query to isolate the
 *     company/ticker entity (or take an explicit `$TICKER` verbatim).
 *  2. `GET /api/search?q=<entity>` → pick the first stock (`t == "s"`) result,
 *     else the first result of any type.
 *  3. Build `/stocks/<symbol>/` (or `/etf/<symbol>/` for ETFs).
 *  4. `GET` that page → [HtmlReadabilityExtractor] → snippet.
 *
 * Any miss (no entity, empty search results, blank page, network failure)
 * returns [SearchOutcome.Error]; the agent loop surfaces it as context the LLM
 * can explain ("couldn't find that stock") rather than crashing.
 */
class StockLookupAdapter(
    private val httpClient: HttpClient,
    private val readability: HtmlReadabilityExtractor = HtmlReadabilityExtractor(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val logger: (String) -> Unit = {},
) : VerticalSearchAdapter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val entity = extractEntity(query)
        if (entity.isBlank()) {
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.BadResponse,
                "could not isolate a company or ticker from the query",
            )
        }
        logger("[vertical:STOCKS] entity=\"$entity\" (from query=\"$query\")")

        val hit = try {
            val body = httpClient.get("$baseUrl/api/search") {
                parameter("q", entity)
            }.bodyAsText()
            val parsed = json.decodeFromString(SearchResponse.serializer(), body)
            parsed.data.firstOrNull { it.t == TYPE_STOCK && it.s.isNotBlank() }
                ?: parsed.data.firstOrNull { it.s.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger("[vertical:STOCKS] symbol search failed: ${t.message ?: t::class.simpleName}")
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.Network,
                "stock symbol lookup failed: ${t.message ?: t::class.simpleName}",
            )
        }

        if (hit == null) {
            logger("[vertical:STOCKS] no symbol match for \"$entity\"")
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.BadResponse,
                "no ticker found for \"$entity\"",
            )
        }

        val symbol = hit.s.lowercase()
        val pathSegment = if (hit.t == TYPE_ETF) "etf" else "stocks"
        val pageUrl = "$baseUrl/$pathSegment/$symbol/"
        logger("[vertical:STOCKS] resolved ${hit.n} -> ${hit.s} (t=${hit.t}) url=$pageUrl")

        val snippet = try {
            val page = httpClient.get(pageUrl).bodyAsText()
            logger("[vertical:STOCKS] $pageUrl bodyLen=${page.length}")
            readability.extract(page).take(PER_SOURCE_CHAR_CAP)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger("[vertical:STOCKS] page fetch failed for $pageUrl: ${t.message ?: t::class.simpleName}")
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.Network,
                "failed to fetch ${hit.s} page: ${t.message ?: t::class.simpleName}",
            )
        }

        if (snippet.isBlank()) {
            logger("[vertical:STOCKS] blank extract from $pageUrl")
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.BadResponse,
                "no readable content extracted from ${hit.s} page",
            )
        }

        val title = "${hit.n} (${hit.s})"
        val structured = buildJsonObject {
            put("subtype", "stocks")
            put("query", query)
            put("symbol", hit.s)
            put("name", hit.n)
            put("url", pageUrl)
            put("snippet", snippet)
        }
        val payload = FormattedSearchPayload(
            json = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), structured),
            sources = listOf(SearchSource(title = title, url = pageUrl, snippet = snippet)),
        )
        SearchOutcome.Success(payload, fromCache = false)
    }

    /**
     * Isolate the company/ticker text from a natural-language stock query. An
     * explicit `$TICKER` wins outright; otherwise drop price/quote/question
     * stopwords and possessive suffixes, leaving the entity for the (fuzzy)
     * search API. Falls back to the raw query if stripping leaves nothing.
     */
    private fun extractEntity(query: String): String {
        TICKER_PREFIX.find(query)?.let { return it.groupValues[1].uppercase() }
        val lower = query.lowercase()
        val kept = TOKEN_SPLIT.split(lower)
            .map { it.removeSuffix("'s").removeSuffix("’s") }
            .filter { it.isNotBlank() && it !in STOPWORDS }
        val joined = kept.joinToString(" ").trim()
        return joined.ifBlank { query.trim() }
    }

    @Serializable
    private data class SearchResponse(
        val status: Int = 0,
        val data: List<SearchHit> = emptyList(),
    )

    @Serializable
    private data class SearchHit(
        val s: String = "",
        val t: String = "",
        val n: String = "",
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://stockanalysis.com"
        const val TYPE_STOCK = "s"
        const val TYPE_ETF = "e"
        // Match FeedAdapter's per-source budget (~600 tokens).
        const val PER_SOURCE_CHAR_CAP = 2400

        val TICKER_PREFIX = Regex("""\$([a-zA-Z]{1,5})\b""")
        val TOKEN_SPLIT = Regex("""[^a-z0-9.’']+""")

        // Price/quote/question vocabulary and determiners — everything that
        // isn't part of the company name. The remainder is handed to the
        // fuzzy search API.
        val STOPWORDS = setOf(
            "what", "whats", "what's", "is", "are", "the", "a", "an", "of", "for",
            "how", "much", "many", "does", "do", "tell", "me", "show", "get",
            "current", "currently", "today", "todays", "now", "right", "latest",
            "price", "prices", "stock", "stocks", "share", "shares", "shareprice",
            "quote", "quotes", "ticker", "symbol", "value", "worth", "trading",
            "trade", "market", "cap", "marketcap", "pe", "p", "e", "ratio",
            "earnings", "dividend", "dividends", "target", "on", "about", "at",
            "to", "in", "and", "s",
        )
    }
}
