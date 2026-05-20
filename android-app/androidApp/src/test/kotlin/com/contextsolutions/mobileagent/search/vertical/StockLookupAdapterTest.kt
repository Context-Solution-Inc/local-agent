package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.SearchOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockLookupAdapterTest {

    private val baseUrl = "https://stockanalysis.com"

    private fun adapterWith(
        searchJson: String,
        pageHtml: String = DEFAULT_PAGE_HTML,
        record: MutableList<String>? = null,
    ): StockLookupAdapter {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            record?.add(url)
            if (url.contains("/api/search")) {
                respond(searchJson, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond(pageHtml, HttpStatusCode.OK, htmlHeaders)
            }
        }
        return StockLookupAdapter(httpClient = HttpClient(engine), baseUrl = baseUrl)
    }

    @Test
    fun resolves_company_to_ticker_and_builds_stocks_url() = runTest {
        val requests = mutableListOf<String>()
        val adapter = adapterWith(
            searchJson = """{"status":200,"data":[{"id":"NVDA","s":"NVDA","t":"s","n":"NVIDIA Corporation"}]}""",
            record = requests,
        )

        val outcome = adapter.fetch("what's nvidia's stock price", VerticalPreferences(), null, null)

        assertTrue("expected Success, got $outcome", outcome is SearchOutcome.Success)
        val source = (outcome as SearchOutcome.Success).payload.sources.single()
        assertEquals("https://stockanalysis.com/stocks/nvda/", source.url)
        assertEquals("NVIDIA Corporation (NVDA)", source.title)
        assertTrue("snippet should carry page text", source.snippet.contains("NVIDIA", ignoreCase = true))
        // Entity extraction strips "what's"/"stock"/"price" and the possessive,
        // leaving "nvidia" for the search API.
        assertTrue(
            "search request should query the extracted entity; got $requests",
            requests.any { it.contains("/api/search") && it.contains("q=nvidia") },
        )
    }

    @Test
    fun explicit_dollar_ticker_is_used_verbatim() = runTest {
        val requests = mutableListOf<String>()
        val adapter = adapterWith(
            searchJson = """{"status":200,"data":[{"id":"TSLA","s":"TSLA","t":"s","n":"Tesla, Inc."}]}""",
            record = requests,
        )

        val outcome = adapter.fetch("how is \$TSLA doing", VerticalPreferences(), null, null)

        assertTrue(outcome is SearchOutcome.Success)
        assertTrue(
            "explicit \$TICKER should be queried verbatim; got $requests",
            requests.any { it.contains("q=TSLA") },
        )
    }

    @Test
    fun prefers_stock_result_over_etf() = runTest {
        // ETF listed first; adapter must still pick the t=="s" stock.
        val adapter = adapterWith(
            searchJson = """{"status":200,"data":[
                {"id":"NVDX","s":"NVDX","t":"e","n":"T-Rex 2X Long NVIDIA Daily Target ETF"},
                {"id":"NVDA","s":"NVDA","t":"s","n":"NVIDIA Corporation"}
            ]}""",
        )

        val outcome = adapter.fetch("nvidia stock", VerticalPreferences(), null, null)

        assertTrue(outcome is SearchOutcome.Success)
        assertEquals(
            "https://stockanalysis.com/stocks/nvda/",
            (outcome as SearchOutcome.Success).payload.sources.single().url,
        )
    }

    @Test
    fun etf_only_result_builds_etf_url() = runTest {
        val adapter = adapterWith(
            searchJson = """{"status":200,"data":[{"id":"SPY","s":"SPY","t":"e","n":"SPDR S&P 500 ETF Trust"}]}""",
        )

        val outcome = adapter.fetch("spy share price", VerticalPreferences(), null, null)

        assertTrue(outcome is SearchOutcome.Success)
        assertEquals(
            "https://stockanalysis.com/etf/spy/",
            (outcome as SearchOutcome.Success).payload.sources.single().url,
        )
    }

    @Test
    fun empty_search_results_return_error() = runTest {
        val adapter = adapterWith(searchJson = """{"status":200,"data":[]}""")

        val outcome = adapter.fetch("zzzznotacompany stock price", VerticalPreferences(), null, null)

        assertTrue("expected Error, got $outcome", outcome is SearchOutcome.Error)
        assertEquals(SearchOutcome.ErrorKind.BadResponse, (outcome as SearchOutcome.Error).kind)
    }

    private companion object {
        const val DEFAULT_PAGE_HTML =
            "<html><body><article><p>NVIDIA Corporation designs accelerated computing " +
                "platforms and is a major AI infrastructure company.</p></article></body></html>"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")
    }
}
