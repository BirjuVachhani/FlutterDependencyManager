package dev.birju.flutterdependencymanager

import com.intellij.codeInsight.intention.FileModifier
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLScalar

// Quick-fix menu order comes from PriorityAction (platform adds TOP +20, HIGH +3, NORMAL 0, LOW -3
// to the group weight, then sorts alphabetically): Upgrade, Upgrade with pub get, Upgrade all, Read changelog.

/**
 * Both fixes re-locate the constraint scalar in the current PSI at invoke time,
 * so they stay correct however the document shifted since annotation.
 * A leading `^` in the existing constraint is preserved.
 */
private fun upgrade(project: Project, file: PsiFile, section: String, packageName: String, latest: String) {
    val scalar = Pubspec.findConstraintScalar(file, section, packageName) ?: return
    val caret = if (scalar.textValue.trim().startsWith("^")) "^" else ""
    replaceScalar(project, scalar, "$caret$latest")
}

private fun replaceScalar(project: Project, scalar: YAMLScalar, newText: String) {
    val generated = YAMLElementGenerator.getInstance(project)
        .createYamlKeyValue("k", newText).value
        ?: return
    scalar.replace(generated)
}

class UpgradeDependencyFix(
    private val section: String,
    private val packageName: String,
    private val latest: String,
    private val pubGet: Boolean = false,
) : BaseIntentionAction(), PriorityAction {
    override fun getFamilyName() = "Flutter dependencies"
    override fun getText() = if (pubGet) "Upgrade to $latest with pub get" else "Upgrade to $latest"
    override fun getPriority() = if (pubGet) PriorityAction.Priority.HIGH else PriorityAction.Priority.TOP
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) =
        file != null && (!pubGet || PubGet.isAvailable())
    override fun startInWriteAction() = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        upgrade(project, file, section, packageName, latest)
        // Diff previews run on a non-physical copy; never start pub get from those.
        if (pubGet && file.isPhysical) PubGet.runAfterWriteAction(project, file)
    }
}

/** One pending upgrade: the entry in [section] named [name] goes to [latest]. */
data class Upgrade(val section: String, val name: String, val latest: String)

class UpgradeAllDependenciesFix(
    // Immutable values; marking it lets the platform render the diff preview for this fix.
    @FileModifier.SafeFieldForPreview private val upgrades: List<Upgrade>,
) : BaseIntentionAction(), PriorityAction {
    override fun getFamilyName() = "Flutter dependencies"
    override fun getText() = "Upgrade all packages (${upgrades.size})"
    override fun getPriority() = PriorityAction.Priority.NORMAL
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = file != null
    override fun startInWriteAction() = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        for ((section, name, latest) in upgrades) {
            // Re-check against the current text: an entry already upgraded by hand is left alone.
            val scalar = Pubspec.findConstraintScalar(file, section, name) ?: continue
            if (!PubVersion.isOutdated(scalar.textValue.trim(), latest)) continue
            upgrade(project, file, section, name, latest)
        }
    }
}

/**
 * Runs the IDE's own "pub get" so output, lock-file refresh and analysis restart behave
 * exactly as when the user clicks it. Routing mirrors the Dart plugin, which hides its
 * pub actions for Flutter pubspecs when the Flutter plugin is present.
 */
object PubGet {
    private const val FLUTTER = "flutter.pub.get"
    private const val DART = "Dart.pub.get"

    fun isAvailable() = action(FLUTTER) != null || action(DART) != null

    /** Call from inside the fix's write action: saves the pubspec and runs pub get once it finishes. */
    fun runAfterWriteAction(project: Project, file: PsiFile) {
        val vFile = file.virtualFile ?: return
        val id = when {
            Pubspec.declaresFlutter(file) && action(FLUTTER) != null -> FLUTTER
            action(DART) != null -> DART
            else -> FLUTTER
        }
        ApplicationManager.getApplication().invokeLater({
            val documents = FileDocumentManager.getInstance()
            documents.getDocument(vFile)?.let(documents::saveDocument) // pub reads the file from disk
            val action = action(id) ?: return@invokeLater
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, vFile)
                .add(CommonDataKeys.PSI_FILE, file)
                .build()
            val event = AnActionEvent.createEvent(
                context, action.templatePresentation.clone(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null,
            )
            ActionUtil.updateAction(action, event) // e.g. Dart disables it while another pub command runs
            if (event.presentation.isEnabled) ActionUtil.performAction(action, event)
        }, project.disposed)
    }

    private fun action(id: String) = ActionManager.getInstance().getAction(id)
}
