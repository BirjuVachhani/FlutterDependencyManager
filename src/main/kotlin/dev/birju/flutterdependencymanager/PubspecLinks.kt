package dev.birju.flutterdependencymanager

import com.intellij.openapi.paths.WebReference
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.pom.PomNamedTarget
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.PomTargetPsiElementImpl
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Cmd+Click in pubspec.yaml:
 * - on a dependency name opens the package's page: pub.dev for regular dependencies,
 *   the hosted server's page for `hosted:` dependencies, the repository for `git:` dependencies;
 * - on a dependency's version opens its changelog in the side panel.
 */
class PubspecLinkContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLKeyValue::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val kv = element as YAMLKeyValue
                    if (!Pubspec.isDependencyKeyValue(kv)) return PsiReference.EMPTY_ARRAY
                    val url = PubspecLinks.packageUrl(kv) ?: return PsiReference.EMPTY_ARRAY
                    val key = kv.key ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(WebReference(kv, key.textRangeInParent, url))
                }
            },
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    // Runs for every YAML scalar; dependencyOf does pointer checks before anything allocates.
                    val dep = Pubspec.dependencyOf(element as YAMLScalar) ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(ChangelogReference(element, dep.lookupName, dep.hostedUrl))
                }
            },
            PsiReferenceRegistrar.DEFAULT_PRIORITY,
        )
    }
}

/**
 * Resolves to a [PomTarget] rather than a fake PSI element: the platform turns PSI targets with a
 * containing file into "go to offset" requests, while a PomTarget's own [navigate] is honored.
 */
private class ChangelogReference(scalar: YAMLScalar, private val packageName: String, private val hostedUrl: String?) :
    PsiReferenceBase<YAMLScalar>(scalar, TextRange(0, scalar.textLength), true) {
    override fun resolve(): PsiElement =
        PomTargetPsiElementImpl(element.project, ChangelogTarget(element.project, packageName, hostedUrl))
}

private class ChangelogTarget(
    private val project: Project,
    private val packageName: String,
    private val hostedUrl: String?,
) : PomNamedTarget {
    override fun getName() = "Changelog of $packageName"
    override fun isValid() = !project.isDisposed
    override fun canNavigate() = true
    override fun canNavigateToSource() = false
    override fun navigate(requestFocus: Boolean) = ChangelogToolWindowFactory.show(project, packageName, hostedUrl)
    override fun equals(other: Any?) =
        other is ChangelogTarget && other.packageName == packageName && other.hostedUrl == hostedUrl && other.project == project
    override fun hashCode() = packageName.hashCode()
}

object PubspecLinks {
    private val PACKAGE_NAME = Pubspec.PACKAGE_NAME

    /** `ssh://[user@]host[:port]/path`; the port is the ssh port, meaningless for the web UI. */
    private val SSH_URL = Regex("""^ssh://(?:[^@/]+@)?([^:/]+)(?::\d+)?/(.+)$""")

    /** scp-like `git@host:path`. */
    private val SCP_URL = Regex("""^git@([^:/]+):(.+)$""")

    /** Browser URL for a dependency entry, or null when there is nothing sensible to open. */
    fun packageUrl(dep: YAMLKeyValue): String? {
        return when (val value = dep.value) {
            // `http: ^1.6.0`, `http:` (null value) and `http: any` are all pub-hosted
            is YAMLScalar, null -> pubDevUrl(dep.keyText)
            is YAMLMapping -> {
                val git = value.getKeyValueByKey("git")
                val hosted = value.getKeyValueByKey("hosted")
                when {
                    git != null -> gitUrl(git)
                    hosted != null -> hostedUrl(hosted, dep.keyText)
                    value.getKeyValueByKey("path") != null -> null // local package, nothing to open
                    value.getKeyValueByKey("sdk") != null -> null // flutter/dart SDK package, not on pub.dev
                    else -> pubDevUrl(dep.keyText) // e.g. only `version:` given
                }
            }
            else -> null
        }
    }

    /** The default server's page: pub.dev, or PUB_HOSTED_URL when set (as pub resolves it). */
    private fun pubDevUrl(name: String): String? =
        if (PACKAGE_NAME.matches(name)) "${PubServers.default.trimEnd('/')}/packages/$name" else null

    /** `git: <url>` or `git: { url: <url>, ... }`, normalized to something a browser can open. */
    private fun gitUrl(git: YAMLKeyValue): String? {
        val raw = when (val v = git.value) {
            is YAMLScalar -> v.textValue
            is YAMLMapping -> (v.getKeyValueByKey("url")?.value as? YAMLScalar)?.textValue
            else -> null
        }?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return browsableGitUrl(raw)
    }

    /**
     * `hosted: <url>` (short form), `hosted: { url: <url>, name: <name> }` (long form), mirroring pub:
     * a long form without `url` means the default server, and a short-form value that is a bare
     * package name is the pre-Dart-2.15 syntax for "this name on the default server".
     */
    private fun hostedUrl(hosted: YAMLKeyValue, depName: String): String? {
        val (base, name) = when (val v = hosted.value) {
            is YAMLScalar -> {
                val text = v.textValue.trim()
                if (PACKAGE_NAME.matches(text)) return pubDevUrl(text)
                text to depName
            }
            is YAMLMapping -> {
                val url = (v.getKeyValueByKey("url")?.value as? YAMLScalar)?.textValue
                val name = (v.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue?.trim() ?: depName
                url to name
            }
            else -> return null
        }
        val trimmed = base?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: return pubDevUrl(name)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!withScheme.startsWith("http://") && !withScheme.startsWith("https://")) return null
        return "$withScheme/packages/$name"
    }

    /** `git@host:path`, `ssh://[user@]host[:port]/path`, `git://host/path` become https; plain http(s) passes through. */
    internal fun browsableGitUrl(raw: String): String? {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        (SSH_URL.matchEntire(raw) ?: SCP_URL.matchEntire(raw))?.let { m ->
            return "https://${m.groupValues[1]}/${m.groupValues[2].removeSuffix(".git")}"
        }
        if (raw.startsWith("git://")) return "https://" + raw.removePrefix("git://").removeSuffix(".git")
        return null
    }
}
