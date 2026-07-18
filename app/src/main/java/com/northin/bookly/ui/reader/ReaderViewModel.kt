package com.northin.bookly.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.northin.bookly.BooklyApplication
import com.northin.bookly.data.db.BlockType
import com.northin.bookly.data.db.Book
import com.northin.bookly.translation.ImageTextExplanation
import com.northin.bookly.translation.WordExplanation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class SentenceUiState(val index: Int, val text: String)

sealed class ReaderBlock {
    data class Heading(val sentences: List<SentenceUiState>) : ReaderBlock()
    data class Paragraph(val sentences: List<SentenceUiState>) : ReaderBlock()
    data class Image(val path: String) : ReaderBlock()
}

data class SentenceDetail(
    val sentenceIndex: Int,
    val originalText: String,
    /** Null while the sentence hasn't been translated yet — still queued in the background. */
    val translation: String?,
    val grammarExplanation: String?,
)

sealed class WordDetailState(val word: String) {
    class Loading(word: String) : WordDetailState(word)
    class Loaded(word: String, val explanation: WordExplanation) : WordDetailState(word)
    class Failed(word: String, val message: String) : WordDetailState(word)
}

sealed class ImageDetailState(val imagePath: String) {
    class Loading(imagePath: String) : ImageDetailState(imagePath)
    class Loaded(imagePath: String, val explanation: ImageTextExplanation) : ImageDetailState(imagePath)
    class Failed(imagePath: String, val message: String) : ImageDetailState(imagePath)
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val booklyApp = app as BooklyApplication
    private var loadedBookId: String? = null
    private var book: Book? = null

    private val _blocks = MutableStateFlow<List<ReaderBlock>>(emptyList())
    val blocks: StateFlow<List<ReaderBlock>> = _blocks

    /** Set before [blocks] first becomes non-empty — safe to read once that happens. */
    var initialScrollIndex: Int = 0
        private set

    private val _selectedSentence = MutableStateFlow<SentenceDetail?>(null)
    val selectedSentence: StateFlow<SentenceDetail?> = _selectedSentence

    private val _wordDetail = MutableStateFlow<WordDetailState?>(null)
    val wordDetail: StateFlow<WordDetailState?> = _wordDetail

    private val _imageDetail = MutableStateFlow<ImageDetailState?>(null)
    val imageDetail: StateFlow<ImageDetailState?> = _imageDetail

    fun load(bookId: String) {
        if (loadedBookId == bookId) return
        loadedBookId = bookId
        viewModelScope.launch {
            val loadedBook = booklyApp.database.bookDao().get(bookId) ?: return@launch
            book = loadedBook
            initialScrollIndex = loadedBook.lastReadBlockIndex

            val sentencesByBlock = booklyApp.database.sentenceDao().getAllForBook(bookId)
                .groupBy { it.blockIndex }

            _blocks.value = booklyApp.database.bookBlockDao().getAllForBook(bookId).map { block ->
                val sentences = sentencesByBlock[block.blockIndex].orEmpty()
                    .map { SentenceUiState(it.sentenceIndex, it.originalText) }
                when (block.type) {
                    BlockType.IMAGE -> ReaderBlock.Image(block.imagePath.orEmpty())
                    BlockType.HEADING -> ReaderBlock.Heading(sentences)
                    BlockType.PARAGRAPH -> ReaderBlock.Paragraph(sentences)
                }
            }
        }
    }

    fun onSentenceClick(sentence: SentenceUiState) {
        // Tapping the already-expanded sentence again collapses it.
        if (_selectedSentence.value?.sentenceIndex == sentence.index) {
            _selectedSentence.value = null
            return
        }

        val book = book ?: return
        viewModelScope.launch {
            val targetLanguage = booklyApp.userPreferences.nativeLanguage.first() ?: return@launch
            val translation = booklyApp.database.sentenceDao().getTranslationForSentence(
                bookId = book.id,
                sentenceIndex = sentence.index,
                sourceLanguage = book.sourceLanguage,
                targetLanguage = targetLanguage,
            )
            _selectedSentence.value = SentenceDetail(
                sentenceIndex = sentence.index,
                originalText = sentence.text,
                translation = translation?.translation,
                grammarExplanation = translation?.grammarExplanation,
            )
        }
    }

    fun dismissSentenceDetail() {
        _selectedSentence.value = null
    }

    fun onWordLongPress(word: String, sentenceContext: String) {
        val book = book ?: return
        viewModelScope.launch {
            _wordDetail.value = WordDetailState.Loading(word)
            try {
                val targetLanguage = booklyApp.userPreferences.nativeLanguage.first()
                    ?: return@launch
                val explanation = booklyApp.translationService.explainWord(
                    word = word,
                    sentenceContext = sentenceContext,
                    sourceLanguage = book.sourceLanguage,
                    targetLanguage = targetLanguage,
                )
                _wordDetail.value = WordDetailState.Loaded(word, explanation)
            } catch (e: Exception) {
                _wordDetail.value = WordDetailState.Failed(word, e.message ?: "Lookup failed")
            }
        }
    }

    fun dismissWordDetail() {
        _wordDetail.value = null
    }

    fun onImageLongPress(imagePath: String) {
        val book = book ?: return
        viewModelScope.launch {
            _imageDetail.value = ImageDetailState.Loading(imagePath)
            try {
                val targetLanguage = booklyApp.userPreferences.nativeLanguage.first()
                    ?: return@launch
                val explanation = booklyApp.translationService.explainImage(
                    imageFile = File(imagePath),
                    sourceLanguage = book.sourceLanguage,
                    targetLanguage = targetLanguage,
                )
                _imageDetail.value = ImageDetailState.Loaded(imagePath, explanation)
            } catch (e: Exception) {
                _imageDetail.value = ImageDetailState.Failed(imagePath, e.message ?: "Lookup failed")
            }
        }
    }

    fun dismissImageDetail() {
        _imageDetail.value = null
    }

    fun saveReadingPosition(blockIndex: Int) {
        val book = book ?: return
        viewModelScope.launch {
            booklyApp.database.bookDao().updateLastReadPosition(book.id, blockIndex)
        }
    }
}
