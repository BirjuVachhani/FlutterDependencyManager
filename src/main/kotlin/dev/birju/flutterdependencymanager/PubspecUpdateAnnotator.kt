package dev.birju.flutterdependencymanager

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import java.util.concurrent.CompletableFuture

/**
 * Background pass that marks outdated dependency constraints in pubspec.yaml.
 * All network work happens in [doAnnotate] (off the EDT, outside read actions);
 * cache misses for every package are fetched concurrently.
 */
class PubspecUpdateAnnotator : ExternalAnnotator<List<DepEntry>, List<PubspecUpdateAnnotator.Outdated>>(), DumbAware {

    data class Outdated(val entry: DepEntry, val latest: String, val type: UpdateType)

    override fun collectInformation(file: PsiFile): List<DepEntry> = Pubspec.entries(file)

    override fun doAnnotate(collectedInfo: List<DepEntry>?): List<Outdated> {
        val entries = collectedInfo?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val futures = entries.map { it to PubDev.latestVersionFuture(it.lookupName, it.hostedUrl) }
        PubDev.awaitDone(CompletableFuture.allOf(*futures.map { it.second }.toTypedArray()), timeoutMs = 15_000)
        return futures.mapNotNull { (entry, future) ->
            val latest = future.valueOrNull() ?: return@mapNotNull null
            PubVersion.updateType(entry.constraint, latest)?.let { Outdated(entry, latest, it) }
        }
    }

    override fun apply(file: PsiFile, annotationResult: List<Outdated>?, holder: AnnotationHolder) {
        val outdated = annotationResult?.takeIf { it.isNotEmpty() } ?: return
        val all = outdated.map { Upgrade(it.entry.section, it.entry.name, it.latest) }
        for ((entry, latest, type) in outdated) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "${type.label} update available: $latest")
                .range(entry.range)
                .textAttributes(type.textAttributesKey)
                .newFix(UpgradeDependencyFix(entry.section, entry.name, latest)).registerFix()
                .newFix(UpgradeDependencyFix(entry.section, entry.name, latest, pubGet = true)).registerFix()
                .newFix(UpgradeAllDependenciesFix(all)).registerFix()
                .newFix(ReadChangelogFix(entry.lookupName, entry.hostedUrl)).registerFix()
                .create()
        }
    }
}
