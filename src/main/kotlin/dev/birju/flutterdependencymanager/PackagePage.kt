package dev.birju.flutterdependencymanager

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.jcef.JBCefApp
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.net.URI

/**
 * Alt+Enter on any dependency: "View on pub.dev", or on the `hosted:` server's host,
 * or on the `git:` repository's host. Opens in the IDE's built-in browser.
 * HIGH priority puts it after the dependency quick fixes but ahead of generic intentions.
 */
class ViewPackageIntention : PsiElementBaseIntentionAction(), PriorityAction, DumbAware {
    override fun getFamilyName() = "View package page"
    override fun getPriority() = PriorityAction.Priority.HIGH
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val page = pageAt(element) ?: return false
        text = "View on ${page.host}"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val page = pageAt(element) ?: return
        BuiltInBrowser.open(project, "${page.packageName} · ${page.host}", page.url)
    }

    private class Page(val packageName: String, val url: String, val host: String)

    private fun pageAt(element: PsiElement): Page? {
        if (!Pubspec.isPubspec(element.containingFile ?: return null)) return null
        // The caret may sit anywhere inside the entry, e.g. on a nested `hosted: url:` value.
        val dep = generateSequence(element) { it.parent }.take(8)
            .filterIsInstance<YAMLKeyValue>()
            .firstOrNull(Pubspec::isDependencyKeyValue) ?: return null
        val url = PubspecLinks.packageUrl(dep) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return Page(dep.keyText, url, if (uri.port == -1) host else "$host:${uri.port}")
    }
}

object BuiltInBrowser {
    /** Opens [url] in an IDE editor tab via JCEF when the IDE supports it, else in the system browser. */
    fun open(project: Project, title: String, url: String) {
        if (!openInIde(project, title, url)) BrowserUtil.browse(url)
    }

    private fun openInIde(project: Project, title: String, url: String): Boolean = try {
        JBCefApp.isSupported().also { supported ->
            if (supported) HTMLEditorProvider.openEditor(project, title, HTMLEditorProvider.Request.url(url))
        }
    } catch (_: LinkageError) {
        false // IDE build without the JCEF plugin
    }
}
