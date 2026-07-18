package com.northin.bookly.data.db

import androidx.room.Entity

/**
 * Translation + grammar explanation for an exact sentence text, for one language pair.
 * Keyed by content hash rather than by book, so identical sentences are shared across books.
 */
@Entity(
    tableName = "sentence_translations",
    primaryKeys = ["textHash", "sourceLanguage", "targetLanguage"],
)
data class SentenceTranslation(
    val textHash: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translation: String,
    val grammarExplanation: String,
    val fetchedAt: Long,
)
