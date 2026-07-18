package com.northin.bookly.data.parser

import java.io.File

class TxtBookParser : BookParser {
    override fun parse(file: File, assetsDir: File): ParsedBook {
        val text = file.readText(Charsets.UTF_8)
        val blocks = text.split(Regex("\\n\\s*\\n"))
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .map { BookBlockContent.Paragraph(it) }

        return ParsedBook(
            title = file.nameWithoutExtension,
            languageHint = null,
            blocks = blocks,
        )
    }
}
