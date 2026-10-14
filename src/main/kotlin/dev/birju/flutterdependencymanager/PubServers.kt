package dev.birju.flutterdependencymanager

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.EnvironmentUtil
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Package repositories per the Hosted Pub Repository Specification v2
 * (https://github.com/dart-lang/pub/blob/master/doc/repository-spec-v2.md).
 */
object PubServers {
    const val PUB_DEV = "https://pub.dev"

    /**
     * pub's default server: PUB_HOSTED_URL when set and valid, else pub.dev. Read once, since an IDE's
     * environment is fixed for its lifetime (on macOS EnvironmentUtil reflects the login shell's exports).
     */
    val default: String by lazy { defaultFrom(EnvironmentUtil.getValue("PUB_HOSTED_URL")) }

    internal fun defaultFrom(pubHostedUrl: String?): String =
        pubHostedUrl?.takeIf { it.isNotBlank() }?.let(::normalize) ?: PUB_DEV

    /**
     * Port of pub's `validateAndNormalizeHostedUrl`, so URLs compare exactly as `dart pub` compares them:
     * http(s) only, no user-info/query/fragment, lowercase scheme and host, default port dropped,
     * dot segments removed, a lone "/" path dropped, any other path "/"-terminated,
     * pub.dartlang.org rewritten to pub.dev. Null when pub itself would reject the URL.
     */
    fun normalize(raw: String): String? {
        val uri = runCatching { URI(raw.trim()).normalize() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
        var path = uri.rawPath.orEmpty()
        if (path == "/") path = ""
        if (path.isNotEmpty() && !path.endsWith("/")) path += "/"
        val url = "$scheme://$host$port$path"
        return if (url == "https://pub.dartlang.org") PUB_DEV else url
    }

    /** `<hosted-url>/api/packages/<name>`; the spec's endpoints are relative to the URL without its trailing slash. */
    fun packageApiUrl(hostedUrl: String, packageName: String) = "${hostedUrl.trimEnd('/')}/api/packages/$packageName"

    /**
     * pub's Credential.canAuthenticate: [url] may carry the token of [hostedUrl] when, lowercased and
     * "/"-terminated, it starts with the hosted URL. Used per request, including every redirect hop.
     */
    fun canAuthenticate(hostedUrl: String, url: String): Boolean {
        fun normalized(u: String) = (if (u.endsWith("/")) u else "$u/").lowercase()
        return isSecure(url) && normalized(url).startsWith(normalized(hostedUrl))
    }

    /** Tokens only travel over HTTPS, or HTTP to this machine (the rule `dart pub token add` enforces). */
    internal fun isSecure(hostedUrl: String): Boolean {
        val uri = runCatching { URI(hostedUrl) }.getOrNull() ?: return false
        if (uri.scheme == "https") return true
        val host = uri.host ?: return false
        return uri.scheme == "http" && (host == "localhost" || host.startsWith("127.") || host == "[::1]")
    }
}

/**
 * Tokens saved by `dart pub token add`, read from the same pub-tokens.json the CLI uses. The file is
 * read per request (requests are rare thanks to caching), so adding or removing a token applies at
 * once and no secret is kept in memory between requests.
 */
object PubTokens {
    /** RFC 6750 token syntax; pub refuses anything else, and it keeps the header injection-free. */
    private val BEARER = Regex("""[a-zA-Z0-9._~+/=-]+""")

    /** Test seam for exercising token scoping without touching the real pub-tokens.json. */
    @org.jetbrains.annotations.TestOnly
    internal var lookupForTests: ((String) -> String?)? = null

    fun forServer(hostedUrl: String): String? {
        if (!PubServers.isSecure(hostedUrl)) return null
        lookupForTests?.let { return it(hostedUrl) }
        val file = configDir()?.resolve("pub-tokens.json") ?: return null
        val json = try {
            Files.readString(file)
        } catch (_: IOException) {
            return null // no file means no tokens configured
        }
        return find(json, hostedUrl) { EnvironmentUtil.getValue(it) }
    }

    /** pub's TokenStore.findCredential: the first credential whose normalized URL equals [hostedUrl]. */
    internal fun find(json: String, hostedUrl: String, env: (String) -> String?): String? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject ?: return null
        if (root.string("version") != "1") return null
        val hosted = root.get("hosted")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null
        for (element in hosted) {
            val credential = element as? JsonObject ?: continue
            if (credential.string("url")?.let(PubServers::normalize) != hostedUrl) continue
            val token = credential.string("token")
            val envName = credential.string("env")
            if ((token == null) == (envName == null)) continue // pub: exactly one of token/env makes it valid
            val value = token ?: env(envName!!) ?: return null // pub: a missing env var is an error, not a fallback
            return value.takeIf(BEARER::matches)
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** cli_util's applicationConfigHome("dart"), which pub uses for pub-tokens.json. */
    private fun configDir(): Path? {
        fun env(name: String) = EnvironmentUtil.getValue(name)?.takeIf { it.isNotEmpty() }
        val home = env("HOME") ?: System.getProperty("user.home")
        val base = when {
            SystemInfo.isWindows -> env("APPDATA")?.let { Path.of(it) }
            SystemInfo.isMac -> home?.let { Path.of(it, "Library", "Application Support") }
            else -> env("XDG_CONFIG_HOME")?.let { Path.of(it) } ?: home?.let { Path.of(it, ".config") }
        }
        return base?.resolve("dart")
    }
}

/** 401 (token missing or invalid) or 403 (token lacks access) from a package server. */
class PubAuthException(val status: Int, val serverMessage: String?) : IOException("HTTP $status")

/** One actionable balloon per server per session instead of silently missing update checks. */
object PubAuthNotifier {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun warnOnce(hostedUrl: String, e: PubAuthException, sentToken: Boolean) {
        if (!warned.add(hostedUrl)) return
        val host = runCatching { URI(hostedUrl).host }.getOrNull() ?: hostedUrl
        val problem = when {
            e.status == 403 -> "The saved token for $host has no access to this package."
            sentToken -> "The saved token for $host was rejected (expired or revoked)."
            else -> "$host requires a token to check for updates."
        }
        val content = buildString {
            append(StringUtil.escapeXmlEntities(problem))
            e.serverMessage?.takeIf { it.isNotBlank() }?.let {
                append("<br/>Server: ").append(StringUtil.escapeXmlEntities(it))
            }
            if (e.status == 401) {
                append("<br/>Run <code>dart pub token add ").append(StringUtil.escapeXmlEntities(hostedUrl)).append("</code>")
            }
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Flutter Dependency Manager")
            .createNotification("Package server needs authentication", content, NotificationType.WARNING)
            .notify(null)
    }
}
