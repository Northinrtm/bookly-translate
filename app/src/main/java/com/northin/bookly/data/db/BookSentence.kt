package com.northin.bookly.data.db

import androidx.room.Entity

/** One sentence at a fixed position within a book, in reading order. */
@Entity(
    tableName = "book_sentences",
    primaryKeys = ["bookId", "sentenceIndex"],
)
data class BookSentence(
    val bookId: String,
    val sentenceIndex: Int,
    /** References [BookBlock.blockIndex] of the HEADING/PARAGRAPH block this sentence belongs to. */
    val blockIndex: Int,
    val originalText: String,
    val textHash: String,
)
