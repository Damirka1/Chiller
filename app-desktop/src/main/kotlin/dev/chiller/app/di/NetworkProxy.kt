package dev.chiller.app.di

import dev.chiller.app.i18n.*
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * How Chiller reaches the network: "system" (the operating system's proxy settings), "off" (always direct) or
 * "manual" (the HTTP proxy at [url]; HTTPS goes through it as a CONNECT tunnel). Loopback and the hosts in [bypass]
 * always go direct.
 */
@Serializable
data class ProxySettings(val mode: String = "system", val url: String = "", val bypass: String = "") {
    /** Why these settings cannot be used, in words for the settings page; null when they are valid. */
    fun problem(): String? = when {
        mode !in MODES -> I18n.current.di.proxyUnknownMode
        mode == "manual" && proxyAddress(url) == null -> I18n.current.di.proxyAddressRule
        bypass.length > 2000 -> I18n.current.di.proxyBypassTooLong
        else -> null
    }

    fun normalized() = copy(mode = mode.trim(), url = url.trim(), bypass = bypassPatterns(bypass).joinToString(", "))

    companion object { val MODES = setOf("system", "off", "manual") }
}

/** The address of a manual proxy: `http://host:port` or `host:port`; null when it is not one. */
internal fun proxyAddress(url: String): InetSocketAddress? = runCatching {
    val text = url.trim().let { if ("://" in it) it else "http://$it" }
    val uri = URI(text)
    val host = uri.host ?: return null
    if (uri.scheme?.lowercase() != "http" || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path.orEmpty().trim('/').isNotEmpty()) return null
    if (uri.port !in 1..65535) return null
    InetSocketAddress.createUnresolved(host, uri.port)
}.getOrNull()

/** Patterns separated by commas, semicolons or spaces, lower-cased: `host`, `*.company.local`, `.company.local`, `10.0.*`. */
internal fun bypassPatterns(bypass: String): List<String> =
    bypass.split(',', ';', ' ', '\n', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(64)

/** True when [host] goes direct under [bypass]: a listed host, a wildcard match, or a parent domain written as `.domain`. */
internal fun bypassed(host: String, bypass: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]")
    return bypassPatterns(bypass).any { pattern ->
        when {
            pattern == "<local>" -> '.' !in name && ':' !in name
            pattern.startsWith(".") -> name.endsWith(pattern) || name == pattern.drop(1)
            '*' in pattern -> Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(name)
            else -> name == pattern.removePrefix("[").removeSuffix("]")
        }
    }
}

private fun isLoopbackHost(host: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]")
    return name == "localhost" || name.endsWith(".localhost") || name == "::1" || name.startsWith("127.")
}

/**
 * Where a connection to [uri] goes under [settings]. [system] is the JDK's selector over the OS settings; its SOCKS
 * answers go direct, since the provider clients speak only HTTP proxies. Non-HTTP connections are never proxied.
 */
internal fun proxyRoute(settings: ProxySettings, uri: URI, system: ProxySelector?): List<Proxy> {
    val direct = listOf(Proxy.NO_PROXY)
    val host = uri.host ?: return direct
    if (uri.scheme?.lowercase() !in setOf("http", "https") || settings.mode == "off" || isLoopbackHost(host) || bypassed(host, settings.bypass)) return direct
    return when (settings.mode) {
        "manual" -> proxyAddress(settings.url)?.let { listOf(Proxy(Proxy.Type.HTTP, it)) } ?: direct
        else -> runCatching { system?.select(uri) }.getOrNull().orEmpty().filter { it.type() == Proxy.Type.HTTP }.take(1).ifEmpty { direct }
    }
}

/**
 * The app's one proxy decision. Ktor CIO clients without an explicit proxy and the JDK HTTP clients of the web tools
 * both ask the default [ProxySelector] for every URL, so installing it once routes all of them; [apply] takes effect
 * from the next connection.
 */
object NetworkProxy {
    @Volatile var settings: ProxySettings = ProxySettings()
        private set
    @Volatile private var system: ProxySelector? = null
    @Volatile private var installed = false

    /** Must run before anything asks for the default selector, so the JDK reads the OS proxy settings. */
    @Synchronized fun install() {
        if (installed) return
        System.setProperty("java.net.useSystemProxies", "true")
        system = ProxySelector.getDefault()
        ProxySelector.setDefault(Selector)
        installed = true
    }

    fun apply(value: ProxySettings) { settings = value }

    private object Selector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = proxyRoute(settings, uri, system)
        override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) {}
    }
}
