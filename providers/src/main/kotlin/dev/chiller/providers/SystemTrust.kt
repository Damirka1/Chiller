package dev.chiller.providers

import io.ktor.client.engine.cio.CIOEngineConfig
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts what the JDK trusts plus the operating system's root store, so a corporate or self-managed CA installed in
 * Windows or the macOS keychain works the way it does for curl and browsers. Verification is never relaxed: a chain
 * must validate against one of the stores, and hostname checks stay with the TLS client.
 */
object SystemTrust {
    val trustManager: X509TrustManager by lazy { build(osStoreTypes(System.getProperty("os.name").orEmpty()).mapNotNull(::loadStore)) }
    val sslContext: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) } }

    internal fun osStoreTypes(osName: String): List<String> {
        val os = osName.lowercase()
        return when {
            "win" in os -> listOf("Windows-ROOT")
            // KeychainStore-ROOT (system roots) exists from JDK 23; KeychainStore holds the user's trusted certificates.
            "mac" in os -> listOf("KeychainStore-ROOT", "KeychainStore")
            else -> emptyList()
        }
    }

    /** An OS store, or null when this JDK has no such store type or it is empty. */
    internal fun loadStore(type: String): KeyStore? =
        runCatching { KeyStore.getInstance(type).apply { load(null, null) } }.getOrNull()?.takeIf { it.size() > 0 }

    internal fun build(extraStores: List<KeyStore>): X509TrustManager {
        val jdk = managerFor(null)
        val extra = extraStores.mapNotNull { store -> runCatching { managerFor(store) }.getOrNull() }
        return if (extra.isEmpty()) jdk else AnyOfTrustManager(listOf(jdk) + extra)
    }

    private fun managerFor(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
}

/** Ktor CIO clients verify servers against [SystemTrust]; tests pass their own manager. */
fun CIOEngineConfig.useSystemTrust(manager: X509TrustManager = SystemTrust.trustManager) { https { trustManager = manager } }

/** True when a connection failed in TLS: an untrusted certificate or a broken handshake, which a retry cannot fix. */
fun isTlsFailure(error: Throwable): Boolean = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(16).any {
    // Certificate and certification-path errors are GeneralSecurityExceptions; Ktor CIO may surface the path error alone.
    it is SSLException || it is GeneralSecurityException || it.javaClass.name == "io.ktor.network.tls.TLSException"
}

/** Accepts a chain that any delegate accepts; reports the first delegate's error when none does. */
private class AnyOfTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = check { it.checkClientTrusted(chain, authType) }
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = check { it.checkServerTrusted(chain, authType) }
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegates.flatMap { it.acceptedIssuers.asList() }.distinct().toTypedArray()

    private inline fun check(verify: (X509TrustManager) -> Unit) {
        var first: CertificateException? = null
        for (delegate in delegates) {
            try { verify(delegate); return } catch (e: CertificateException) { if (first == null) first = e }
        }
        throw first ?: CertificateException("No trust store accepted the certificate chain")
    }
}
