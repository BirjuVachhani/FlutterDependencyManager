package dev.birju.flutterdependencymanager

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.jetbrains.yaml.YAMLLanguage
import javax.swing.Icon

private val MAJOR_UPDATE = TextAttributesKey.createTextAttributesKey("FLUTTER_DEPENDENCY_MAJOR_UPDATE")
private val MINOR_UPDATE = TextAttributesKey.createTextAttributesKey("FLUTTER_DEPENDENCY_MINOR_UPDATE")
private val PATCH_UPDATE = TextAttributesKey.createTextAttributesKey("FLUTTER_DEPENDENCY_PATCH_UPDATE")

/**
 * Squiggle of an outdated version. Defaults are in colorSchemes/ for Default and Darcula, which every bundled
 * scheme descends from. No fallback key on purpose: a scheme checks a fallback before its parent, so one
 * would replace these defaults with the fallback's look in every derived scheme (Light, Dark, Islands Dark).
 */
val UpdateType.textAttributesKey: TextAttributesKey
    get() = when (this) {
        UpdateType.MAJOR -> MAJOR_UPDATE
        UpdateType.MINOR -> MINOR_UPDATE
        UpdateType.PATCH -> PATCH_UPDATE
    }

/** Settings | Editor | Color Scheme | Flutter Dependencies. */
class UpdateColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Flutter Dependencies"

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(YAMLLanguage.INSTANCE, null, null)

    override fun getDemoText() = """
        dependencies:
          http: <MAJOR>^0.13.6</MAJOR>     # 1.6.0 (major)
          provider: <MINOR>^6.0.5</MINOR>  # 6.1.5 (minor)
          path: <PATCH>^1.9.0</PATCH>      # 1.9.1 (patch)
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap() =
        UpdateType.entries.associate { it.name to it.textAttributesKey }

    override fun getAttributeDescriptors() =
        UpdateType.entries.map { AttributesDescriptor("${it.label} update", it.textAttributesKey) }.toTypedArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
