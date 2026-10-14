package dev.birju.flutterdependencymanager

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** A dependency entry whose constraint we know how to check and rewrite: `1.2.3` or `^1.2.3`. */
data class DepEntry(
    /** `dependencies`, `dev_dependencies` or `dependency_overrides`; a name may appear in several. */
    val section: String,
    val name: String,
    /** Raw constraint text, e.g. `^1.2.3`. */
    val constraint: String,
    /** Range of the constraint scalar: the entry's value, or the `version:` value of a map entry. */
    val range: TextRange,
    /** Normalized package server URL (`hosted:`, or PUB_HOSTED_URL for default-server packages), or null for pub.dev. */
    val hostedUrl: String? = null,
    /** Name to look up on the server; differs from [name] only with `hosted: name:`. */
    val lookupName: String = name,
)

object Pubspec {
    private val SECTIONS = setOf("dependencies", "dev_dependencies", "dependency_overrides")

    /** Valid pub package name; anything else is skipped rather than put into a request URL. */
    internal val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+""")

    /** `1.2.3`, `^1.2.3`, optional prerelease/build suffixes. Anything else (ranges, any, git, path, sdk) is skipped. */
    private val SIMPLE_CONSTRAINT = Regex("""\^?\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")

    fun isPubspec(file: PsiFile): Boolean =
        file is YAMLFile && file.name == "pubspec.yaml"

    /** Read the checkable dependency entries. Must be called inside a read action. */
    fun entries(file: PsiFile): List<DepEntry> {
        if (!isPubspec(file)) return emptyList()
        val root = (file as YAMLFile).documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return emptyList()
        val result = ArrayList<DepEntry>()
        for (section in root.keyValues) {
            val sectionName = section.keyText
            if (sectionName !in SECTIONS) continue
            val deps = section.value as? YAMLMapping ?: continue
            for (dep in deps.keyValues) {
                val name = dep.keyText
                if (!PACKAGE_NAME.matches(name)) continue
                val entry = when (val value = dep.value) {
                    is YAMLScalar -> entry(sectionName, name, value, defaultServer(), lookupName = name)
                    is YAMLMapping -> mappedEntry(sectionName, name, value)
                    else -> null
                }
                if (entry != null) result.add(entry)
            }
        }
        return result
    }

    /** `name: {version: ^1.0.0, hosted: ...}`; git/path/sdk sources have no server version to compare against. */
    private fun mappedEntry(section: String, name: String, value: YAMLMapping): DepEntry? {
        val version = value.getKeyValueByKey("version")?.value as? YAMLScalar ?: return null
        val (hostedUrl, lookupName) = source(value, name) ?: return null
        return entry(section, name, version, hostedUrl, lookupName)
    }

