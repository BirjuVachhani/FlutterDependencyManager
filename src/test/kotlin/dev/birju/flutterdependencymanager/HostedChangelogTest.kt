package dev.birju.flutterdependencymanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class HostedChangelogTest {

    // --- a tiny tar writer, so the tests exercise real archive bytes -----------------------------

    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun file(name: String, data: String, type: Char = '0', prefix: String = "") = apply {
            entry(name, data.toByteArray(), type, prefix)
        }

        fun dir(name: String) = apply { entry(name, ByteArray(0), '5') }

        fun pax(path: String) = apply { entry("PaxHeader", paxRecord("path", path), 'x') }

        fun gnuLongName(name: String) = apply { entry("././@LongLink", (name + "\u0000").toByteArray(), 'L') }

        fun bytes(): ByteArray {
            out.write(ByteArray(1024)) // end-of-archive marker
            return out.toByteArray()
        }

        private fun entry(name: String, data: ByteArray, type: Char, prefix: String = "") {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644".toByteArray().copyInto(h, 100)
            String.format("%011o", data.size).toByteArray().copyInto(h, 124)
            h[156] = type.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            "00".toByteArray().copyInto(h, 263)
            prefix.toByteArray().copyInto(h, 345)
            out.write(h)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }

        private fun paxRecord(key: String, value: String): ByteArray {
            val body = " $key=$value\n"
            var length = body.length + 1
            while ("$length$body".toByteArray().size != length) length++
            return "$length$body".toByteArray()
        }
    }

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(bytes) } }.toByteArray()

    private fun changelogOf(archive: ByteArray) = PackageArchive.changelog(ByteArrayInputStream(gzip(archive)))

    // --- archive ---------------------------------------------------------------------------------

    @Test
    fun findsRootChangelogPastOtherEntries() {
        val archive = TarBuilder()
            .file("pubspec.yaml", "name: payments")
            .dir("lib/")
            .file("lib/payments.dart", "x".repeat(10_000))
            .file("example/CHANGELOG.md", "## example only")
            .file("CHANGELOG.md", "## 0.5.0\n- New")
            .bytes()
        assertEquals("## 0.5.0\n- New", changelogOf(archive))
    }

    @Test
    fun nestedChangelogIsNotThePackagesChangelog() {
        val archive = TarBuilder().file("example/CHANGELOG.md", "## nope").bytes()
        assertNull(changelogOf(archive))
    }

    @Test
    fun dotSlashPrefixAndCaseInsensitiveName() {
        assertEquals("a", changelogOf(TarBuilder().file("./changelog.md", "a").bytes()))
    }

    @Test
    fun ustarPrefixMakesItNested() {
        assertNull(changelogOf(TarBuilder().file("CHANGELOG.md", "x", prefix = "deep/dir").bytes()))
    }

    @Test
    fun paxPathOverridesTheHeaderName() {
        val archive = TarBuilder().pax("CHANGELOG.md").file("TRUNCATED-NAME", "from pax").bytes()
        assertEquals("from pax", changelogOf(archive))
    }

    @Test
    fun gnuLongNameAppliesToTheNextEntryOnly() {
        val archive = TarBuilder()
            .gnuLongName("CHANGELOG.md").file("ignored", "from gnu")
            .bytes()
        assertEquals("from gnu", changelogOf(archive))
        val notNext = TarBuilder().gnuLongName("lib/x.dart").file("a", "1").file("b", "2").bytes()
        assertNull(changelogOf(notNext))
    }

    @Test
    fun alreadyDecodedBodyIsReadToo() {
        // Servers may serve the .tar.gz with Content-Encoding: gzip, which the client already undid
        val plainTar = TarBuilder().file("CHANGELOG.md", "plain").bytes()
        assertEquals("plain", PackageArchive.changelog(ByteArrayInputStream(plainTar)))
    }

    @Test
    fun readsAtMostTheCap() {
        val archive = TarBuilder().file("CHANGELOG.md", "y".repeat(2_000)).bytes()
        val read = Tar.find(ByteArrayInputStream(archive), 100, Long.MAX_VALUE) { it == "CHANGELOG.md" }!!
        assertEquals(100, read.size)
    }

    @Test
    fun scanCapTreatsAFarAwayChangelogAsAbsent() {
        val archive = TarBuilder().file("assets/big.bin", "z".repeat(5_000)).file("CHANGELOG.md", "late").bytes()
        assertNull(Tar.find(ByteArrayInputStream(archive), 100, maxScanBytes = 2_000) { it == "CHANGELOG.md" })
        // ...but the entry that crosses the cap is read when it is the changelog itself
        val crossing = TarBuilder().file("CHANGELOG.md", "x".repeat(3_000)).bytes()
        assertEquals(100, Tar.find(ByteArrayInputStream(crossing), 100, maxScanBytes = 2_000) { it == "CHANGELOG.md" }!!.size)
    }

    @Test
    fun utf8BomDoesNotHideTheNewestHeading() {
        val archive = TarBuilder().file("CHANGELOG.md", "\uFEFF## 1.0.1\n- Fix").bytes()
        val cl = ChangelogMarkdown.toHtml(changelogOf(archive)!!, 40_000)
        assertTrue(cl.html.startsWith("<div class=\"changelog-entry\"><h2>"))
    }

    // --- markdown --------------------------------------------------------------------------------

    private val markdown = """
        # Changelog

        ## 0.5.0
        - **Breaking**: new API
        - Fix `foo()`

        ```dart
        ## 9.9.9 inside a fence is code, not a version
        ```

        ## 0.4.0
        - Older

        ## [0.3.0] - 2026-01-01
        - Oldest
    """.trimIndent()

    @Test
    fun rendersLikePubDevEntries() {
        val cl = ChangelogMarkdown.toHtml(markdown, 40_000)
        assertTrue(cl.complete)
        assertEquals("one block per version", 3, Regex("""<div class="changelog-entry">""").findAll(cl.html).count())
        assertFalse("the preamble is not a version", cl.html.startsWith("<div class=\"changelog-entry\">"))
        assertTrue(cl.html.contains("<strong>Breaking</strong>"))
        assertTrue("the fenced heading stays inside 0.5.0", cl.html.contains("9.9.9 inside a fence"))
        assertTrue(cl.html.contains("<li>Oldest</li>"))
    }

    @Test
    fun hostileHtmlIsStripped() {
        val hostile = """
            ## 1.0.0
            **kept** and [docs](https://example.com/docs) stay.
            <form action="https://evil.example/c"><input name="t"><input type="submit"></form>
            <object classid="com.intellij.ui.components.JBLabel"><param name="text" value="x"></object>
            <img src="file://attacker/share/x.png"> ![beacon](https://evil.example/b.png)
            <script>alert(1)</script> <b onclick="steal()">bold</b>
            <a href="file:///etc/passwd">local</a> [js](javascript:alert(1)) <a href="jetbrains://x">ide</a>
            <link rel="stylesheet" href="https://evil.example/s.css"><style>p{}</style>
        """.trimIndent()
        val html = ChangelogMarkdown.toHtml(hostile, 40_000).html
        for (tag in listOf("<form", "<input", "<object", "<param", "<img", "<script", "<link", "<style", "onclick=")) {
            assertFalse("must not survive: $tag", html.contains(tag, ignoreCase = true))
        }
        // Dangerous schemes may remain as escaped text (e.g. inside a raw-HTML block), never as a link target.
        val targets = Regex("""(?:href|src)\s*=\s*"([^"]*)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue("only web links survive: $targets", targets.all { it.startsWith("https://") || it.startsWith("http://") })
        assertTrue(html.contains("<strong>kept</strong>"))
        assertTrue(html.contains("href=\"https://example.com/docs\""))
        assertTrue("our own wrapper survives sanitizing", html.startsWith("<div class=\"changelog-entry\">"))
    }

    @Test
    fun keepAChangelogReferenceLinksResolve() {
        val md = "## [1.1.0] - 2026-10-01\n- New\n\n## [1.0.0] - 2026-01-01\n- First\n\n[1.1.0]: https://example.com/1.1.0\n"
        val html = ChangelogMarkdown.toHtml(md, 40_000).html
        assertTrue(html.contains("href=\"https://example.com/1.1.0\""))
        assertFalse("no literal brackets in headings", html.contains("[1.1.0]"))
    }

    @Test
    fun pathologicalMarkdownIsBounded() {
        val started = System.nanoTime()
        val cl = ChangelogMarkdown.toHtml("## 1.0.0\n" + "[".repeat(200_000), 40_000)
        val seconds = (System.nanoTime() - started) / 1e9
        println("pathological render: %.2f s".format(seconds))
        assertFalse(cl.complete)
        assertTrue("worst case must stay bounded (was quadratic: minutes)", seconds < 10)
    }

    @Test
    fun stopsRenderingOnceTheCapIsReached() {
        val cl = ChangelogMarkdown.toHtml(markdown, 60)
        assertFalse(cl.complete)
        assertTrue(cl.html.contains("0.5.0"))
        assertFalse(cl.html.contains("0.3.0"))
    }
}
