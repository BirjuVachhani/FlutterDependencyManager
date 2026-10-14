package dev.birju.flutterdependencymanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ChangelogExtractTest {

    private fun entry(version: String, body: String) =
        "<div class=\"changelog-entry\"><h2 class=\"changelog-version hash-header\" id=\"$version\">$version " +
            "<a href=\"#$version\" class=\"hash-link\">#</a></h2><div class=\"changelog-content\"><p>$body</p></div></div>"

    private fun page(vararg entries: String) =
        "<html><body><nav><div>header</div></nav>" +
            "<section><div class=\"tab-content detail-tab-changelog-content -active markdown-body\">" +
            entries.joinToString("") +
            "</div></section><footer><div>footer text</div></footer></body></html>"

    @Test
    fun extractsOnlyTheChangelogContainerAndStripsHashLinks() {
        val cl = PubDev.extractChangelog(page(entry("2.0.0", "Breaking"), entry("1.0.0", "Initial")), 40_000)!!
        assertTrue(cl.complete)
        assertTrue(cl.html.startsWith("<div class=\"changelog-entry\">"))
        assertTrue(cl.html.contains("Initial"))
        assertFalse("page chrome after the container must be excluded", cl.html.contains("footer"))
        assertFalse("in-page # anchors must be stripped", cl.html.contains("hash-link"))
    }

    @Test
    fun truncatesAtVersionBoundary() {
        val entries = (1..50).map { entry("1.0.$it", "x".repeat(200)) }.toTypedArray()
        val cl = PubDev.extractChangelog(page(*entries), 2_000)!!
        assertFalse(cl.complete)
        assertTrue(cl.html.length <= 2_000)
        assertTrue("must end on a whole entry", cl.html.endsWith("</div></div>"))
        assertTrue(cl.html.contains("1.0.1 "))
    }

    @Test
    fun partialReadIsMarkedIncomplete() {
        val full = page(*(1..50).map { entry("1.0.$it", "y".repeat(200)) }.toTypedArray())
        val cl = PubDev.extractChangelog(full.substring(0, full.length / 2), 40_000)!!
        assertFalse("no closing tag in the buffer means we could not see the end", cl.complete)
    }

    @Test
    fun excerptShortensFurther() {
        val cl = PubDev.extractChangelog(page(*(1..20).map { entry("1.0.$it", "z".repeat(200)) }.toTypedArray()), 40_000)!!
        assertTrue(cl.complete)
        val ex = PubDev.excerpt(cl, 1_000)
        assertFalse(ex.complete)
        assertTrue(ex.html.length <= 1_000)
        assertEquals(cl, PubDev.excerpt(cl, 1_000_000))
    }

    @Test
    fun preambleBeforeAnOversizedFirstEntryKeepsThatEntry() {
        // pub.dev renders a leading "# Changelog" heading ahead of the first version (e.g. sentry)
        val preamble = "<h1 id=\"changelog\" class=\"hash-header\">Changelog</h1>"
        val body = preamble + entry("9.0.0", "w".repeat(5_000)) + entry("8.0.0", "older")
        val cl = PubDev.extractChangelog(page(body), 40_000)!!
        val ex = PubDev.excerpt(cl, 2_000)
        assertFalse(ex.complete)
        assertTrue("the newest version must survive the cut, not only the preamble", ex.html.contains("9.0.0"))
        assertFalse(ex.html.contains("8.0.0"))
    }

    @Test
    fun missingContainerYieldsNull() {
        assertNull(PubDev.extractChangelog("<html><body>nothing here</body></html>", 40_000))
    }

    @Test
    fun trimEvictsExpiredFirstThenOldest() {
        val now = 10_000_000L
        fun done(v: String?) = CompletableFuture.completedFuture(v)
        val map = ConcurrentHashMap<String, PubDev.Entry<String>>()
        map["expired"] = PubDev.Entry(done("1.0.0"), bornAt = now - 31 * 60_000) // past the 30 min TTL
        map["old"] = PubDev.Entry(done("1.0.0"), bornAt = now - 60_000)
        map["new"] = PubDev.Entry(done("1.0.0"), bornAt = now)
        map["pending"] = PubDev.Entry(CompletableFuture(), bornAt = now - 40 * 60_000) // in flight never expires
        PubDev.trim(map, maxEntries = 2, now = now)
        assertEquals(setOf("new", "pending"), map.keys)
    }
}
