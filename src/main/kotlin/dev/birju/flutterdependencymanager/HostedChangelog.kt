package dev.birju.flutterdependencymanager

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** CHANGELOG.md out of a pub package archive (gzipped tar), without unpacking anything else. */
internal object PackageArchive {
    /** Far more than the renderer will use (see ChangelogMarkdown.MAX_MARKDOWN_CHARS); changelogs are newest-first. */
    private const val MAX_CHANGELOG_BYTES = 64 * 1024

    /** Give up on archives without a root changelog early instead of downloading huge packages to the end. */
    private const val MAX_SCAN_BYTES = 16L * 1024 * 1024

    /** [body] is the archive as served; it may already be gunzipped via Content-Encoding, so sniff the gzip magic. */
    fun changelog(body: InputStream): String? {
        val input = BufferedInputStream(body)
        input.mark(2)
        val gzipped = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val tar = if (gzipped) GZIPInputStream(input) else input
        // Root-level only: lib/**/CHANGELOG.md or example/CHANGELOG.md are not the package's changelog.
        return Tar.find(tar, MAX_CHANGELOG_BYTES, MAX_SCAN_BYTES) { it.equals("CHANGELOG.md", ignoreCase = true) }
            ?.toString(Charsets.UTF_8)
            ?.removePrefix("\uFEFF") // a UTF-8 BOM would turn the newest heading into plain text
    }
}

/**
 * Minimal streaming tar reader: ustar names (with prefix), GNU long names and PAX paths; every other entry
 * is skipped unread. Returns as soon as the wanted file is read, so the caller can abort the download.
 */
internal object Tar {
    private const val BLOCK = 512
    private const val MAX_NAME_RECORD = 64 * 1024 // GNU/PAX name records are tiny; anything bigger is corrupt

    /** [maxScanBytes] bounds how far into the archive we read looking for the file; past it, it counts as absent. */
    fun find(input: InputStream, maxBytes: Int, maxScanBytes: Long, wanted: (String) -> Boolean): ByteArray? {
        val header = ByteArray(BLOCK)
        var pendingName: String? = null // from a GNU 'L' or PAX 'x' record, applies to the next entry only
        var scanned = 0L
        while (input.readNBytes(header, 0, BLOCK) == BLOCK) {
            if (header.all { it.toInt() == 0 }) return null // end-of-archive marker
            val size = size(header)
            val padding = (BLOCK - size % BLOCK) % BLOCK
            scanned += BLOCK + size + padding
            if (scanned > maxScanBytes && pendingName == null && !isCandidate(header, wanted)) return null
            when (val type = header[156].toInt().toChar()) {
                'L', 'x' -> {
                    if (size > MAX_NAME_RECORD) return null
                    val record = input.readNBytes(size.toInt())
                    pendingName = if (type == 'L') cString(record, 0, record.size) else paxPath(record) ?: pendingName
                    input.skipNBytes(padding)
                    continue
                }
                '0', '\u0000' -> {
                    val name = (pendingName ?: ustarName(header)).removePrefix("./")
                    if (wanted(name)) return input.readNBytes(minOf(size, maxBytes.toLong()).toInt())
                }
            }
            pendingName = null
            input.skipNBytes(size + padding)
        }
        return null
    }

    /** Lets the scan cap spare the entry that is the wanted file itself. */
    private fun isCandidate(header: ByteArray, wanted: (String) -> Boolean): Boolean =
        header[156].toInt().toChar().let { it == '0' || it == '\u0000' } && wanted(ustarName(header).removePrefix("./"))

    /** Octal size field, or GNU base-256 (high bit set) for entries over 8 GiB. */
    private fun size(h: ByteArray): Long {
        var value = 0L
        if (h[124].toInt() and 0x80 != 0) {
            for (i in 125 until 136) value = (value shl 8) or (h[i].toLong() and 0xff)
            return value
        }
        var started = false
        for (i in 124 until 136) {
            val c = h[i].toInt().toChar()
            if (c in '0'..'7') {
                value = value * 8 + (c - '0')
                started = true
            } else if (started) {
                break
            }
        }
        return value
    }

