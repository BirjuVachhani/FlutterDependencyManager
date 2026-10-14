package dev.birju.flutterdependencymanager

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import java.io.StringReader
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.FormSubmitEvent
import javax.swing.text.Document
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

/** Quick fix on an outdated dependency: open its changelog in the side panel. */
class ReadChangelogFix(
    private val packageName: String,
    /** Normalized `hosted:` server, or null for pub.dev. */
    private val hostedUrl: String? = null,
) : BaseIntentionAction(), PriorityAction {
    override fun getFamilyName() = "Flutter dependencies"
    override fun getText() = "Read changelog of '$packageName'"
    override fun getPriority() = PriorityAction.Priority.LOW
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
    override fun startInWriteAction() = false // navigation only; also keeps it out of diff previews

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        ChangelogToolWindowFactory.show(project, packageName, hostedUrl)
    }
}

/**
 * Right-docked tool window rendering a package changelog. Hidden until the first
 * "Read changelog"; content comes from the same cache the hover popup uses.
 */
class ChangelogToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project) = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = Unit // filled on demand

    companion object {
        const val ID = "Flutter Changelog"

        /** A changelog is identified by package and server: same-named packages elsewhere are different packages. */
        fun show(project: Project, packageName: String, hostedUrl: String? = null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.isAvailable = true
            val manager = toolWindow.contentManager

            val current = manager.selectedContent?.component as? ChangelogPanel
            // Reuse only while the panel shows the live cache entry; once that expires the call
            // returns a fresh in-flight future, which the new panel's load() attaches to (no second request).
            val same = current != null && current.packageName == packageName && current.hostedUrl == hostedUrl
            val upToDate = same && !current.failed && current.shown === PubDev.changelogFuture(packageName, hostedUrl)
            if (!upToDate) {
                val panel = ChangelogPanel(packageName, hostedUrl)
                val title = if (hostedUrl == null) packageName else "$packageName \u00b7 ${ChangelogHtml.serverName(hostedUrl)}"
                val content = ContentFactory.getInstance().createContent(panel, title, false)
                manager.removeAllContents(true)
                manager.addContent(content)
                panel.load(content, retry = same && current.failed)
            }
            toolWindow.activate(null)
        }
    }
}

private class ChangelogPanel(val packageName: String, val hostedUrl: String?) : JBScrollPane() {
    @Volatile
    var failed = false
        private set

    private val pane = JEditorPane().apply {
        isEditable = false
        // Defense in depth behind the sanitizer: a form must never submit on its own.
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build().apply { isAutoFormSubmission = false }
        border = JBUI.Borders.empty(8, 12)
    }

    private var parentContent: Content? = null

    /** The cache entry this panel rendered (or is rendering). */
    var shown: java.util.concurrent.CompletableFuture<Changelog?>? = null
        private set

    init {
        setViewportView(pane)
        pane.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED || e is FormSubmitEvent) return@addHyperlinkListener
            val href = e.description.orEmpty()
            when {
                href == RETRY -> (parentContent ?: return@addHyperlinkListener).let { load(it, retry = true) }
                href.startsWith("#") -> pane.scrollToReference(href.substring(1)) // stay in the pane
                // Only web pages leave the IDE: never file:, jetbrains: or other handlers.
                else -> e.url?.takeIf { it.protocol == "https" || it.protocol == "http" }?.let { BrowserUtil.browse(it.toExternalForm()) }
            }
        }
    }

    /** EDT. Fetch (cached) and parse off the EDT; only the finished document is swapped in on the EDT. */
    fun load(content: Content, retry: Boolean) {
        parentContent = content
        val future = PubDev.changelogFuture(packageName, hostedUrl, retryFailed = retry)
        shown = future
        if (!future.isDone) setDocument(parse("<i>Loading changelog of $packageName…</i>"))
        val kit = pane.editorKit as HTMLEditorKit
        future.handleAsync({ changelog, error ->
            failed = error != null
            val html = when {
                error != null -> ChangelogHtml.failure(PubDev.failureOf(future) ?: error, hostedUrl) +
                    " &nbsp;<a href=\"$RETRY\">Retry</a>"
                changelog == null -> "<i>No changelog published for $packageName.</i>"
                else -> ChangelogHtml.body(changelog, packageName, hostedUrl)
            }
            parse(html, kit)
        }, AppExecutorUtil.getAppExecutorService()).thenAccept { doc ->
            ApplicationManager.getApplication().invokeLater {
                if (content.manager != null) setDocument(doc) // skip if the content was replaced meanwhile
            }
        }
    }

    /** Build an HTMLDocument without touching the component; safe off the EDT (Swing's own async loading does this). */
    private fun parse(bodyHtml: String, kit: HTMLEditorKit = pane.editorKit as HTMLEditorKit): Document {
        val doc = kit.createDefaultDocument() as HTMLDocument
        doc.base = base // relative links resolve against the package's own server
        kit.read(StringReader("<html><body>$bodyHtml</body></html>"), doc, 0)
        return doc
    }

    private fun setDocument(doc: Document) {
        pane.document = doc
        pane.caretPosition = 0
    }

    private val base = URI((hostedUrl ?: PubServers.PUB_DEV).trimEnd('/') + "/").toURL()

    private companion object {
        const val RETRY = "flutter-dependency-manager:retry"
    }
}
