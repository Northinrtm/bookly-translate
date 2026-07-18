package com.northin.bookly.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val filePath: String,
    val sourceLanguage: String,
    val processingStatus: ProcessingStatus = ProcessingStatus.NOT_STARTED,
    val processedSentences: Int = 0,
    val totalSentences: Int = 0,
    /** Index into book_blocks — where the reader last was, restored when the book is reopened. */
    val lastReadBlockIndex: Int = 0,
)
