package com.northin.bookly.data.parser

import java.io.File

sealed class BookBlockContent {
    data class Heading(val text: String) : BookBlockContent()
    data class Paragraph(val text: String) : BookBlockContent()
    /** [imagePath] points at a file already written under the book's assets directory. */
    data class Image(val imagePath: String) : BookBlockContent()
}

data class ParsedBook(
    val title: String,
    /** Language declared in the file's own metadata, if any; null when it must be asked from the user. */
    val languageHint: String?,
    val blocks: List<BookBlockContent>,
)

interface BookParser {
    /** [assetsDir] is where any images found in the book should be written, already created. */
    fun parse(file: File, assetsDir: File): ParsedBook
}
