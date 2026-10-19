package dev.chiller.providers

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import dev.chiller.contracts.AgentMessage
import dev.chiller.contracts.ModelEvent
import dev.chiller.contracts.ModelRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemTrustTest {
    @Test fun osStoresArePickedPerPlatform() {
        assertEquals(listOf("Windows-ROOT"), SystemTrust.osStoreTypes("Windows 11"))
        assertEquals(listOf("KeychainStore-ROOT", "KeychainStore"), SystemTrust.osStoreTypes("Mac OS X"))
        assertEquals(emptyList(), SystemTrust.osStoreTypes("Linux"))
        assertEquals(null, SystemTrust.loadStore("No-Such-Store"))
    }

    @Test fun tlsFailuresAreRecognisedThroughWrappers() {
        assertTrue(isTlsFailure(IOException("wrapped", SSLHandshakeException("PKIX path building failed"))))
        assertTrue(isTlsFailure(java.security.cert.CertPathBuilderException("unable to find valid certification path")))
        assertFalse(isTlsFailure(ConnectException("Connection refused")))
    }

    @Test fun untrustedCertificateIsReportedAsTlsAndNotRetried() = runBlocking {
        SelfSignedServer().use { server ->
            val events = server.chat(SystemTrust.build(emptyList()))
            val failure = assertIs<ModelEvent.Failed>(events.last(), "Stream events: $events")
            assertEquals("PROVIDER_TLS_ERROR", failure.code, failure.message)
            assertEquals(0, server.requests.get())
            assertTrue(events.none { it is ModelEvent.Retrying })
        }
    }

    @Test fun certificateInAnExtraStoreIsTrustedNextToTheJdkRoots() = runBlocking {
        SelfSignedServer().use { server ->
            val manager = SystemTrust.build(listOf(server.trustStore()))
            assertTrue(manager.acceptedIssuers.size > 1, "JDK roots are kept")
            val events = server.chat(manager)
            assertIs<ModelEvent.Completed>(events.last(), "Stream events: $events")
            assertEquals(1, server.requests.get())
        }
    }

    /** An HTTPS chat endpoint with a fresh self-signed certificate for 127.0.0.1, made by the JDK's keytool. */
    private class SelfSignedServer : AutoCloseable {
        private val dir: Path = Files.createTempDirectory("chiller-tls")
        private val keyStore: KeyStore
        private val server: HttpsServer
        val requests = AtomicInteger(0)

        init {
            val file = dir.resolve("server.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
            val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", file.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed" }
            keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, PASSWORD.toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD.toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator = HttpsConfigurator(context)
                createContext("/chat/completions") { exchange ->
                    requests.incrementAndGet()
                    val body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body); exchange.close()
                }
                start()
            }
        }

        fun trustStore(): KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("server", keyStore.getCertificate("server")) }

        suspend fun chat(manager: X509TrustManager): List<ModelEvent> {
            val client = HttpClient(CIO) { engine { useSystemTrust(manager) }; install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
            try {
                val profile = ChatCompletionsProfile("tls", "https://127.0.0.1:${server.address.port}", "m")
                return withTimeout(30_000) { ChatCompletionsProvider(client, profile, emptyList()) { "k" }.stream(ModelRequest("m", listOf(AgentMessage("user", "hi")))).toList() }
            } finally { client.close() }
        }

        override fun close() { server.stop(0); dir.toFile().deleteRecursively() }
    }

    private companion object { const val PASSWORD = "changeit" }
}
