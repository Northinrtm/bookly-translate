package com.northin.bookly.ui.library

/** Short pick list for the language dialogs (book language, reader's native language). */
object CommonLanguages {
    val all = listOf(
        "en" to "English",
        "ru" to "Русский",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português",
        "ja" to "日本語",
        "zh" to "中文",
        "ko" to "한국어",
    )

    fun displayName(code: String): String = all.firstOrNull { it.first == code }?.second ?: code
}
