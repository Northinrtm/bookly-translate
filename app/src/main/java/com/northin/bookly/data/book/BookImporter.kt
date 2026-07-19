package com.northin.bookly.data.book

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.northin.bookly.data.db.AppDatabase
import com.northin.bookly.data.db.BlockType
import com.northin.bookly.data.db.Book
import com.northin.bookly.data.db.BookBlock
import com.northin.bookly.data.db.BookSentence
import com.northin.bookly.data.db.ProcessingStatus
import com.northin.bookly.data.parser.BookBlockContent
import com.northin.bookly.data.parser.BookParserRegistry
import com.northin.bookly.data.parser.ParsedBook
import com.northin.bookly.text.SentenceSplitter
import com.northin.bookly.worker.BookProcessingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Copies a picked book file into app storage, parses it, splits it into sentences and schedules
 * the background translation/grammar pipeline for the whole book.
 *
 * Import is split in two steps because the source language is only known once the file has been
 * parsed (from [ParsedBook.languageHint]) — the caller decides whether that's enough or whether
 * to ask the user, then calls [finishImport] with the language it settled on.
 */
class BookImporter(
    private val context: Context,
    private val database: AppDatabase,
) {
    data class PreparedImport(
        val bookId: String,
        val bookFile: File,
        val parsed: ParsedBook,
    )

    suspend fun prepareImport(sourceUri: Uri, displayName: String): PreparedImport =
        withContext(Dispatchers.IO) {
            val bookId = UUID.randomUUID().toString()
            val extension = displayName.substringAfterLast('.', "")
            val bookFile = copyToLocalStorage(sourceUri, bookId, extension)
            val assetsDir = assetsDirFor(bookId).apply { mkdirs() }
            val parsed = BookParserRegistry.parserFor(displayName).parse(bookFile, assetsDir)
            PreparedImport(bookId, bookFile, parsed)
        }

    suspend fun finishImport(
        prepared: PreparedImport,
        sourceLanguage: String,
        targetLanguage: String,
    ): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val blocks = mutableListOf<BookBlock>()
        val sentences = mutableListOf<BookSentence>()
        var sentenceIndex = 0

        prepared.parsed.blocks.forEachIndexed { blockIndex, block ->
            when (block) {
                is BookBlockContent.Image -> {
                    blocks += BookBlock(prepared.bookId, blockIndex, BlockType.IMAGE, block.imagePath)
                }
                is BookBlockContent.Heading -> {
                    blocks += BookBlock(prepared.bookId, blockIndex, BlockType.HEADING)
                    for (sentence in SentenceSplitter.split(block.text)) {
                        sentences += BookSentence(
                            bookId = prepared.bookId,
                            sentenceIndex = sentenceIndex++,
                            blockIndex = blockIndex,
                            originalText = sentence,
                            textHash = sha256(digest, sentence),
                        )
                    }
                }
                is BookBlockContent.Paragraph -> {
                    blocks += BookBlock(prepared.bookId, blockIndex, BlockType.PARAGRAPH)
                    for (sentence in SentenceSplitter.split(block.text)) {
                        sentences += BookSentence(
                            bookId = prepared.bookId,
                            sentenceIndex = sentenceIndex++,
                            blockIndex = blockIndex,
                            originalText = sentence,
                            textHash = sha256(digest, sentence),
                        )
                    }
                }
            }
        }

        database.bookDao().insert(
            Book(
                id = prepared.bookId,
                title = prepared.parsed.title,
                filePath = prepared.bookFile.path,
                sourceLanguage = sourceLanguage,
                processingStatus = ProcessingStatus.NOT_STARTED,
                processedSentences = 0,
                totalSentences = sentences.size,
            ),
        )
        database.bookBlockDao().insertBlocks(blocks)
        database.sentenceDao().insertBookSentences(sentences)

        enqueueProcessing(prepared.bookId, sourceLanguage, targetLanguage)
        prepared.bookId
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(book.id))

        // Translations are keyed by sentence text hash, not bookId (so identical sentences
        // across books share one cached entry) — grab this book's hashes before its sentence
        // rows are gone, so its own translations can be cleared too, not just left orphaned.
        val textHashes = database.sentenceDao().getTextHashesForBook(book.id)

        database.sentenceDao().deleteForBook(book.id)
        database.bookBlockDao().deleteForBook(book.id)
        database.bookDao().delete(book.id)
        // Only delete translations no other book still references — deleteForBook above already
        // removed this book's own sentence rows, so deleteOrphanedTranslations' NOT IN subquery
        // now only matches hashes truly unused elsewhere. SQLite caps IN (...) params (~999),
        // which a long book's distinct sentence count can exceed — delete in safely-sized chunks.
        textHashes.chunked(900).forEach { chunk ->
            database.sentenceDao().deleteOrphanedTranslations(chunk)
        }

        File(book.filePath).delete()
        assetsDirFor(book.id).deleteRecursively()
    }

    /**
     * Removes any book file/assets directory under `files/books/` that has no matching row in
     * the `books` table — e.g. left behind by a destructive schema migration during development,
     * which wipes tables directly and bypasses [deleteBook].
     */
    suspend fun cleanupOrphanedFiles() = withContext(Dispatchers.IO) {
        val booksDir = File(context.filesDir, "books")
        val files = booksDir.listFiles() ?: return@withContext
        val knownIds = database.bookDao().getAllIds().toSet()
        for (file in files) {
            val id = if (file.name.endsWith("_assets")) file.name.removeSuffix("_assets") else file.nameWithoutExtension
            if (id !in knownIds) {
                file.deleteRecursively()
            }
        }
    }

    private fun copyToLocalStorage(sourceUri: Uri, bookId: String, extension: String): File {
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val destination = File(booksDir, "$bookId.$extension")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open book file: $sourceUri")
        return destination
    }

    private fun assetsDirFor(bookId: String) = File(File(context.filesDir, "books"), "${bookId}_assets")

    private fun enqueueProcessing(bookId: String, sourceLanguage: String, targetLanguage: String) {
        val request = OneTimeWorkRequestBuilder<BookProcessingWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    BookProcessingWorker.KEY_BOOK_ID to bookId,
                    BookProcessingWorker.KEY_SOURCE_LANGUAGE to sourceLanguage,
                    BookProcessingWorker.KEY_TARGET_LANGUAGE to targetLanguage,
                ),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(bookId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun uniqueWorkName(bookId: String) = "book-processing-$bookId"

    private fun sha256(digest: MessageDigest, text: String): String {
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
