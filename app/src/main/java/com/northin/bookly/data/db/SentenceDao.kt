package com.northin.bookly.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SentenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookSentences(sentences: List<BookSentence>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslations(translations: List<SentenceTranslation>)

    @Query(
        """
        SELECT bs.* FROM book_sentences bs
        LEFT JOIN sentence_translations st
            ON st.textHash = bs.textHash
            AND st.sourceLanguage = :sourceLanguage
            AND st.targetLanguage = :targetLanguage
        WHERE bs.bookId = :bookId AND st.textHash IS NULL
        ORDER BY bs.sentenceIndex
        LIMIT :limit
        """,
    )
    suspend fun getUntranslatedSentences(
        bookId: String,
        sourceLanguage: String,
        targetLanguage: String,
        limit: Int,
    ): List<BookSentence>

    @Query(
        """
        SELECT st.* FROM sentence_translations st
        INNER JOIN book_sentences bs ON bs.textHash = st.textHash
        WHERE bs.bookId = :bookId AND bs.sentenceIndex = :sentenceIndex
          AND st.sourceLanguage = :sourceLanguage AND st.targetLanguage = :targetLanguage
        LIMIT 1
        """,
    )
    suspend fun getTranslationForSentence(
        bookId: String,
        sentenceIndex: Int,
        sourceLanguage: String,
        targetLanguage: String,
    ): SentenceTranslation?

    @Query("SELECT COUNT(*) FROM book_sentences WHERE bookId = :bookId")
    suspend fun countSentences(bookId: String): Int

    @Query("SELECT * FROM book_sentences WHERE bookId = :bookId ORDER BY sentenceIndex")
    suspend fun getAllForBook(bookId: String): List<BookSentence>

    @Query("DELETE FROM book_sentences WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("SELECT DISTINCT textHash FROM book_sentences WHERE bookId = :bookId")
    suspend fun getTextHashesForBook(bookId: String): List<String>

    @Query(
        """
        DELETE FROM sentence_translations
        WHERE textHash IN (:textHashes)
          AND textHash NOT IN (SELECT textHash FROM book_sentences)
        """,
    )
    suspend fun deleteOrphanedTranslations(textHashes: List<String>)

    @Query(
        """
        SELECT COUNT(*) FROM book_sentences bs
        LEFT JOIN sentence_translations st
            ON st.textHash = bs.textHash
            AND st.sourceLanguage = :sourceLanguage
            AND st.targetLanguage = :targetLanguage
        WHERE bs.bookId = :bookId AND st.textHash IS NULL
        """,
    )
    suspend fun countUntranslatedSentences(
        bookId: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): Int
}
