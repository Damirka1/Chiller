package dev.chiller.tools

import dev.chiller.contracts.ToolException
import dev.chiller.contracts.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import java.io.StringReader

interface WebService {
    suspend fun search(query: String, limit: Int = 5): ToolResult
    suspend fun fetch(url: String): ToolResult
}

data class WebResponse(val status: Int, val contentType: String?, val location: String?, val body: ByteArray)

fun interface WebTransport { suspend fun get(uri: URI, maxBytes: Int): WebResponse }
fun interface HostResolver { fun resolve(host: String): List<InetAddress> }

/**
 * Search and fetch with public-address admission checks and strict size/time limits.
 * DNS checks are best effort: JDK HttpClient resolves the hostname again at connect time, so this is not an OS network sandbox.
 */
class BoundedWebService(
    private val transport: WebTransport = JdkWebTransport(),
    private val resolver: HostResolver = HostResolver { InetAddress.getAllByName(it).toList() },
    private val searchEndpoint: URI = URI("https://html.duckduckgo.com/html/"),
) : WebService {
    override suspend fun search(query: String, limit: Int): ToolResult {
        try { return withTimeout(REQUEST_TIMEOUT_MILLIS) {
            if (query.isBlank() || query.length > MAX_QUERY_CHARS) throw ToolException("INVALID_ARGUMENTS", "query must contain 1–300 characters")
            if (limit !in 1..5) throw ToolException("INVALID_ARGUMENTS", "limit must be between 1 and 5")
            val uri = URI.create("${searchEndpoint.toASCIIString()}?q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}")
            val page = getPage(uri)
            if (!isHtml(page.contentType)) throw ToolException("UNSUPPORTED_CONTENT", "Search provider returned a non-HTML page")
            if (DdgResultParser.isChallenge(page.text)) throw ToolException("SEARCH_BLOCKED", "Search provider returned a challenge page", "Try again later or search from a browser")
            val found = mutableListOf<JsonObject>()
            for (hit in DdgResultParser.parse(page.text)) {
                val resultUri = resultUri(hit.url) ?: continue
                val safe = try { checkedUri(resultUri) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
                found += buildJsonObject { put("title", hit.title.take(300)); put("url", safe.toASCIIString()); put("snippet", hit.snippet.take(700)) }
                if (found.size > limit) break
            }
            // An empty page without DuckDuckGo's own "no results" notice is a block or a changed layout, not an answer.
            if (found.isEmpty() && !DdgResultParser.saysNoResults(page.text))
                throw ToolException("SEARCH_UNAVAILABLE", "Search returned a page without results or a 'no results' notice; the provider may be limiting requests",
                    "Retry once with a different query, or web_fetch a site you know. Do not treat this as 'nothing exists'.")
            val truncated = page.truncated || found.size > limit
            val results = found.take(limit)
            ToolResult(true, buildJsonObject {
                put("query", query); put("results", JsonArray(results)); put("source", "DuckDuckGo HTML")
                put("truncated", truncated)
            }, truncated = truncated)
        } } catch (_: TimeoutCancellationException) { throw ToolException("TIMEOUT", "Web search exceeded its time limit") }
    }

    override suspend fun fetch(url: String): ToolResult {
        if (url.length > MAX_URL_CHARS) throw ToolException("INVALID_URL", "URL exceeds the length limit")
        val uri = try { URI(url) } catch (_: Exception) { throw ToolException("INVALID_URL", "Use an absolute public http or https URL") }
        val page = getPage(uri)
        val extracted = if (isHtml(page.contentType)) {
            val doc = HtmlTextParser.parse(page.text)
            FetchedText(doc.title, doc.text)
        } else FetchedText("", page.text)
        val content = extracted.content.take(MAX_OUTPUT_CHARS)
        val truncated = page.truncated || extracted.content.length > MAX_OUTPUT_CHARS
        // A big page with little readable text is drawn by scripts: its data is not in this text.
        val scriptRendered = isHtml(page.contentType) && extracted.content.trim().length < THIN_TEXT_CHARS && page.text.length > extracted.content.length * 20
        return ToolResult(true, buildJsonObject {
            put("url", page.uri.toASCIIString()); put("title", extracted.title.take(300)); put("content", content)
            put("content_type", page.contentType.take(MAX_CONTENT_TYPE_CHARS)); put("truncated", truncated)
            if (scriptRendered) put("note", "Little readable text: this page is rendered by scripts, so its data (numbers, tables) is not in this content. " +
                "Do not infer it; try another source or tell the user it was not found.")
        }, truncated = truncated)
    }

    private data class WebPage(val text: String, val truncated: Boolean, val uri: URI, val contentType: String)
    private data class FetchedText(val title: String, val content: String)

    private suspend fun getPage(uri: URI): WebPage = try { withTimeout(REQUEST_TIMEOUT_MILLIS) {
        var current = checkedUri(uri)
        var response: WebResponse
        var redirects = 0
        while (true) {
            response = transport.get(current, MAX_BODY_BYTES + 1)
            if (response.status !in 300..399) break
            if (++redirects > MAX_REDIRECTS) throw ToolException("REDIRECT_LIMIT", "Web page exceeded the redirect limit")
            val location = response.location ?: throw ToolException("HTTP_REDIRECT", "Web redirect did not include a destination")
            current = checkedUri(current.resolve(location))
        }
        if (response.status !in 200..299) throw ToolException("HTTP_ERROR", "Web request returned HTTP ${response.status}")
        val contentType = response.contentType?.take(MAX_CONTENT_TYPE_CHARS)?.trim().orEmpty()
        if (!isSupportedContent(contentType)) throw ToolException("UNSUPPORTED_CONTENT", "Only HTML, plain-text, and JSON pages are supported")
        // Check again after the request to reject obvious DNS changes during the exchange.
        checkedUri(current)
        val over = response.body.size > MAX_BODY_BYTES
        val bytes = response.body.copyOf(minOf(response.body.size, MAX_BODY_BYTES))
        val charsetName = Regex("(?i)charset\\s*=\\s*([^;\\s]+)").find(response.contentType.orEmpty())?.groupValues?.get(1)?.trim('"', '\'')
        val charset = runCatching { charsetName?.let(Charset::forName) }.getOrNull() ?: StandardCharsets.UTF_8
        WebPage(bytes.toString(charset), over, current, contentType)
    } } catch (_: TimeoutCancellationException) { throw ToolException("TIMEOUT", "Web request exceeded its time limit") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ToolException) { throw error }
        catch (_: java.net.http.HttpTimeoutException) { throw ToolException("TIMEOUT", "Web request exceeded its time limit") }
        catch (_: Exception) { throw ToolException("NETWORK_ERROR", "Web request could not be completed", "Check connectivity or try another public source") }

    private fun isHtml(contentType: String): Boolean = contentType.substringBefore(';').trim().equals("text/html", ignoreCase = true)
    private fun isSupportedContent(contentType: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        return mediaType == "text/html" || mediaType == "text/plain" || mediaType == "application/json" ||
            (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
    }

    private suspend fun checkedUri(uri: URI): URI {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.isOpaque || uri.rawUserInfo != null || uri.host.isNullOrBlank() || uri.rawFragment != null)
            throw ToolException("INVALID_URL", "Only absolute http/https URLs without credentials or fragments are allowed")
        val defaultPort = if (scheme == "https") 443 else 80
        if (uri.port != -1 && uri.port != defaultPort) throw ToolException("INVALID_URL", "Nonstandard URL ports are not allowed")
        val host = uri.host.removePrefix("[").removeSuffix("]")
        if (host.contains('%')) throw ToolException("INVALID_URL", "Scoped IP addresses are not allowed")
        val addresses = try { withTimeout(DNS_TIMEOUT_MILLIS) { runInterruptible(Dispatchers.IO) { resolver.resolve(host) } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        if (addresses.isEmpty() || addresses.any(::isNonPublic)) throw ToolException("PRIVATE_ADDRESS_BLOCKED", "URL host must resolve only to public addresses")
        return uri
    }

    private fun isNonPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return a == 0 || a == 100 && b in 64..127 || a == 192 && (b == 0 || b == 168 || b == 88 && c == 99) ||
                a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113 || a >= 240
        }
        if (address is Inet6Address) {
            return (bytes[0] and 0xe0) != 0x20 || (bytes[0] and 0xfe) == 0xfc || bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8
        }
        return true
    }

    private data class SearchHit(val title: String, val url: String, val snippet: String)

    private fun resultUri(raw: String): URI? = runCatching {
        val expanded = if (raw.startsWith("//")) "https:$raw" else raw
        val uri = if (expanded.startsWith("/")) searchEndpoint.resolve(expanded) else URI(expanded)
        if (uri.host?.endsWith("duckduckgo.com", ignoreCase = true) == true && uri.path == "/l/") {
            val encoded = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("uddg=") }?.substringAfter('=') ?: return null
            URI(java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8))
        } else uri
    }.getOrNull()

    private object DdgResultParser {
        fun isChallenge(html: String) = Regex("(?i)(captcha|unusual traffic|automated requests)").containsMatchIn(html.take(100_000))
        /** DuckDuckGo's own notice that the query matched nothing. */
        fun saysNoResults(html: String) = Regex("(?i)(class=\"[^\"]*no-results|No results found|No more results)").containsMatchIn(html.take(200_000))
        fun parse(html: String): List<SearchHit> {
            val hits = mutableListOf<SearchHit>()
            val callback = object : HTMLEditorKit.ParserCallback() {
                var active = false
                var href = ""
                var title = StringBuilder()
                // The snippet follows its result's title link; it often carries the answer itself (a temperature, a version).
                var snippetTag: HTML.Tag? = null
                var snippet = StringBuilder()
                override fun handleStartTag(tag: HTML.Tag, attrs: MutableAttributeSet, pos: Int) {
                    val classes = attrs.getAttribute(HTML.Attribute.CLASS)?.toString().orEmpty().split(' ')
                    if (tag == HTML.Tag.A && classes.any { it == "result__a" }) {
                        active = true; href = attrs.getAttribute(HTML.Attribute.HREF)?.toString().orEmpty(); title = StringBuilder()
                    } else if (snippetTag == null && classes.any { it == "result__snippet" } && hits.isNotEmpty()) {
                        snippetTag = tag; snippet = StringBuilder()
                    }
                }
                override fun handleText(data: CharArray, pos: Int) {
                    if (active) title.append(data)
                    else if (snippetTag != null) snippet.append(data)
                }
                override fun handleEndTag(tag: HTML.Tag, pos: Int) {
                    if (tag == HTML.Tag.A && active) { if (href.isNotBlank() && title.isNotBlank()) hits += SearchHit(title.toString().trim(), href, ""); active = false }
                    else if (tag == snippetTag) {
                        val last = hits.removeAt(hits.lastIndex)
                        hits += last.copy(snippet = snippet.toString().replace(Regex("\\s+"), " ").trim())
                        snippetTag = null
                    }
                }
            }
            ParserDelegator().parse(StringReader(html), callback, true)
            return hits.take(20)
        }
    }

    private data class HtmlDocument(val title: String, val text: String)

    private object HtmlTextParser {
        fun parse(html: String): HtmlDocument {
            val text = StringBuilder()
            val title = StringBuilder()
            var inTitle = false
            var skipDepth = 0
            val callback = object : HTMLEditorKit.ParserCallback() {
                override fun handleStartTag(tag: HTML.Tag, attrs: MutableAttributeSet, pos: Int) {
                    if (tag == HTML.Tag.TITLE) inTitle = true
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) skipDepth++
                    if (tag in setOf(HTML.Tag.P, HTML.Tag.DIV, HTML.Tag.BR, HTML.Tag.LI, HTML.Tag.H1, HTML.Tag.H2, HTML.Tag.H3, HTML.Tag.TR)) text.append('\n')
                }
                override fun handleSimpleTag(tag: HTML.Tag, attrs: MutableAttributeSet, pos: Int) { if (tag == HTML.Tag.BR) text.append('\n') }
                override fun handleEndTag(tag: HTML.Tag, pos: Int) {
                    if (tag == HTML.Tag.TITLE) inTitle = false
                    if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && skipDepth > 0) skipDepth--
                    if (tag in setOf(HTML.Tag.P, HTML.Tag.DIV, HTML.Tag.LI, HTML.Tag.H1, HTML.Tag.H2, HTML.Tag.H3, HTML.Tag.TR)) text.append('\n')
                }
                override fun handleText(data: CharArray, pos: Int) {
                    if (inTitle) title.append(data)
                    if (skipDepth == 0) { text.append(data); text.append(' ') }
                }
            }
            ParserDelegator().parse(StringReader(html), callback, true)
            return HtmlDocument(title.toString().replace(WHITESPACE, " ").trim(), text.toString().replace(WHITESPACE, " ").trim())
        }
    }

    private class JdkWebTransport : WebTransport {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).followRedirects(HttpClient.Redirect.NEVER).build()
        override suspend fun get(uri: URI, maxBytes: Int): WebResponse = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("User-Agent", "Chiller/1.0 (read-only web tools)").header("Accept", "text/html, text/plain, application/json;q=0.9").GET().build()
            val response = runInterruptible { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
            val body = runInterruptible { response.body().use { it.readNBytes(maxBytes) } }
            WebResponse(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null), response.headers().firstValue("Location").orElse(null), body)
        }
    }

    companion object {
        private const val MAX_QUERY_CHARS = 300
        private const val MAX_URL_CHARS = 2048
        private const val MAX_BODY_BYTES = 1_000_000
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_CONTENT_TYPE_CHARS = 200
        private const val MAX_REDIRECTS = 3
        private const val DNS_TIMEOUT_MILLIS = 2_000L
        private const val REQUEST_TIMEOUT_MILLIS = 12_000L
        private const val TIMEOUT_SECONDS = 8L
        private val WHITESPACE = Regex("\\s+")
    }
}

internal object DisabledWebService : WebService {
    private fun disabled(): Nothing = throw ToolException("NETWORK_DISABLED", "Network tools are not connected")
    override suspend fun search(query: String, limit: Int): ToolResult = disabled()
    override suspend fun fetch(url: String): ToolResult = disabled()
}
/** Below this much readable text a large HTML page is treated as script-rendered. */
private const val THIN_TEXT_CHARS = 2_500
