package com.sai.sports.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language has every string, with the same format arguments.
 *
 * A missing translation silently falls back to English — which, for an athlete
 * who chose Tamil because they do not read English, is a screen they cannot use.
 * A mismatched argument is worse: `%1$d` in English and `%1$s` in Hindi crashes
 * the app the first time that screen is opened in Hindi.
 */
class TranslationCompletenessTest {

    private val resources = File("src/main/res")

    private fun strings(folder: String): Map<String, String> {
        val file = File(resources, "$folder/strings.xml")
        assertTrue("Missing $file", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as org.w3c.dom.Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun formatArguments(text: String): List<String> =
        Regex("""%(\d+\$)?[sdf]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every shipped language has exactly the default keys`() {
        val english = strings("values")

        AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
            val translated = strings("values-${language.tag}")
            assertEquals(
                "Missing in ${language.tag}",
                emptySet<String>(),
                english.keys - translated.keys
            )
            assertEquals(
                "Unknown keys in ${language.tag}",
                emptySet<String>(),
                translated.keys - english.keys
            )
        }
    }

    @Test
    fun `format arguments match in every language`() {
        val english = strings("values")

        AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
            val translated = strings("values-${language.tag}")
            english.forEach { (key, value) ->
                assertEquals(
                    "Format arguments differ for '$key' in ${language.tag}",
                    formatArguments(value),
                    formatArguments(translated.getValue(key))
                )
            }
        }
    }

    @Test
    fun `translations are not just copied English`() {
        val english = strings("values")
        // Strings that legitimately stay the same: empty, symbols, brand names.
        val allowedIdentical = setOf("unit_blank")

        AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
            val translated = strings("values-${language.tag}")
            val untranslated = english.filter { (key, value) ->
                key !in allowedIdentical && value.isNotBlank() && translated[key] == value
            }.keys
            assertEquals("Untranslated in ${language.tag}", emptySet<String>(), untranslated)
        }
    }

    @Test
    fun `every language names itself in its own script`() {
        assertEquals(AppLanguage.entries.size, AppLanguage.entries.map { it.nativeName }.toSet().size)
        assertEquals(AppLanguage.HINDI, AppLanguage.fromTag("hi"))
        assertEquals(null, AppLanguage.fromTag("xx"))
    }
}
