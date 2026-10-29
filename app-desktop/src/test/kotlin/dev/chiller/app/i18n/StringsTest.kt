package dev.chiller.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringsTest {
    /** Every text of every group, read through the group interfaces so a new group is covered once it is listed here. */
    private fun texts(strings: Strings): Map<String, String> = Strings::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType.isInterface && it.returnType.packageName == Strings::class.java.packageName }
        .map { it.name.removePrefix("get").replaceFirstChar(Char::lowercase) to it.invoke(strings) }
        .flatMap { (group, value) ->
        value.javaClass.interfaces.flatMap { it.methods.toList() }
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }
            .map { "$group.${it.name}" to it.invoke(value) as String }
    }.toMap()

    @Test fun everyLanguageFillsEveryText() {
        for (language in Language.entries) {
            val blank = texts(stringsFor(language)).filterValues { it.isBlank() }.keys
            assertEquals(emptySet(), blank, "$language has empty texts")
        }
        assertEquals(texts(EnStrings).keys, texts(RuStrings).keys)
    }

    @Test fun englishIsTheDefaultAndUnknownIdsFallBackToIt() {
        assertEquals(Language.EN, Language.fromId(null))
        assertEquals(Language.EN, Language.fromId("de"))
        assertEquals(Language.RU, Language.fromId("ru"))
        assertEquals("en", dev.chiller.app.di.UiPreferences().language)
    }

    @Test fun pluralRulesFollowEachLanguage() {
        assertEquals(listOf("файл", "файла", "файлов", "файлов", "файл"), listOf(1, 2, 5, 11, 21).map { ruPlural(it, "файл", "файла", "файлов") })
        assertEquals(listOf("file", "files", "files"), listOf(1, 2, 0).map { enPlural(it, "file", "files") })
    }

    @Test fun russianIsATranslationNotACopy() {
        val en = texts(EnStrings); val ru = texts(RuStrings)
        val same = en.filter { (key, text) -> ru[key] == text && text.any(Char::isLetter) }.keys
        assertTrue(same.size < en.size / 10, "too many texts are identical in both languages: $same")
    }
}
