package com.northin.bookly.data.db

import androidx.room.Entity

/** One content block of a book, in reading order — a heading, a paragraph, or a standalone image. */
@Entity(
    tableName = "book_blocks",
    primaryKeys = ["bookId", "blockIndex"],
)
data class BookBlock(
    val bookId: String,
    val blockIndex: Int,
    val type: BlockType,
    /** Set only when [type] is [BlockType.IMAGE] — absolute path to the extracted image file. */
    val imagePath: String? = null,
)
