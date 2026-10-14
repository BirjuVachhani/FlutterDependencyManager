package dev.birju.flutterdependencymanager

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ViewPackageIntentionTest : BasePlatformTestCase() {

    private fun viewIntentionAt(yamlWithCaret: String, fileName: String = "pubspec.yaml"): String? {
        myFixture.configureByText(fileName, yamlWithCaret.trimIndent())
        return myFixture.filterAvailableIntentions("View on").singleOrNull()?.text
    }

    fun testPubDevOnVersion() = assertEquals(
        "View on pub.dev",
        viewIntentionAt(
            """
            dependencies:
              http: ^1.<caret>5.0
            """,
        ),
    )

    fun testHostedServerHostFromNestedUrl() = assertEquals(
        "View on pub.company.dev",
        viewIntentionAt(
            """
            dependencies:
              internal:
                hosted:
                  name: internal
                  url: https://pub.company.dev/re<caret>po
                version: ^1.0.0
            """,
        ),
    )

    fun testHostedShortFormOnName() = assertEquals(
        "View on pub.company.dev:8443",
        viewIntentionAt(
            """
            dev_dependencies:
              inter<caret>nal:
                hosted: https://pub.company.dev:8443
                version: ^1.0.0
            """,
        ),
    )

    fun testGitRepositoryHost() = assertEquals(
        "View on github.com",
        viewIntentionAt(
            """
            dependency_overrides:
              repo:
                git:
                  url: git@github.com:user/re<caret>po.git
            """,
        ),
    )

    fun testPathDependencyHasNoPage() = assertNull(
        viewIntentionAt(
            """
            dependencies:
              local:
                path: ../lo<caret>cal
            """,
        ),
    )

    fun testNotOutsideDependencySections() = assertNull(
        viewIntentionAt(
            """
            environment:
              sdk: ^3.<caret>5.0
            """,
        ),
    )

    fun testNotInOtherYamlFiles() = assertNull(
        viewIntentionAt(
            """
            dependencies:
              http: ^1.<caret>5.0
            """,
            fileName = "config.yaml",
        ),
    )

    fun testFlutterDetectionForPubGetRouting() {
        myFixture.configureByText("pubspec.yaml", "dependencies:\n  flutter:\n    sdk: flutter\n")
        assertTrue(Pubspec.declaresFlutter(myFixture.file))
        myFixture.configureByText("pubspec.yaml", "dependencies:\n  http: ^1.5.0\n")
        assertFalse(Pubspec.declaresFlutter(myFixture.file))
    }
}
