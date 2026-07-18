package com.northin.bookly.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.northin.bookly.data.db.AppDatabase
import com.northin.bookly.data.db.ProcessingStatus
import com.northin.bookly.data.db.SentenceTranslation
import com.northin.bookly.translation.SentenceToTranslate
import com.northin.bookly.translation.TranslationService
import kotlinx.coroutines.delay

/**
 * Translates and explains the grammar of an entire book in the background, sentence by sentence,
 * in reading order, persisting each batch to Room as it arrives so progress survives interruption.
 */
class BookProcessingWorker(
    context: Context,
    params: WorkerParameters,
    private val database: AppDatabase,
    private val translationService: TranslationService,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val sourceLanguage = inputData.getString(KEY_SOURCE_LANGUAGE) ?: return Result.failure()
        val targetLanguage = inputData.getString(KEY_TARGET_LANGUAGE) ?: return Result.failure()

        val bookDao = database.bookDao()
        val sentenceDao = database.sentenceDao()

        bookDao.get(bookId) ?: return Result.failure()
        val totalSentences = sentenceDao.countSentences(bookId)

        try {
            while (!isStopped) {
                // The book may have been deleted while a long batch call was in flight — bail
                // out instead of endlessly retrying work for a book that no longer exists.
                if (bookDao.get(bookId) == null) return Result.success()

                val batch = sentenceDao.getUntranslatedSentences(bookId, sourceLanguage, targetLanguage, BATCH_SIZE)
                if (batch.isEmpty()) break

                val results = translationService.translateBatch(
                    sentences = batch.map { SentenceToTranslate(it.sentenceIndex, it.originalText) },
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                )

                val now = System.currentTimeMillis()
                val translations = batch.mapNotNull { bookSentence ->
                    val result = results.find { it.sentenceIndex == bookSentence.sentenceIndex }
                        ?: return@mapNotNull null
                    SentenceTranslation(
                        textHash = bookSentence.textHash,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translation = result.translation,
                        grammarExplanation = result.grammarExplanation,
                        fetchedAt = now,
                    )
                }
                sentenceDao.insertTranslations(translations)

                val remaining = sentenceDao.countUntranslatedSentences(bookId, sourceLanguage, targetLanguage)
                bookDao.updateProgress(bookId, ProcessingStatus.IN_PROGRESS, totalSentences - remaining, totalSentences)

                if (remaining > 0) {
                    delay(RATE_LIMIT_DELAY_MS)
                }
            }
        } catch (e: Exception) {
            // Network hiccup or API error mid-book: what's already translated stayed in Room,
            // so retrying just resumes from the first still-untranslated sentence.
            Log.e(TAG, "Batch translation failed for book $bookId, will retry", e)
            return Result.retry()
        }

        val remaining = sentenceDao.countUntranslatedSentences(bookId, sourceLanguage, targetLanguage)
        if (remaining == 0) {
            bookDao.updateProgress(bookId, ProcessingStatus.COMPLETE, totalSentences, totalSentences)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "BookProcessingWorker"

        const val KEY_BOOK_ID = "bookId"
        const val KEY_SOURCE_LANGUAGE = "sourceLanguage"
        const val KEY_TARGET_LANGUAGE = "targetLanguage"

        // 400 generated so much output (translation + grammar per sentence) that requests
        // regularly ran past a 60s timeout and failed outright. Smaller batches finish faster
        // and more reliably; the 2-model rotation (40 requests/day) still comfortably covers
        // a full book at this size.
        private const val BATCH_SIZE = 150

        // Comfortably under every rotated model's RPM (5-10) regardless of which comes next.
        private const val RATE_LIMIT_DELAY_MS = 15_000L
    }
}
