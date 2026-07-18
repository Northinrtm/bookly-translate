package com.northin.bookly.data.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF has no semantic paragraph/sentence markup like EPUB or FB2 — this pulls the raw text layer
 * and falls back to the same blank-line heuristic [TxtBookParser] uses. Scanned PDFs with no text
 * layer will yield no blocks at all; multi-column layouts may interleave oddly.
 */
class PdfBookParser : BookParser {
    override fun parse(file: File, assetsDir: File): ParsedBook {
        PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper().apply {
                paragraphEnd = "\n\n"
            }
            val text = stripper.getText(document)

            val blocks = text.split(Regex("\\n\\s*\\n"))
                .map { it.replace('\n', ' ').trim() }
                .filter { it.isNotEmpty() }
                .map { BookBlockContent.Paragraph(it) }

            val title = document.documentInformation?.title?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension

            return ParsedBook(title = title, languageHint = null, blocks = blocks)
        }
    }
}