    private fun ustarName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val prefix = if (String(h, 257, 5, Charsets.US_ASCII) == "ustar") cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(b: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && b[end].toInt() != 0) end++
        return String(b, offset, end - offset, Charsets.UTF_8)
    }

    /** PAX records are `<byte length> <key>=<value>\n`. */
    private fun paxPath(data: ByteArray): String? {
        var i = 0
        while (i < data.size) {
            var space = i
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            val length = String(data, i, space - i, Charsets.US_ASCII).toIntOrNull() ?: return null
            if (length <= space - i + 1 || i + length > data.size) return null
            val record = String(data, space + 1, i + length - space - 2, Charsets.UTF_8)
            if (record.startsWith("path=")) return record.removePrefix("path=")
            i += length
        }
        return null
    }
}

/**
 * CHANGELOG.md rendered with the structure of pub.dev's changelog page: an optional preamble, then one
 * `changelog-entry` block per version. Hover excerpts, size caps and the side panel then treat hosted
 * and pub.dev changelogs identically.
 *
 * A hosted server's changelog is untrusted (pub.dev sanitizes its own; nobody sanitizes a private
 * server's), so every rendered section goes through an allowlist before reaching Swing, whose HTML
 * kit would otherwise build live forms, instantiate `<object>` classes and fetch `<img>` URLs.
 */
internal object ChangelogMarkdown {
    /** Version headings as authors write them: `## 1.2.3`, `# v1.2.3`, `## [1.2.3] - 2024-01-01`. */
    private val VERSION_HEADING = Regex("""^ {0,3}#{1,6}\s*\[?v?\d+\.\d+\.\d+""")
    private val FENCE = Regex("""^ {0,3}(```|~~~)""")

    /** `[1.2.3]: https://...` reference definitions, which Keep a Changelog puts at the very end. */
    private val LINK_DEFINITION = Regex("""^ {0,3}\[[^\]\n]+]:[ \t]*\S.*$""", RegexOption.MULTILINE)

    // ponytail: the bundled inline parser is quadratic on unmatched `[`, so its input is capped
    // (worst case ~1 s per section); a cancellable parse needs its @Experimental CancellationToken API.
    private const val MAX_MARKDOWN_CHARS = 12_000
    private const val MAX_SECTION_CHARS = 4_000
    private const val MAX_DEFINITION_CHARS = 2_000

    private val SAFE: Safelist = Safelist.basic() // text formatting, lists, code, links (no images, forms, objects, styles)
        .addTags("h1", "h2", "h3", "h4", "h5", "h6", "hr", "del", "s", "table", "thead", "tbody", "tr", "th", "td")
        .removeProtocols("a", "href", "ftp") // http, https, mailto remain
    private val OUTPUT = Document.OutputSettings().prettyPrint(false)

    /** Renders section by section and stops at [maxChars] of HTML or the parser budget, whichever comes first. */
    fun toHtml(markdown: String, maxChars: Int): Changelog {
        val definitions = LINK_DEFINITION.findAll(markdown).joinToString("\n") { it.value }.take(MAX_DEFINITION_CHARS)
        val html = StringBuilder()
        var budget = MAX_MARKDOWN_CHARS
        for (section in sections(markdown)) {
            if (html.length >= maxChars || budget <= 0) return Changelog(html.toString(), complete = false)
            val piece = section.take(minOf(budget, MAX_SECTION_CHARS))
            budget -= piece.length
            val rendered = sanitize(render(if (definitions.isEmpty()) piece else "$piece\n\n$definitions"))
            if (VERSION_HEADING.containsMatchIn(section)) {
                html.append("<div class=\"changelog-entry\">").append(rendered).append("</div>")
            } else {
                html.append(rendered)
            }
            if (piece.length < section.length) return Changelog(html.toString(), complete = false)
        }
        return Changelog(html.toString(), complete = true)
    }

    /** Split before each version heading; `#` lines inside code fences are code, not headings. */
    private fun sections(markdown: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inFence = false
        for (line in markdown.lineSequence()) {
            if (FENCE.containsMatchIn(line)) {
                inFence = !inFence
            } else if (!inFence && VERSION_HEADING.containsMatchIn(line) && current.isNotBlank()) {
                result += current.toString()
                current = StringBuilder()
            }
            current.append(line).append('\n')
        }
        if (current.isNotBlank()) result += current.toString()
        return result
    }

    private fun render(markdown: String): String {
        val flavour = GFMFlavourDescriptor()
        // Deliberately the same (deprecated) entry points the platform's own MarkdownToHtmlConverter uses in 262:
        // they cannot go away without breaking the IDE, unlike the experimental CancellationToken constructor.
        @Suppress("DEPRECATION")
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml().removeSurrounding("<body>", "</body>")
    }

    internal fun sanitize(html: String): String = Jsoup.clean(html, "", SAFE, OUTPUT)
}
