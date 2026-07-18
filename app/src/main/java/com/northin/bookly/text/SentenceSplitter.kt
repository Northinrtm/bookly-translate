package com.northin.bookly.text

import java.text.BreakIterator
import java.util.Locale

/** Splits a paragraph of text into sentences, on-device, without calling any AI. */
object SentenceSplitter {
    fun split(text: String, locale: Locale = Locale.getDefault()): List<String> {
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)

        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
            }
            start = end
            end = iterator.next()
        }
        return sentences
    }
}
