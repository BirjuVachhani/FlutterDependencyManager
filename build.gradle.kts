import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    compilerOptions {
        // Platform interfaces have real default methods; without this, kotlinc emits delegating
        // overrides in our classes that call deprecated/experimental defaults (flagged by the verifier).
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        // Major-only so Android Studio (same 262 branch, older patch builds) can install it.
        ideaVersion {
            sinceBuild = "262"
        }
    }
    pluginVerification {
        ides {
            // -PverifyIdes=/Applications/A.app,/Applications/B.app verifies against installed IDEs, no downloads.
            val installed = providers.gradleProperty("verifyIdes").orNull
            if (installed != null) installed.split(',').forEach { local(file(it.trim())) } else recommended()
        }
    }
}
