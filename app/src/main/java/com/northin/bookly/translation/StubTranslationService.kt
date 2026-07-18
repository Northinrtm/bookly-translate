package com.northin.bookly.translation

import kotlinx.coroutines.delay
import java.io.File

/** Placeholder used until a real provider (e.g. Gemini) is wired up behind a backend. */
class StubTranslationService : TranslationService {
    override suspend fun translateBatch(
        sentences: List<SentenceToTranslate>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<SentenceTranslationResult> {
        delay(200)
        return sentences.map { sentence ->
            SentenceTranslationResult(
                sentenceIndex = sentence.sentenceIndex,
                translation = "[$targetLanguage] ${sentence.text}",
                grammarExplanation = "Grammar explanation placeholder for: \"${sentence.text}\"",
            )
        }
    }

    override suspend fun explainWord(
        word: String,
        sentenceContext: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): WordExplanation {
        delay(200)
        return WordExplanation(
            translations = listOf("[$targetLanguage] $word"),
            partOfSpeech = "placeholder",
            grammaticalForm = "placeholder",
            notes = "Placeholder explanation for \"$word\" in: \"$sentenceContext\"",
        )
    }

    override suspend fun explainImage(
        imageFile: File,
        sourceLanguage: String,
        targetLanguage: String,
    ): ImageTextExplanation {
        delay(200)
        return ImageTextExplanation(hasText = false, items = emptyList())
    }
}
