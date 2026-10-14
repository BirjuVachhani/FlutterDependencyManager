package dev.birju.flutterdependencymanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PubServersTest {

    @Test
    fun normalizesLikePub() {
        assertEquals("https://myclub.birju.dev", PubServers.normalize("https://myclub.birju.dev"))
        assertEquals("https://myclub.birju.dev", PubServers.normalize("https://myclub.birju.dev/"))
        assertEquals("https://myclub.birju.dev", PubServers.normalize(" HTTPS://MyClub.Birju.Dev:443/ "))
        assertEquals("https://host.com/prefix/pub/", PubServers.normalize("https://host.com/prefix/pub"))
        assertEquals("https://host.com/b/", PubServers.normalize("https://host.com/a/../b"))
        assertEquals("http://localhost:8080", PubServers.normalize("http://localhost:8080"))
        assertEquals("https://pub.dev", PubServers.normalize("https://pub.dartlang.org/"))
    }

    @Test
    fun rejectsWhatPubRejects() {
        assertNull("scheme is required", PubServers.normalize("myclub.birju.dev"))
        assertNull(PubServers.normalize("ftp://host.com"))
        assertNull(PubServers.normalize("https://user:pass@host.com"))
        assertNull(PubServers.normalize("https://host.com/?key=value"))
        assertNull(PubServers.normalize("https://host.com/#fragment"))
    }

    @Test
    fun defaultServerFollowsPubHostedUrlLikePub() {
        assertEquals(PubServers.PUB_DEV, PubServers.defaultFrom(null))
        assertEquals(PubServers.PUB_DEV, PubServers.defaultFrom("  "))
        assertEquals(
            "https://acme.jfrog.io/artifactory/api/pub/dart/",
            PubServers.defaultFrom("https://acme.jfrog.io/artifactory/api/pub/dart"),
        )
        assertEquals("invalid values fall back to pub.dev", PubServers.PUB_DEV, PubServers.defaultFrom("acme.jfrog.io"))
    }

    @Test
    fun apiUrlIsRelativeToTheHostedUrl() {
        assertEquals("https://host.com/prefix/pub/api/packages/foo", PubServers.packageApiUrl("https://host.com/prefix/pub/", "foo"))
        assertEquals("https://pub.dev/api/packages/http", PubServers.packageApiUrl(PubServers.PUB_DEV, "http"))
    }

    @Test
    fun tokensOnlyTravelSecurely() {
        assertTrue(PubServers.isSecure("https://myclub.birju.dev"))
        assertTrue(PubServers.isSecure("http://localhost:10234"))
        assertTrue(PubServers.isSecure("http://127.0.0.1:8080"))
        assertFalse(PubServers.isSecure("http://myclub.birju.dev"))
    }

    private val tokens = """
        {"version": 1, "hosted": [
          {"url": "http://localhost:10234", "token": "local-token"},
          {"url": "https://myclub.birju.dev/", "token": "abc.DEF_123~+/="},
          {"url": "https://env.example.com", "env": "EXAMPLE_TOKEN"},
          {"url": "https://bad.example.com", "token": "evil\r\nX-Injected: 1"},
          {"url": "https://both.example.com", "token": "t", "env": "E"}
        ]}
    """.trimIndent()

    private fun find(url: String, env: Map<String, String> = emptyMap()) = PubTokens.find(tokens, url) { env[it] }

    @Test
    fun findsTokenByNormalizedUrlLikePub() {
        assertEquals("abc.DEF_123~+/=", find("https://myclub.birju.dev"))
        assertEquals("local-token", find("http://localhost:10234"))
        assertNull(find("https://other.example.com"))
    }

    @Test
    fun envCredentials() {
        assertEquals("from-env", find("https://env.example.com", mapOf("EXAMPLE_TOKEN" to "from-env")))
        assertNull("a missing variable means no token", find("https://env.example.com"))
    }

    @Test
    fun rejectsUnsafeOrInvalidCredentials() {
        assertNull("header injection must be impossible", find("https://bad.example.com"))
        assertNull("pub treats token+env as invalid", find("https://both.example.com"))
        assertNull(PubTokens.find("""{"version": 2, "hosted": [{"url": "https://a.dev", "token": "t"}]}""", "https://a.dev") { null })
        assertNull(PubTokens.find("not json", "https://a.dev") { null })
    }
}
