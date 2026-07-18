package com.northin.bookly.data.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.Base64

private val HEADING_TAGS = setOf("title", "subtitle")
// "v" is a verse line inside <stanza>/<poem> — FB2's poetry doesn't use <p> at all.
private val BLOCK_TAGS = HEADING_TAGS + setOf("p", "v", "image")

class Fb2BookParser : BookParser {
    override fun parse(file: File, assetsDir: File): ParsedBook {
        val doc = Jsoup.parse(file, "UTF-8", "", Parser.xmlParser())

        val title = doc.getElementsByTag("book-title").firstOrNull()?.text()
            ?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        val languageHint = doc.getElementsByTag("lang").firstOrNull()?.text()
            ?.takeIf { it.isNotBlank() }

        val binariesById = doc.getElementsByTag("binary").associateBy { it.attr("id") }

        val blocks = mutableListOf<BookBlockContent>()
        var imageCounter = 0
        val emittedBinaryIds = mutableSetOf<String>()

        fun emitImageById(id: String) {
            if (!emittedBinaryIds.add(id)) return
            val binary = binariesById[id] ?: return
            val bytes = runCatching { Base64.getMimeDecoder().decode(binary.text().trim()) }
                .getOrNull() ?: return
            val extension = binary.attr("content-type").substringAfter('/', "img")
            val savedFile = File(assetsDir, "image_${imageCounter++}.$extension")
            savedFile.writeBytes(bytes)
            blocks += BookBlockContent.Image(savedFile.path)
        }

        fun hrefOf(element: Element): String = element.attr("xlink:href")
            .ifBlank { element.attr("l:href") }
            .ifBlank { element.attr("href") }
            .removePrefix("#")

        // The cover lives in <description>, outside the main <body> the loop below walks.
        doc.getElementsByTag("coverpage").firstOrNull()
            ?.getElementsByTag("image")?.firstOrNull()
            ?.let { hrefOf(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let(::emitImageById)

        // Footnote/comment bodies carry a "name" attribute; the main text body doesn't.
        val allBodies = doc.getElementsByTag("body")
        val mainBodies = allBodies.filterNot { it.hasAttr("name") }
        val bodies = mainBodies.ifEmpty { allBodies }

        for (body in bodies) {
            val elements = collectElementsInOrder(body, BLOCK_TAGS)
                // title/subtitle wrap their own <p> children — don't double-count those as paragraphs.
                .filterNot { element ->
                    element.tagName().equals("p", ignoreCase = true) &&
                        element.parents().any { it.tagName().lowercase() in HEADING_TAGS }
                }

            for (element in elements) {
                when (element.tagName().lowercase()) {
                    "image" -> {
                        val href = hrefOf(element)
                        if (href.isNotEmpty()) emitImageById(href)
                    }
                    "title", "subtitle" -> {
                        val text = element.text().trim()
                        if (text.isNotEmpty()) blocks += BookBlockContent.Heading(text)
                    }
                    else -> {
                        val text = element.text().trim()
                        if (text.isNotEmpty()) blocks += BookBlockContent.Paragraph(text)
                    }
                }
            }
        }

        return ParsedBook(title = title, languageHint = languageHint, blocks = blocks)
    }
}
