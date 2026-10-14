package dev.birju.flutterdependencymanager

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.util.ui.UIUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HostedDependenciesTest : BasePlatformTestCase() {

    private val pubspec = """
        name: app
        dependencies:
          http: ^1.0.0
          payments:
            hosted: https://myclub.birju.dev
            version: ^0.2.0
          legacy:
            hosted: legacy_real
            version: ^1.0.0
          longform:
            hosted:
              name: longform
              url: https://Pub.Company.dev/prefix
            version: 1.0.0
          nourl:
            hosted:
              name: nourl
            version: ^2.0.0
          public_explicit:
            hosted: https://pub.dev
            version: ^3.0.0
          from_git:
            git: https://github.com/x/y
            version: ^1.0.0
          bad_url:
            hosted: not a url
            version: ^1.0.0
        dependency_overrides:
          http: ^1.2.0
    """.trimIndent()

    fun testHostedEntriesResolveLikePub() {
        myFixture.configureByText("pubspec.yaml", pubspec)
        val entries = Pubspec.entries(myFixture.file).associateBy { "${it.section}/${it.name}" }
        assertEquals(
            setOf(
                "dependencies/http", "dependencies/payments", "dependencies/legacy", "dependencies/longform",
                "dependencies/nourl", "dependencies/public_explicit", "dependency_overrides/http",
            ),
            entries.keys,
        )
        val payments = entries.getValue("dependencies/payments")
        assertEquals("https://myclub.birju.dev", payments.hostedUrl)
        assertEquals("^0.2.0", payments.constraint)
        assertEquals("^0.2.0", payments.range.substring(myFixture.file.text))
        assertEquals("legacy_real", entries.getValue("dependencies/legacy").lookupName)
        assertNull("legacy short form means the default server", entries.getValue("dependencies/legacy").hostedUrl)
        assertEquals("https://pub.company.dev/prefix/", entries.getValue("dependencies/longform").hostedUrl)
        assertNull("no url means the default server", entries.getValue("dependencies/nourl").hostedUrl)
        assertNull("explicit pub.dev shares the pub.dev cache", entries.getValue("dependencies/public_explicit").hostedUrl)
    }

    fun testDependencyOfResolvesTheServerForChangelogs() {
        myFixture.configureByText("pubspec.yaml", pubspec)
        fun depAt(text: String, occurrence: Int = 0): Pubspec.DepRef? {
            var offset = -1
            repeat(occurrence + 1) { offset = myFixture.file.text.indexOf(text, offset + 1) }
            return Pubspec.dependencyAt(myFixture.file.findElementAt(offset)!!)
        }
        assertEquals(Pubspec.DepRef("dependencies", "payments", "payments", "https://myclub.birju.dev"), depAt("^0.2.0"))
        assertEquals(Pubspec.DepRef("dependencies", "http", "http", null), depAt("^1.0.0"))
        assertEquals("legacy_real", depAt("^1.0.0", occurrence = 1)?.lookupName)
        assertNull("git sources have no server changelog", depAt("^1.0.0", occurrence = 3))
        assertEquals(Pubspec.DepRef("dependency_overrides", "http", "http", null), depAt("^1.2.0"))
        assertNull("keys are not versions", depAt("payments:"))
    }

    fun testAuthFailureNotifiesOncePerServerWithEscapedServerText() {
        val seen = mutableListOf<String>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    seen += notification.content
                }
            })
        val server = "https://private.example.com"
        PubAuthNotifier.warnOnce(server, PubAuthException(401, "Get one at <b>portal</b>"), sentToken = false)
        PubAuthNotifier.warnOnce(server, PubAuthException(401, null), sentToken = false)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("one balloon per server per session", 1, seen.size)
        assertTrue(seen[0].contains("dart pub token add $server"))
        assertTrue("server-provided text must not inject HTML", seen[0].contains("&lt;b&gt;portal&lt;/b&gt;"))
    }

    private fun applyFix(fix: UpgradeDependencyFix) =
        WriteCommandAction.runWriteCommandAction(project) { fix.invoke(project, myFixture.editor, myFixture.file) }

    fun testUpgradeRewritesHostedVersionKeepingCaret() {
        myFixture.configureByText("pubspec.yaml", pubspec)
        applyFix(UpgradeDependencyFix("dependencies", "payments", "0.5.0"))
        val text = myFixture.editor.document.text
        assertTrue(text.contains("    hosted: https://myclub.birju.dev\n    version: ^0.5.0\n"))
    }

    fun testUpgradeEditsOnlyTheEntryItWasOfferedOn() {
        myFixture.configureByText("pubspec.yaml", pubspec)
        applyFix(UpgradeDependencyFix("dependency_overrides", "http", "1.6.0"))
        val text = myFixture.editor.document.text
        assertTrue("dependencies entry must stay untouched", text.contains("dependencies:\n  http: ^1.0.0\n"))
        assertTrue(text.endsWith("dependency_overrides:\n  http: ^1.6.0"))
    }
}
