package dev.birju.flutterdependencymanager

import com.google.gson.stream.JsonReader
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Result of a finished future without throwing: null while pending, when missing, or on failure. */
fun <T> CompletableFuture<T?>.valueOrNull(): T? =
    if (isDone && !isCompletedExceptionally) getNow(null) else null

/** Changelog HTML; [complete] is false when it was cut at a version boundary to bound memory/render cost. */
data class Changelog(val html: String, val complete: Boolean)

/**
 * pub.dev access. One shared HTTP/2 client, two small bounded caches.
 *
 * A future completes with null when pub.dev definitively has nothing (404), and
 * exceptionally on network/server errors, so callers can tell the two apart.
 *
 * Latest versions come from `/api/packages/{name}`: the response starts with the
 * `latest` object, so we stream-parse and abort the connection after reading
 * `latest.version` instead of downloading the package's whole version history.
 */
object PubDev {
    private val OK_TTL_MS = TimeUnit.MINUTES.toMillis(30)
    private val MISSING_TTL_MS = TimeUnit.MINUTES.toMillis(5)
    private val ERROR_TTL_MS = TimeUnit.SECONDS.toMillis(30)

    /** Bounds what we keep and render per changelog; changelogs are newest-first, so this keeps recent releases. */
    const val CHANGELOG_MAX_CHARS = 40_000
    private const val PAGE_MAX_CHARS = 2_000_000 // safety net for pathological pages
    private const val BODY_DEADLINE_SECONDS = 20L
    private const val PUB_API_V2 = "application/vnd.pub.v2+json"
    private const val MAX_REDIRECTS = 5
    private val AUTH_MESSAGE = Regex("""message="([^"]*)"""")
    private const val MAX_VERSIONS = 512
    private const val MAX_CHANGELOGS = 16

    private const val ENTRY_START = "<div class=\"changelog-entry\">"
    private val HASH_LINK = Regex("""<a href="#[^"]*" class="hash-link">#</a>""")

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    // Blocking HTTP stays off ForkJoinPool.commonPool; separate pools so a cold
    // pubspec's version burst never delays a changelog the user is waiting for.
    private val versionPool: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("FlutterDependencyManager versions", 8)
    private val changelogPool: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("FlutterDependencyManager changelogs", 2)

    internal class Entry<T>(
        val future: CompletableFuture<T?>,
        val bornAt: Long,
        /** How long a definitive "nothing there" stays valid. */
        private val missingTtlMs: Long = MISSING_TTL_MS,
    ) {
        fun failed() = future.isCompletedExceptionally

        fun expired(now: Long): Boolean {
            if (!future.isDone) return false
            val ttl = when {
                failed() -> ERROR_TTL_MS
                future.getNow(null) == null -> missingTtlMs
                else -> OK_TTL_MS
            }
            return now - bornAt > ttl
        }
    }

    private val versions = ConcurrentHashMap<String, Entry<String>>()
    private val changelogs = ConcurrentHashMap<String, Entry<Changelog>>()

    /**
     * Latest version of [packageName] on [hostedUrl] (normalized; null means pub.dev), fetching
     * (deduplicated) on cache miss. Keyed by API URL, so same-named packages on different servers never mix.
     */
    fun latestVersionFuture(packageName: String, hostedUrl: String? = null): CompletableFuture<String?> {
        val apiUrl = PubServers.packageApiUrl(hostedUrl ?: PubServers.PUB_DEV, packageName)
        return cached(versions, MAX_VERSIONS, apiUrl, versionPool, retryFailed = false) {
            fetchLatestVersion(apiUrl, hostedUrl)
        }
    }

    /**
     * Changelog of [packageName], fetching on cache miss: pub.dev's rendered page, or for a package on
     * [hostedUrl] the CHANGELOG.md inside its latest archive (the spec has no changelog endpoint).
     * [retryFailed] skips a cached network failure (explicit user retry).
     */
    fun changelogFuture(
        packageName: String,
        hostedUrl: String? = null,
        retryFailed: Boolean = false,
    ): CompletableFuture<Changelog?> {
        val key = PubServers.packageApiUrl(hostedUrl ?: PubServers.PUB_DEV, packageName)
        // A release's changelog (or its absence) does not change, so "none" is cached as long as a hit.
        return cached(changelogs, MAX_CHANGELOGS, key, changelogPool, retryFailed, missingTtlMs = OK_TTL_MS) {
            if (hostedUrl == null) fetchChangelog(packageName) else fetchHostedChangelog(hostedUrl, packageName)
        }
    }

    private fun <T> cached(
        map: ConcurrentHashMap<String, Entry<T>>,
        maxEntries: Int,
        key: String,
        pool: ExecutorService,
        retryFailed: Boolean,
        missingTtlMs: Long = MISSING_TTL_MS,
        fetch: () -> T?,
    ): CompletableFuture<T?> {
        val now = System.currentTimeMillis()
        val entry = map.compute(key) { _, old ->
            if (old != null && !old.expired(now) && !(retryFailed && old.failed())) old
            else Entry(CompletableFuture.supplyAsync({ fetch() }, pool), now, missingTtlMs)
        }!!
        if (map.size > maxEntries) trim(map, maxEntries, now)
        return entry.future
    }

    /**
     * Drop expired entries, then the oldest finished ones, until the map fits. Only runs when over capacity.
     * In-flight entries go last: evicting one would let the next caller start a duplicate request.
     */
    internal fun <T> trim(map: ConcurrentHashMap<String, Entry<T>>, maxEntries: Int, now: Long) {
        map.entries.removeIf { it.value.expired(now) }
        while (map.size > maxEntries) {
            val victim = map.entries.filter { it.value.future.isDone }.minByOrNull { it.value.bornAt }
                ?: map.entries.minByOrNull { it.value.bornAt }
                ?: return
            map.remove(victim.key, victim.value)
        }
    }

    /** The failure a finished future completed with, unwrapped; null if it is pending or succeeded. */
    fun failureOf(future: CompletableFuture<*>): Throwable? {
        if (!future.isCompletedExceptionally) return null
        val error = runCatching { future.join() }.exceptionOrNull()
        return (error as? java.util.concurrent.CompletionException)?.cause ?: error
    }

    /** Wait for [future] from a cancellable context, polling so the IDE can interrupt. True when it finished in time. */
    fun awaitDone(future: CompletableFuture<*>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!future.isDone) {
            ProgressManager.checkCanceled()
            if (System.currentTimeMillis() >= deadline) return false
            try {
                future.get(50, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // timeout tick or failure; isDone decides
            }
        }
        return true
    }

    /** One GET. 401/403 always mean authentication trouble; every other status is the caller's to interpret. */
    private fun send(url: String, accept: String?, bearer: String?): HttpResponse<InputStream> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "FlutterDependencyManager (IntelliJ plugin)")
            .apply { if (accept != null) header("Accept", accept) }
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            response.body().close()
            // Spec: WWW-Authenticate: Bearer realm="pub", message="<how to get access>"
            val message = response.headers().firstValue("WWW-Authenticate").orElse(null)
                ?.let { AUTH_MESSAGE.find(it)?.groupValues?.get(1) }
            throw PubAuthException(response.statusCode(), message)
        }
        return response
    }

    /** Hand the content-decoded body to [read]. The request timeout stops applying once headers arrive, so a deadline closes the body and makes a stalled read throw. */
    private fun <T> readBody(response: HttpResponse<InputStream>, read: (InputStream) -> T?): T? {
        val raw = response.body()
        val deadline = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(Runnable { raw.close() }, BODY_DEADLINE_SECONDS, TimeUnit.SECONDS)
        try {
            val gzip = response.headers().firstValue("Content-Encoding").orElse("") == "gzip"
            return (if (gzip) GZIPInputStream(raw) else raw).use(read)
        } finally {
            deadline.cancel(false)
        }
    }

    /**
     * GET following redirects like pub does (package:http, at most 5), for API listings and archives
     * (the spec requires it for archive URLs, e.g. signed blob-storage links). [bearerFor] is asked on
     * every hop, so a token never follows a redirect off its server. 404 means "not found"; any other
     * non-200 status is an error. Errors name only hosts: signed URLs carry secrets in their query string.
     */
    private fun <T> fetch(url: String, accept: String?, bearerFor: (String) -> String?, read: (InputStream) -> T?): T? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val response = send(current, accept, bearerFor(current))
            val status = response.statusCode()
            if (status == 200) return readBody(response, read)
            response.body().close()
            when (status) {
                404 -> return null
                in 300..399 -> {
                    val location = response.headers().firstValue("Location").orElse(null)
                        ?: throw IOException("Redirect without a Location from ${URI(current).host}")
                    current = URI(current).resolve(location).toString()
                    if (!current.startsWith("https://") && !current.startsWith("http://")) {
                        throw IOException("Unsupported redirect from ${URI(url).host}")
                    }
                }
                else -> throw IOException("HTTP $status from ${URI(current).host}")
            }
        }
        throw IOException("Too many redirects from ${URI(url).host}")
    }

    /**
     * GET a pub.dev web page without following redirects: pub.dev answers a changelog-less package's
     * /changelog with a 303 to its README page, which must not be shown as the changelog.
     */
    private fun <T> fetchPubDevPage(url: String, read: (InputStream) -> T?): T? {
        val response = send(url, accept = null, bearer = null)
        when (response.statusCode()) {
            200 -> return readBody(response, read)
            404, in 300..399 -> {
                response.body().close()
                return null
            }
            else -> {
                response.body().close()
                throw IOException("HTTP ${response.statusCode()} from pub.dev")
            }
        }
    }

    /** Runs [block] with the token scoping for [hostedUrl]; a 401/403 raises the one-time "needs a token" balloon. */
    private fun <T> withServerToken(hostedUrl: String?, block: (bearerFor: (String) -> String?) -> T): T {
        val token = hostedUrl?.let(PubTokens::forServer) // pub.dev reads need no token
        // pub's canAuthenticate, applied per request including every redirect hop
        val bearerFor: (String) -> String? =
            if (hostedUrl == null || token == null) { _ -> null }
            else { url -> token.takeIf { PubServers.canAuthenticate(hostedUrl, url) } }
        try {
            return block(bearerFor)
        } catch (e: PubAuthException) {
            if (hostedUrl != null) PubAuthNotifier.warnOnce(hostedUrl, e, sentToken = token != null)
            throw e
        }
    }

    /** Server-supplied versions are untrusted: only a well-formed version reaches the UI or the pubspec. */
    private fun fetchLatestVersion(apiUrl: String, hostedUrl: String?): String? =
        withServerToken(hostedUrl) { bearerFor -> fetchLatestField(apiUrl, bearerFor, "version") }
            ?.trim()?.takeIf { PubVersion.parse(it) != null }

    /** `latest.<field>` of the spec's package listing; key order isn't guaranteed, so scan and stop at the field. */
    private fun fetchLatestField(apiUrl: String, bearerFor: (String) -> String?, field: String): String? =
        fetch(apiUrl, PUB_API_V2, bearerFor) { stream ->
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "latest") {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        // Returning closes the stream, which aborts the rest of the download.
                        if (reader.nextName() == field) return@fetch reader.nextString()
                        reader.skipValue()
                    }
                    reader.endObject()
                }
                null
            }
        }

    /**
     * CHANGELOG.md from the latest archive. Per the spec (and pub's canAuthenticate), the token is sent
     * to the archive only when its URL lies under the hosted URL.
     */
    private fun fetchHostedChangelog(hostedUrl: String, packageName: String): Changelog? =
        withServerToken(hostedUrl) { bearerFor ->
            val archiveUrl = fetchLatestField(PubServers.packageApiUrl(hostedUrl, packageName), bearerFor, "archive_url")
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@withServerToken null
            val markdown = fetch(archiveUrl, accept = null, bearerFor) { PackageArchive.changelog(it) }
                ?: return@withServerToken null
            excerpt(ChangelogMarkdown.toHtml(markdown, CHANGELOG_MAX_CHARS), CHANGELOG_MAX_CHARS)
        }

    private fun fetchChangelog(packageName: String): Changelog? =
        fetchPubDevPage("https://pub.dev/packages/$packageName/changelog") { stream ->
            // Read only as far as we can use: up to the cap past the changelog start, then abort the download.
            val page = StringBuilder()
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val buf = CharArray(16 * 1024)
            var contentStart = -1
            while (page.length < PAGE_MAX_CHARS) {
                val n = reader.read(buf)
                if (n < 0) break
                page.appendRange(buf, 0, n)
                if (contentStart < 0) contentStart = page.indexOf("detail-tab-changelog-content")
                if (contentStart >= 0 && page.length > contentStart + CHANGELOG_MAX_CHARS + 4096) break
            }
            extractChangelog(page, CHANGELOG_MAX_CHARS)
        }

    /**
     * Pull the changelog body out of a (possibly partially read) pub.dev page.
     * Widening fallbacks like pubgrade: the changelog tab first, then any markdown body.
     */
    internal fun extractChangelog(page: CharSequence, maxChars: Int): Changelog? {
        for (marker in listOf("detail-tab-changelog-content", "markdown-body")) {
            val classAttr = page.indexOf(marker)
            if (classAttr < 0) continue
            val open = page.indexOf('>', classAttr)
            if (open < 0) continue
            val close = matchingDivClose(page, open + 1)
            var body = page.substring(open + 1, if (close >= 0) close else page.length)
            var complete = close >= 0
            if (body.length > maxChars) {
                body = cutAtEntry(body, maxChars)
                complete = false
            }
            body = HASH_LINK.replace(body, "") // in-page "#" anchors are noise outside pub.dev
            if (body.length >= 20) return Changelog(body, complete)
        }
        return null
    }

    /** Shorten [changelog] to [maxChars] at a version boundary; [Changelog.complete] reflects any cut. */
    fun excerpt(changelog: Changelog, maxChars: Int): Changelog =
        if (changelog.html.length <= maxChars) changelog
        else Changelog(cutAtEntry(changelog.html, maxChars), complete = false)

    /**
     * Cut before the last version entry that starts within [maxChars], but never before the first one:
     * pub.dev may render a preamble (e.g. `<h1>Changelog</h1>`) ahead of it. Else cut before the last tag.
     */
    private fun cutAtEntry(html: String, maxChars: Int): String {
        val first = html.indexOf(ENTRY_START)
        val entry = html.lastIndexOf(ENTRY_START, maxChars)
        if (first >= 0 && entry > first) return html.substring(0, entry)
        val tag = html.lastIndexOf('<', maxChars)
        return html.substring(0, if (tag > 0) tag else maxChars)
    }

    /** Index of the `</div>` closing a div whose content starts at [from], or -1 if not within [s]. */
    private fun matchingDivClose(s: CharSequence, from: Int): Int {
        var depth = 1
        var i = from
        while (true) {
            val nextClose = s.indexOf("</div>", i)
            if (nextClose < 0) return -1
            val nextOpen = s.indexOf("<div", i)
            if (nextOpen in 0 until nextClose) {
                depth++
                i = nextOpen + 4
            } else {
                if (--depth == 0) return nextClose
                i = nextClose + 6
            }
        }
    }
}
