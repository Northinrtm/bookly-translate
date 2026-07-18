package com.northin.bookly.data.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val IMAGE_TAGS = setOf("img", "image")
private val BLOCK_TAGS = HEADING_TAGS + setOf("p", "li") + IMAGE_TAGS

class EpubBookParser : BookParser {
    override fun parse(file: File, assetsDir: File): ParsedBook {
        ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: error("Invalid EPUB: missing META-INF/container.xml")
            val containerDoc = zip.getInputStream(containerEntry).use {
                Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
            }
            val opfPath = containerDoc.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
                ?.takeIf { it.isNotBlank() }
                ?: error("Invalid EPUB: missing rootfile full-path")

            val opfEntry = zip.getEntry(opfPath) ?: error("Invalid EPUB: missing OPF at $opfPath")
            val opfDoc = zip.getInputStream(opfEntry).use {
                Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
            }
            val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")

            val title = opfDoc.getElementsByTag("dc:title").firstOrNull()?.text()
                ?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
            val languageHint = opfDoc.getElementsByTag("dc:language").firstOrNull()?.text()
                ?.takeIf { it.isNotBlank() }

            val manifest = opfDoc.getElementsByTag("item")
                .associate { it.attr("id") to it.attr("href") }
            val spineHrefs = opfDoc.getElementsByTag("itemref")
                // linear="no" marks auxiliary docs (e.g. a nav/TOC page) as not part of the
                // main reading order — pulling their text in would dump the table of contents
                // into the book as if it were prose.
                .filterNot { it.attr("linear").equals("no", ignoreCase = true) }
                .mapNotNull { manifest[it.attr("idref")] }

            val blocks = mutableListOf<BookBlockContent>()
            var imageCounter = 0
            val emittedZipPaths = mutableSetOf<String>()

            fun emitImage(zipPath: String) {
                if (!emittedZipPaths.add(zipPath)) return
                val imageEntry = zip.getEntry(zipPath) ?: return
                val bytes = zip.getInputStream(imageEntry).use { it.readBytes() }
                val extension = zipPath.substringAfterLast('.', "img")
                val savedFile = File(assetsDir, "image_${imageCounter++}.$extension")
                savedFile.writeBytes(bytes)
                blocks += BookBlockContent.Image(savedFile.path)
            }

            // The cover often isn't reachable by walking the spine at all (declared only in OPF
            // metadata), or sits behind an <svg><image> wrapper a plain <img> scan would miss —
            // so pull it in explicitly first, ahead of the regular content walk below.
            findCoverHref(opfDoc, manifest)?.let { href ->
                emitImage(resolveZipPath(opfDir, href))
            }

            for (href in spineHrefs) {
                val entryPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val entryDir = entryPath.substringBeforeLast('/', missingDelimiterValue = "")
                val entry = zip.getEntry(entryPath) ?: continue
                val contentDoc = zip.getInputStream(entry).use {
                    Jsoup.parse(it, "UTF-8", "", Parser.htmlParser())
                }

                for (element in collectElementsInOrder(contentDoc.body(), BLOCK_TAGS)) {
                    val tag = element.tagName().lowercase()
                    if (tag in IMAGE_TAGS) {
                        val src = when (tag) {
                            "img" -> element.attr("src")
                            else -> element.attr("xlink:href").ifBlank { element.attr("href") }
                        }
                        if (src.isBlank()) continue
                        emitImage(resolveZipPath(entryDir, src))
                    } else {
                        val text = element.text().trim()
                        if (text.isEmpty()) continue
                        blocks += if (tag in HEADING_TAGS) {
                            BookBlockContent.Heading(text)
                        } else {
                            BookBlockContent.Paragraph(text)
                        }
                    }
                }
            }

            return ParsedBook(title = title, languageHint = languageHint, blocks = blocks)
        }
    }
}

/** EPUB3 `<item properties="cover-image">`, falling back to EPUB2 `<meta name="cover" content="ID">`. */
private fun findCoverHref(opfDoc: Document, manifest: Map<String, String>): String? {
    opfDoc.getElementsByTag("item")
        .firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
        ?.attr("href")?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val coverId = opfDoc.getElementsByTag("meta")
        .firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
        ?.attr("content")
        ?.takeIf { it.isNotBlank() }
        ?: return null

    return manifest[coverId]
}

/** Resolves a relative path (possibly with `../`) against the zip entry that referenced it. */
private fun resolveZipPath(baseDir: String, relative: String): String {
    val cleanRelative = relative.substringBefore('#')
    if (cleanRelative.startsWith("/")) return cleanRelative.removePrefix("/")

    val parts = if (baseDir.isEmpty()) mutableListOf() else baseDir.split('/').toMutableList()
    for (part in cleanRelative.split('/')) {
        when (part) {
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            ".", "" -> Unit
            else -> parts.add(part)
        }
    }
    return parts.joinToString("/")
}
