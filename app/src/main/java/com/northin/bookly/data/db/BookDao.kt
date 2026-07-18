package com.northin.bookly.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book)

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observe(bookId: String): Flow<Book?>

    @Query("SELECT * FROM books ORDER BY title")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT id FROM books")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun get(bookId: String): Book?

    @Query(
        """
        UPDATE books
        SET processingStatus = :status, processedSentences = :processed, totalSentences = :total
        WHERE id = :bookId
        """,
    )
    suspend fun updateProgress(bookId: String, status: ProcessingStatus, processed: Int, total: Int)

    @Query("UPDATE books SET lastReadBlockIndex = :blockIndex WHERE id = :bookId")
    suspend fun updateLastReadPosition(bookId: String, blockIndex: Int)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun delete(bookId: String)
}
