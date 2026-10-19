package dev.chiller.tools

import dev.chiller.contracts.ToolException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebServiceTest {
    @Test fun fetchStripsScriptsAndStylesAndReturnsSourceTitle() = runBlocking {
        val transport = FixtureTransport(WebResponse(200, "text/html; charset=utf-8", null,
            "<html><head><title>Reference</title><style>secret-style</style><script>secret-script()</script></head><body><h1>Hello</h1><p>Page text &amp; more</p></body></html>".toByteArray()))
        val service = BoundedWebService(transport, publicResolver)
        val result = service.fetch("https://example.com/docs")
        val data = result.data!!.jsonObject
        assertEquals("Reference", data.getValue("title").jsonPrimitive.content)
        val content = data.getValue("content").jsonPrimitive.content
        assertTrue(content.contains("Page text & more"))
        assertFalse(content.contains("secret-style"))
        assertFalse(content.contains("secret-script"))
        assertEquals("https://example.com/docs", data.getValue("url").jsonPrimitive.content)
    }

    @Test fun transportFailureIsStructuredAndDoesNotLeakInternalDetails() = runBlocking {
        val service = BoundedWebService(WebTransport { _, _ -> throw java.io.IOException("synthetic-private-detail") }, publicResolver)
        val error = assertFailsWith<ToolException> { service.fetch("https://example.com/") }
        assertEquals("NETWORK_ERROR", error.code)
        assertFalse("synthetic-private-detail" in error.message)
    }

    @Test fun redirectsAreRevalidatedAndPrivateTargetsAreBlocked() = runBlocking {
        val transport = FixtureTransport(
            WebResponse(302, "text/html", "/inside", byteArrayOf()),
            WebResponse(200, "text/html", null, "<title>ok</title><p>done</p>".toByteArray()),
        )
        val service = BoundedWebService(transport, publicResolver)
        assertTrue(service.fetch("https://example.com/start").ok)
        assertEquals("https://example.com/inside", transport.uris[1].toString())

        val privateRedirect = FixtureTransport(WebResponse(302, "text/html", "http://127.0.0.1/admin", byteArrayOf()))
        assertFailsWith<ToolException> { BoundedWebService(privateRedirect, publicResolver).fetch("https://example.com/") }
        assertEquals(1, privateRedirect.uris.size)
    }

    @Test fun ordinaryNavigationLinksDoNotAbortSearchParsing() = runBlocking {
        val html = """<html><a href="/">Home</a><a class="result__a" href="https://example.com/docs">Docs</a></html>"""
        val service = BoundedWebService(FixtureTransport(WebResponse(200, "text/html", null, html.toByteArray())), publicResolver)
        val results = service.search("docs").data!!.jsonObject.getValue("results").jsonArray
        assertEquals(1, results.size)
        assertEquals("Docs", results.single().jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test fun searchReturnsBoundedTitlesAndPrimaryUrls() = runBlocking {
        val html = """<html><div class="result"><a class="result__a" href="https://example.com/guide">Guide &amp; API</a></div>
            <div class="result"><a class="result__a" href="https://example.org/docs">Second</a></div></html>"""
        val transport = FixtureTransport(WebResponse(200, "text/html", null, html.toByteArray()))
        val result = BoundedWebService(transport, publicResolver).search("Kotlin tools", 2)
        val rows = result.data!!.jsonObject.getValue("results").jsonArray
        assertEquals(2, rows.size)
        assertEquals("Guide & API", rows[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("https://example.com/guide", rows[0].jsonObject.getValue("url").jsonPrimitive.content)
        assertTrue(transport.uris.single().rawQuery.contains("Kotlin+tools"))
    }

    @Test fun snippetsAreKeptAndAnEmptyPageWithoutANoticeIsAnError() = runBlocking {
        val html = """<html><div class="result"><a class="result__a" href="https://weather.example/temirtau">Temirtau weather</a>
            <a class="result__snippet" href="https://weather.example/temirtau">Today <b>12</b>° / night 2°</a></div></html>"""
        val row = BoundedWebService(FixtureTransport(WebResponse(200, "text/html", null, html.toByteArray())), publicResolver)
            .search("weather").data!!.jsonObject.getValue("results").jsonArray.single().jsonObject
        assertEquals("Today 12° / night 2°", row.getValue("snippet").jsonPrimitive.content)
        val empty = assertFailsWith<dev.chiller.contracts.ToolException> {
            BoundedWebService(FixtureTransport(WebResponse(200, "text/html", null, "<html><body>Loading…</body></html>".toByteArray())), publicResolver).search("weather")
        }
        assertEquals("SEARCH_UNAVAILABLE", empty.code)
        val none = BoundedWebService(FixtureTransport(WebResponse(200, "text/html", null, "<html><div class=\"no-results\">No results found for x</div></html>".toByteArray())), publicResolver).search("x")
        assertTrue(none.ok && none.data!!.jsonObject.getValue("results").jsonArray.isEmpty())
    }

    @Test fun scriptRenderedPagesAreFlagged() = runBlocking {
        val html = "<html><head><title>Weather</title><script>" + "x".repeat(80_000) + "</script></head><body>Menu Sign in</body></html>"
        val page = BoundedWebService(FixtureTransport(WebResponse(200, "text/html", null, html.toByteArray())), publicResolver).fetch("https://weather.example/today")
        assertTrue("rendered by scripts" in page.data!!.jsonObject.getValue("note").jsonPrimitive.content)
    }

    @Test fun acceptsBoundedPlainTextAndJsonWithoutHtmlRewriting() = runBlocking {
        val plain = "<script>plain text stays literal</script>".toByteArray()
        val json = "{\"html\":\"<script>json stays data</script>\",\"ok\":true}".toByteArray()
        val transport = FixtureTransport(
            WebResponse(200, "text/plain; charset=utf-8", null, plain),
            WebResponse(200, "application/json; charset=utf-8", null, json),
        )
        val service = BoundedWebService(transport, publicResolver)
        val textData = service.fetch("https://example.com/readme.txt").data!!.jsonObject
        assertEquals("text/plain; charset=utf-8", textData.getValue("content_type").jsonPrimitive.content)
        assertEquals(String(plain), textData.getValue("content").jsonPrimitive.content)
        val jsonData = service.fetch("https://example.com/data.json").data!!.jsonObject
        assertEquals("application/json; charset=utf-8", jsonData.getValue("content_type").jsonPrimitive.content)
        assertEquals(String(json), jsonData.getValue("content").jsonPrimitive.content)
        assertEquals("", jsonData.getValue("title").jsonPrimitive.content)
    }

    @Test fun rejectsCredentialsPrivateAddressesAndBinaryContent() = runBlocking {
        val transport = FixtureTransport(WebResponse(200, "image/png", null, byteArrayOf(1, 2, 3)))
        val service = BoundedWebService(transport, publicResolver)
        assertFailsWith<ToolException> { service.fetch("http://user:pass@example.com/") }
        assertFailsWith<ToolException> { service.fetch("http://localhost/") }
        assertEquals(0, transport.uris.size)
        assertFailsWith<ToolException> { service.fetch("https://example.com/image.png") }
        assertEquals(1, transport.uris.size)
    }

    @Test fun rejectsIpv6LoopbackAndReportsSearchChallenges() = runBlocking {
        val ipv6Private = HostResolver { host ->
            if (host.contains("::1")) listOf(InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 }))
            else publicResolver.resolve(host)
        }
        val privateTransport = FixtureTransport(WebResponse(200, "text/html", null, "ok".toByteArray()))
        assertFailsWith<ToolException> { BoundedWebService(privateTransport, ipv6Private).fetch("http://[::1]/admin") }
        assertTrue(privateTransport.uris.isEmpty())

        val challenge = FixtureTransport(WebResponse(200, "text/html", null,
            "<html><body>Automated requests are not allowed. Complete CAPTCHA.</body></html>".toByteArray()))
        val error = assertFailsWith<ToolException> { BoundedWebService(challenge, publicResolver).search("test") }
        assertEquals("SEARCH_BLOCKED", error.code)
    }

    private class FixtureTransport(vararg responses: WebResponse) : WebTransport {
        private val pending = ArrayDeque(responses.toList())
        val uris = mutableListOf<URI>()
        override suspend fun get(uri: URI, maxBytes: Int): WebResponse {
            uris += uri
            val response = pending.removeFirst()
            assertTrue(response.body.size <= maxBytes)
            return response
        }
    }

    private val publicResolver = HostResolver { host ->
        if (host.equals("localhost", true) || host == "127.0.0.1") listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        else listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
    }
}
