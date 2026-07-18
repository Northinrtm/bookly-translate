package com.northin.bookly.data.parser

object BookParserRegistry {
    private val parsersByExtension = mapOf(
        "txt" to TxtBookParser(),
        "fb2" to Fb2BookParser(),
        "epub" to EpubBookParser(),
        "pdf" to PdfBookParser(),
    )

    fun parserFor(fileName: String): BookParser {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return parsersByExtension[extension]
            ?: throw IllegalArgumentException("Unsupported book format: .$extension")
    }
}
