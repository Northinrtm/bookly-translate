package com.northin.bookly.translation

import java.io.File

data class SentenceToTranslate(
    val sentenceIndex: Int,
    val text: String,
)

data class SentenceTranslationResult(
    val sentenceIndex: Int,
    val translation: String,
    val grammarExplanation: String,
)

data class WordExplanation(
    /** One or more translation options — a word can have several valid renderings by context. */
    val translations: List<String>,
    val partOfSpeech: String,
    val grammaticalForm: String,
    val notes: String,
)

data class ImageTextItem(
    /** A word or short phrase as it appears in the image, in the source language. */
    val original: String,
    val translation: String,
)

data class ImageTextExplanation(
    val hasText: Boolean,
    /** Empty when [hasText] is false. */
    val items: List<ImageTextItem>,
)

/**
 * Abstraction over whichever AI provider does translation + grammar explanation.
 * Keeps the rest of the app (worker, UI) unaware of the concrete provider (Gemini, etc.)
 * and of how the API key is supplied.
 */
interface TranslationService {
    suspend fun translateBatch(
        sentences: List<SentenceToTranslate>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<SentenceTranslationResult>

    /** On-demand deep dive into a single word/phrase, in the context of the sentence it's from. */
    suspend fun explainWord(
        word: String,
        sentenceContext: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): WordExplanation

    /** On-demand: find and translate any text embedded in a book illustration. */
    suspend fun explainImage(
        imageFile: File,
        sourceLanguage: String,
        targetLanguage: String,
    ): ImageTextExplanation
}
