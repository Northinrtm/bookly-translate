package com.northin.bookly.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

/** The reader's native language: the translation target for every book, set once app-wide. */
class UserPreferences(private val context: Context) {
    private val nativeLanguageKey = stringPreferencesKey("native_language")

    val nativeLanguage: Flow<String?> = context.dataStore.data.map { it[nativeLanguageKey] }

    suspend fun setNativeLanguage(languageCode: String) {
        context.dataStore.edit { it[nativeLanguageKey] = languageCode }
    }
}