    /**
     * Where a map entry's package lives, resolved the way pub does: `hosted: <url>` (Dart 2.15+),
     * `hosted: {url:, name:}` (url optional, meaning the default server) and the pre-2.15
     * `hosted: <name>`; "default server" means PUB_HOSTED_URL or pub.dev, as for pub. Returns
     * (server URL, or null for pub.dev; name to look up), or null for git/path/sdk sources and
     * invalid hosted values.
     */
    private fun source(value: YAMLMapping, name: String): Pair<String?, String>? {
        if (listOf("git", "path", "sdk").any { value.getKeyValueByKey(it) != null }) return null
        val (server, lookupName) = when (val hosted = value.getKeyValueByKey("hosted")?.value) {
            null -> PubServers.default to name
            is YAMLScalar -> {
                val text = hosted.textValue.trim()
                if (PACKAGE_NAME.matches(text)) PubServers.default to text else (PubServers.normalize(text) ?: return null) to name
            }
            is YAMLMapping -> {
                val url = (hosted.getKeyValueByKey("url")?.value as? YAMLScalar)?.textValue
                val server = if (url != null) PubServers.normalize(url) ?: return null else PubServers.default
                val hostedName = (hosted.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue?.trim() ?: name
                if (!PACKAGE_NAME.matches(hostedName)) return null
                server to hostedName
            }
            else -> return null
        }
        return server.takeUnless { it == PubServers.PUB_DEV } to lookupName
    }

    /** The server of `name: ^1.2.3`: PUB_HOSTED_URL if set, else pub.dev (null). */
    private fun defaultServer(): String? = PubServers.default.takeUnless { it == PubServers.PUB_DEV }

    private fun entry(section: String, name: String, scalar: YAMLScalar, hostedUrl: String?, lookupName: String): DepEntry? {
        val constraint = scalar.textValue.trim()
        if (!SIMPLE_CONSTRAINT.matches(constraint)) return null
        return DepEntry(section, name, constraint, scalar.textRange, hostedUrl, lookupName)
    }

    /** `dependencies: flutter: sdk: flutter`, the same test the Dart plugin uses to defer pub actions to Flutter. */
    fun declaresFlutter(file: PsiFile): Boolean {
        val root = (file as? YAMLFile)?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping ?: return false
        val deps = root.getKeyValueByKey("dependencies")?.value as? YAMLMapping ?: return false
        val flutter = deps.getKeyValueByKey("flutter")?.value as? YAMLMapping ?: return false
        return (flutter.getKeyValueByKey("sdk")?.value as? YAMLScalar)?.textValue?.trim() == "flutter"
    }

    /**
     * The constraint scalar of [packageName] in [section] in the current file state: the entry's value, or
     * the `version:` value of a map entry. Read action required.
     */
    fun findConstraintScalar(file: PsiFile, section: String, packageName: String): YAMLScalar? {
        val root = (file as? YAMLFile)?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping ?: return null
        val dep = (root.getKeyValueByKey(section)?.value as? YAMLMapping)?.getKeyValueByKey(packageName) ?: return null
        return when (val value = dep.value) {
            is YAMLScalar -> value
            is YAMLMapping -> value.getKeyValueByKey("version")?.value as? YAMLScalar
            else -> null
        }
    }

    /**
     * True when [kv] is a direct entry of one of the three dependency sections in a pubspec file.
     * Runs for every YAML key-value on each highlighting pass, so the allocation-free
     * structural checks (depth exactly 2) come first and the key-text check last.
     */
    fun isDependencyKeyValue(kv: YAMLKeyValue): Boolean {
        val sectionMapping = kv.parent as? YAMLMapping ?: return false
        val section = sectionMapping.parent as? YAMLKeyValue ?: return false
        val document = section.parent?.parent as? org.jetbrains.yaml.psi.YAMLDocument ?: return false
        val file = document.parent as? PsiFile ?: return false
        return isPubspec(file) && section.keyText in SECTIONS
    }

    /** A dependency as far as lookups care: where it lives and under which name. */
    data class DepRef(val section: String, val name: String, val lookupName: String, val hostedUrl: String?)

    /**
     * The dependency whose version [scalar] is: `name: <scalar>`, or `name: {version: <scalar>, ...}`
     * on pub.dev or a hosted repository (not git/path/sdk). Runs for every YAML scalar on each
     * highlighting pass, so pointer checks come before anything that allocates.
     */
    fun dependencyOf(scalar: YAMLScalar): DepRef? {
        val kv = scalar.parent as? YAMLKeyValue ?: return null
        if (kv.value !== scalar) return null
        if (isDependencyKeyValue(kv)) {
            val name = kv.keyText
            return if (PACKAGE_NAME.matches(name)) DepRef(sectionOf(kv), name, name, defaultServer()) else null
        }
        val mapping = kv.parent as? YAMLMapping ?: return null
        val dep = mapping.parent as? YAMLKeyValue ?: return null
        if (!isDependencyKeyValue(dep) || kv.keyText != "version") return null
        val name = dep.keyText
        if (!PACKAGE_NAME.matches(name)) return null
        val (hostedUrl, lookupName) = source(mapping, name) ?: return null
        return DepRef(sectionOf(dep), name, lookupName, hostedUrl)
    }

    /** [dependencyOf] for the element under the caret or mouse (a leaf inside the scalar, or the scalar). */
    fun dependencyAt(element: com.intellij.psi.PsiElement): DepRef? =
        generateSequence(element) { it.parent }.take(3).filterIsInstance<YAMLScalar>().firstOrNull()?.let(::dependencyOf)

    /** Only for key-values that passed [isDependencyKeyValue]. */
    private fun sectionOf(dep: YAMLKeyValue) = ((dep.parent as YAMLMapping).parent as YAMLKeyValue).keyText
}
