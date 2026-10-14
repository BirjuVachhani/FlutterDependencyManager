package dev.birju.flutterdependencymanager

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.net.URI

/**
 * Hovering a dependency's version in pubspec.yaml shows its latest changelog entries: from pub.dev's
 * rendered changelog, or from CHANGELOG.md in the archive for hosted packages. Cached in [PubDev].
 */
class ChangelogDocumentationProvider : DocumentationProvider {

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (contextElement == null || !Pubspec.isPubspec(file)) return null
        return if (Pubspec.dependencyAt(contextElement) != null) contextElement else null
    }

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val target = originalElement ?: element
        if (!Pubspec.isPubspec(target.containingFile ?: return null)) return null
        val dep = Pubspec.dependencyAt(target) ?: return null

        val future = PubDev.changelogFuture(dep.lookupName, dep.hostedUrl)
        // A hosted changelog needs an API call plus an archive download on first use.
        if (!PubDev.awaitDone(future, timeoutMs = if (dep.hostedUrl == null) 4_000 else 10_000)) return null
        val latest = PubDev.latestVersionFuture(dep.lookupName, dep.hostedUrl).valueOrNull()
        val title = buildString {
            append("<b>").append(dep.name).append("</b> changelog")
            if (latest != null) append(" &nbsp;(latest: ").append(StringUtil.escapeXmlEntities(latest)).append(")")
        }
        val failure = PubDev.failureOf(future)
        val body = when {
            failure != null -> ChangelogHtml.failure(failure, dep.hostedUrl) + " Try again in a moment."
            else -> future.valueOrNull()
                ?.let { ChangelogHtml.body(PubDev.excerpt(it, HOVER_MAX_CHARS), dep.lookupName, dep.hostedUrl) }
                ?: "<i>No changelog published for ${dep.name}.</i>"
        }
        return "$title<hr/>$body"
    }

    private companion object {
        /** The popup is a glance; the side panel shows more. */
        const val HOVER_MAX_CHARS = 10_000
    }
}

object ChangelogHtml {
    fun serverName(hostedUrl: String?): String =
        hostedUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: "pub.dev"

    /** Changelog body plus, when it was shortened, a pointer to the rest. */
    fun body(changelog: Changelog, packageName: String, hostedUrl: String?): String = when {
        changelog.complete -> changelog.html
        hostedUrl == null -> changelog.html +
            "<p><a href=\"https://pub.dev/packages/$packageName/changelog\">Read the full changelog on pub.dev</a></p>"
        else -> changelog.html + "<p><i>Older versions are not shown.</i></p>" // the spec defines no changelog page
    }

    /** Why a changelog could not be loaded, worded for the user. */
    fun failure(error: Throwable, hostedUrl: String?): String {
        val server = StringUtil.escapeXmlEntities(serverName(hostedUrl))
        return if (error is PubAuthException && hostedUrl != null) {
            "<i>$server needs a valid token.</i> Run <code>dart pub token add ${StringUtil.escapeXmlEntities(hostedUrl)}</code>."
        } else {
            "<i>Couldn't reach $server.</i>"
        }
    }
}
