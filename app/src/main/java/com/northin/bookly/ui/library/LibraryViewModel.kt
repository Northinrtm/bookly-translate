package com.northin.bookly.ui.library

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.northin.bookly.BooklyApplication
import com.northin.bookly.data.book.BookImporter
import com.northin.bookly.data.db.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val booklyApp = app as BooklyApplication

    val books: StateFlow<List<Book>> = booklyApp.database.bookDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nativeLanguage: StateFlow<String?> = booklyApp.userPreferences.nativeLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set once the file is parsed but its language couldn't be determined from its own metadata. */
    private val _pendingLanguagePrompt = MutableStateFlow<BookImporter.PreparedImport?>(null)
    val pendingLanguagePrompt: StateFlow<BookImporter.PreparedImport?> = _pendingLanguagePrompt

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _pendingDelete = MutableStateFlow<Book?>(null)
    val pendingDelete: StateFlow<Book?> = _pendingDelete

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val displayName = resolveDisplayName(uri)
                val prepared = booklyApp.bookImporter.prepareImport(uri, displayName)
                val hint = prepared.parsed.languageHint
                if (hint != null) {
                    completeImport(prepared, hint)
                } else {
                    _pendingLanguagePrompt.value = prepared
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Import failed"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onBookLanguageChosen(sourceLanguage: String) {
        val prepared = _pendingLanguagePrompt.value ?: return
        _pendingLanguagePrompt.value = null
        viewModelScope.launch {
            _isImporting.value = true
            try {
                completeImport(prepared, sourceLanguage)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun dismissLanguagePrompt() {
        _pendingLanguagePrompt.value = null
    }

    fun setNativeLanguage(languageCode: String) {
        viewModelScope.launch { booklyApp.userPreferences.setNativeLanguage(languageCode) }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun requestDelete(book: Book) {
        _pendingDelete.value = book
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val book = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch { booklyApp.bookImporter.deleteBook(book) }
    }

    private suspend fun completeImport(prepared: BookImporter.PreparedImport, sourceLanguage: String) {
        val target = booklyApp.userPreferences.nativeLanguage.first()
        if (target == null) {
            _errorMessage.value = "Set your native language first"
            return
        }
        booklyApp.bookImporter.finishImport(prepared, sourceLanguage, target)
    }

    private fun resolveDisplayName(uri: Uri): String {
        booklyApp.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        return uri.lastPathSegment ?: "book"
    }
}
