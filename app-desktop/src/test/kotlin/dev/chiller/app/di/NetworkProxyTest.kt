package dev.chiller.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkProxyTest {
    private val proxy = ProxySettings("manual", "http://proxy.company.local:3128", "*.company.local, .corp, 10.0.*, <local>")

    @Test fun `manual proxy routes remote hosts and keeps loopback and exceptions direct`() {
        fun route(url: String) = proxyRoute(proxy, URI(url), null).single()
        val remote = route("https://api.deepseek.com/v1")
        assertEquals(Proxy.Type.HTTP, remote.type())
        val address = remote.address() as java.net.InetSocketAddress
        assertEquals("proxy.company.local" to 3128, address.hostString to address.port)
        for (direct in listOf("http://localhost:8000/v1", "http://127.0.0.1:1234", "http://[::1]:8080", "https://llm.company.local/v1",
            "https://gateway.corp/v1", "https://corp/v1", "http://10.0.0.5:8080/v1", "http://gpu-box:8080/v1")) {
            assertEquals(Proxy.NO_PROXY, route(direct), direct)
        }
        assertEquals(Proxy.NO_PROXY, proxyRoute(proxy.copy(mode = "off"), URI("https://api.deepseek.com"), null).single())
        assertEquals(Proxy.NO_PROXY, proxyRoute(proxy, URI("socket://api.deepseek.com:443"), null).single(), "only HTTP(S) is proxied")
    }

    @Test fun `system mode follows the OS selector but never a SOCKS answer`() {
        val http = Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("sys-proxy", 8080))
        fun system(answer: Proxy) = object : ProxySelector() {
            override fun select(uri: URI) = listOf(answer)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
        }
        val settings = ProxySettings("system", bypass = "*.company.local")
        assertEquals(http, proxyRoute(settings, URI("https://api.deepseek.com"), system(http)).single())
        assertEquals(Proxy.NO_PROXY, proxyRoute(settings, URI("https://llm.company.local"), system(http)).single())
        assertEquals(Proxy.NO_PROXY, proxyRoute(settings, URI("https://api.deepseek.com"), system(Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("s", 1080)))).single())
        assertEquals(Proxy.NO_PROXY, proxyRoute(settings, URI("https://api.deepseek.com"), null).single())
    }

    @Test fun `proxy addresses and settings are validated`() {
        assertNotNull(proxyAddress("proxy.company.local:3128"))
        assertNotNull(proxyAddress("http://10.1.2.3:8080/"))
        for (bad in listOf("", "proxy.company.local", "https://proxy:3128", "http://user:pw@proxy:3128", "http://proxy:3128/path", "http://proxy:0")) assertNull(proxyAddress(bad), bad)
        assertNull(proxy.problem())
        assertNotNull(ProxySettings("manual", "proxy").problem())
        assertNotNull(ProxySettings("pac").problem())
        assertEquals("*.a, b", ProxySettings(bypass = " *.A ;b  b ").normalized().bypass)
    }

    @Test fun `ktor clients follow the default selector through a proxy, tunnelling https`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            runCatching {
                while (!server.isClosed) server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    seen += line
                    val reply = if (line.startsWith("CONNECT")) "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"
                        else "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    socket.getOutputStream().apply { write(reply.toByteArray()); flush() }
                }
            }
        }
        val previous = ProxySelector.getDefault()
        val settings = ProxySettings("manual", "127.0.0.1:${server.localPort}")
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI) = proxyRoute(settings, uri, null)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
        })
        try {
            HttpClient(CIO).use { client ->
                assertEquals("ok", client.get("http://upstream.invalid/v1/models").bodyAsText())
                runCatching { client.get("https://upstream.invalid/v1/models") }
            }
            // The web tools' JDK client takes the same default selector.
            val jdk = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
            jdk.send(java.net.http.HttpRequest.newBuilder(URI("http://upstream.invalid/page")).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals("GET http://upstream.invalid/v1/models HTTP/1.1", seen.first())
            assertTrue(seen.any { it.startsWith("GET http://upstream.invalid/page") }, "JDK client goes through the proxy: $seen")
            assertTrue(seen.any { it.startsWith("CONNECT upstream.invalid:443") }, "HTTPS goes through a CONNECT tunnel: $seen")
        } finally {
            ProxySelector.setDefault(previous)
            server.close(); worker.join(2_000)
        }
    }
}
