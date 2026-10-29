package dev.chiller.contracts

/**
 * The language of texts the core modules show to the user: run statuses, errors, helper results. The desktop app
 * sets it together with its interface language; English is the default, and texts for the model stay English.
 */
object CoreLanguage {
    @Volatile var russian: Boolean = false
}

/** The user-facing text in the current [CoreLanguage]: both versions stay at the call site. */
fun tr(en: String, ru: String): String = if (CoreLanguage.russian) ru else en
