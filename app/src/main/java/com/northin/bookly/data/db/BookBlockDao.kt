package com.northin.bookly.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookBlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<BookBlock>)

    @Query("SELECT * FROM book_blocks WHERE bookId = :bookId ORDER BY blockIndex")
    suspend fun getAllForBook(bookId: String): List<BookBlock>

    @Query("DELETE FROM book_blocks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}
