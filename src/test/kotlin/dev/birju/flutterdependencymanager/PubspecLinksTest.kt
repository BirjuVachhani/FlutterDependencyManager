package dev.birju.flutterdependencymanager

import com.intellij.openapi.paths.WebReference
import com.intellij.pom.PomNamedTarget
import com.intellij.pom.PomTargetPsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PubspecLinksTest : BasePlatformTestCase() {

    private val pubspec = """
        name: demo
        environment:
          sdk: ^3.5.0
        dependencies:
          flutter:
            sdk: flutter
          http: ^1.5.0
          internal_short:
            hosted: https://pub.company.dev/
            version: ^1.0.0
          internal_long:
            hosted:
              name: real_name
              url: pub.company.dev
            version: ^1.0.0
          repo_short:
            git: https://github.com/user/repo.git
          repo_ssh:
            git:
              url: git@github.com:user/repo.git
              ref: main
          repo_ssh_port:
            git:
              url: ssh://git@gitlab.company.com:2222/mobile/app_core.git
          hosted_no_url:
            hosted:
              name: transmogrify
            version: ^1.4.0
          legacy_hosted:
            hosted: real_pkg
            version: ^1.0.0
          local_pkg:
            path: ../local_pkg
        dev_dependencies:
          test: ^1.25.0
    """.trimIndent()

    private fun urlAt(packageName: String): String? {
        myFixture.configureByText("pubspec.yaml", pubspec)
        val offset = myFixture.file.text.indexOf("$packageName:")
        assertTrue("fixture must contain the package key '$packageName'", offset >= 0)
        val ref = myFixture.file.findReferenceAt(offset + 1)
        return (ref as? WebReference)?.url
    }

    fun testPubDevLink() = assertEquals("https://pub.dev/packages/http", urlAt("http"))

    fun testDevDependencyLink() = assertEquals("https://pub.dev/packages/test", urlAt("test"))

    fun testHostedShortForm() =
        assertEquals("https://pub.company.dev/packages/internal_short", urlAt("internal_short"))

    fun testHostedLongFormWithNameAndBareHost() =
        assertEquals("https://pub.company.dev/packages/real_name", urlAt("internal_long"))

    fun testGitShortForm() = assertEquals("https://github.com/user/repo.git", urlAt("repo_short"))

    fun testGitSshUrlBecomesBrowsable() = assertEquals("https://github.com/user/repo", urlAt("repo_ssh"))

    fun testGitSshUrlPortIsDropped() =
        assertEquals("https://gitlab.company.com/mobile/app_core", urlAt("repo_ssh_port"))

    fun testHostedLongFormWithoutUrlUsesDefaultServer() =
        assertEquals("https://pub.dev/packages/transmogrify", urlAt("hosted_no_url"))

    fun testLegacyHostedNameShortForm() =
        assertEquals("https://pub.dev/packages/real_pkg", urlAt("legacy_hosted"))

    fun testPathDependencyHasNoLink() = assertNull(urlAt("local_pkg"))

    fun testSdkDependencyHasNoLink() = assertNull(urlAt("flutter"))

    fun testNonPubspecYamlHasNoLink() {
        myFixture.configureByText("config.yaml", "dependencies:\n  http: ^1.5.0\n")
        val offset = myFixture.file.text.indexOf("http:")
        assertNull(myFixture.file.findReferenceAt(offset + 1))
    }

    fun testVersionLinksToChangelog() {
        myFixture.configureByText("pubspec.yaml", pubspec)
        val ref = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("^1.5.0"))
        assertNotNull("version text must carry the changelog link", ref)
        assertFalse("the version must not open a web page", ref is WebReference)
        val target = (ref!!.resolve() as PomTargetPsiElement).target as PomNamedTarget
        assertEquals("Changelog of http", target.name)
        assertTrue(target.canNavigate())
    }

    fun testHostedVersionLinksToItsServersChangelog() {
        myFixture.configureByText("pubspec.yaml", pubspec)
        val ref = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("^1.0.0")) // internal_short's version
        val target = (ref!!.resolve() as PomTargetPsiElement).target as PomNamedTarget
        assertEquals("Changelog of internal_short", target.name)
    }

    fun testGitAndSdkVersionsHaveNoChangelogLink() {
        myFixture.configureByText(
            "pubspec.yaml",
            "dependencies:\n  pinned:\n    git: https://github.com/x/y\n    version: ^1.0.0\n",
        )
        assertNull(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("^1.0.0")))
    }

    fun testVersionInNonPubspecHasNoChangelogLink() {
        myFixture.configureByText("config.yaml", "dependencies:\n  http: ^1.5.0\n")
        assertNull(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("^1.5.0")))
    }
}
