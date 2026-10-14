package dev.birju.flutterdependencymanager

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UpdateColorsTest : BasePlatformTestCase() {

    /** The colorSchemes/ defaults load under the keys the annotator uses and reach every scheme. */
    fun testEverySchemeGetsDistinctWavyColors() {
        val colorsByScheme = EditorColorsManager.getInstance().allSchemes.associate { scheme ->
            scheme.name to UpdateType.entries.map { type ->
                val attributes = scheme.getAttributes(type.textAttributesKey)
                assertEquals("$type in ${scheme.name}", EffectType.WAVE_UNDERSCORE, attributes?.effectType)
                assertNotNull("$type stripe in ${scheme.name}", attributes!!.errorStripeColor)
                attributes.effectColor
            }
        }
        colorsByScheme.forEach { (name, colors) -> assertEquals("distinct colors in $name", 3, colors.toSet().size) }
        // Each scheme inherits either the light (Default) or the dark (Darcula) set.
        assertEquals("light and dark sets in $colorsByScheme", 2, colorsByScheme.values.toSet().size)
    }
}
