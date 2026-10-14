package dev.birju.flutterdependencymanager

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The whole hosted flow against a local spec-v2 server: redirects on the listing and the archive,
 * per-hop token scoping, archive parsing, sanitizing and version validation. No real token is used.
 */
class HostedServerFlowTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>() // path to Authorization header

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { ex ->
            requests += ex.requestURI.path to ex.requestHeaders.getFirst("Authorization")
            fun redirect(code: Int, to: String) {
                ex.responseHeaders.add("Location", to)
                ex.sendResponseHeaders(code, -1)
            }
            fun ok(body: ByteArray) {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            when (ex.requestURI.path) {
                "/repo/api/packages/flowpkg" -> redirect(301, "/repo/v2/api/packages/flowpkg") // same server, under the prefix
                "/repo/v2/api/packages/flowpkg" -> ok(
                    """{"name":"flowpkg","versions":[],"latest":{"archive_url":"$base/repo/archives/flowpkg.tar.gz","version":"2.0.0"}}"""
                        .toByteArray(),
                )
                "/repo/archives/flowpkg.tar.gz" -> redirect(302, "$base/blob/flowpkg.tar.gz?sig=s3cr3t") // off the prefix
                "/blob/flowpkg.tar.gz" -> ok(gzip(tar("CHANGELOG.md", HOSTILE_CHANGELOG)))
                "/repo/api/packages/evilver" -> ok("""{"latest":{"version":"9.9.9<img src=x>"}}""".toByteArray())
                else -> ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
        PubTokens.lookupForTests = { url -> if (url == PubServers.normalize("$base/repo")) "test-token" else null }
    }

    override fun tearDown() {
        try {
            PubTokens.lookupForTests = null
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private val hosted get() = PubServers.normalize("$base/repo")!!

    fun testListingFollowsRedirectsAndArchiveTokenStaysOnItsServer() {
        assertEquals("2.0.0", PubDev.latestVersionFuture("flowpkg", hosted).get(20, TimeUnit.SECONDS))
        val changelog = PubDev.changelogFuture("flowpkg", hosted).get(20, TimeUnit.SECONDS)!!

        val auth = requests.groupBy({ it.first }, { it.second })
        assertEquals("Bearer test-token", auth.getValue("/repo/api/packages/flowpkg").first())
        assertEquals("redirect hop under the prefix keeps the token", "Bearer test-token", auth.getValue("/repo/v2/api/packages/flowpkg").first())
        assertEquals("Bearer test-token", auth.getValue("/repo/archives/flowpkg.tar.gz").first())
        assertNull("the token must not follow a redirect off the hosted URL", auth.getValue("/blob/flowpkg.tar.gz").first())

        assertTrue(changelog.html.contains("<div class=\"changelog-entry\">"))
        assertTrue(changelog.html.contains("<strong>Breaking</strong>"))
        for (bad in listOf("<form", "<img", "<object", "onclick")) assertFalse("must be sanitized: $bad", changelog.html.contains(bad))
    }

    fun testMalformedServerVersionIsIgnored() {
        assertNull(PubDev.latestVersionFuture("evilver", hosted).get(20, TimeUnit.SECONDS))
    }

    private companion object {
        val HOSTILE_CHANGELOG = """
            # Changelog
            ## 2.0.0
            - **Breaking** change
            <form action="https://evil.example/c"><input name="t"></form><img src="https://evil.example/b.png">
            <object classid="javax.swing.JButton"></object><b onclick="x()">b</b>
        """.trimIndent()

        fun tar(name: String, content: String): ByteArray {
            val data = content.toByteArray()
            val out = ByteArrayOutputStream()
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            String.format("%011o", data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            out.write(h)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
            out.write(ByteArray(1024))
            return out.toByteArray()
        }

        fun gzip(bytes: ByteArray) =
            ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(bytes) } }.toByteArray()
    }
}
