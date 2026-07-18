package com.northin.bookly.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.northin.bookly.data.db.Book
import com.northin.bookly.data.db.ProcessingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Book) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val books by viewModel.books.collectAsState()
    val nativeLanguage by viewModel.nativeLanguage.collectAsState()
    val pendingLanguagePrompt by viewModel.pendingLanguagePrompt.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFilePicked)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bookly") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Import book")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books yet — tap + to import an EPUB, PDF, TXT or FB2 file")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            onClick = { onBookClick(book) },
                            onDeleteClick = { viewModel.requestDelete(book) },
                        )
                    }
                }
            }

            if (isImporting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Importing book…")
                    }
                }
            }
        }
    }

    if (nativeLanguage == null) {
        NativeLanguageDialog(onChosen = viewModel::setNativeLanguage)
    }

    pendingLanguagePrompt?.let { prepared ->
        LanguagePickerDialog(
            title = "What language is \"${prepared.parsed.title}\" written in?",
            onChosen = viewModel::onBookLanguageChosen,
            onDismiss = viewModel::dismissLanguagePrompt,
        )
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") }
            },
            title = { Text("Delete \"${book.title}\"?") },
            text = { Text("This removes the book and its translation progress from this device.") },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Couldn't import book") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit, onDeleteClick: () -> Unit) {
    val isComplete = book.processingStatus == ProcessingStatus.COMPLETE

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick)) {
        Column {
            ListItem(
                headlineContent = { Text(book.title) },
                supportingContent = if (isComplete) null else { { Text(progressLabel(book)) } },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isComplete) {
                            Text(
                                "Translated",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        IconButton(onClick = onDeleteClick) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete book")
                        }
                    }
                },
            )
            if (!isComplete) {
                val progress = if (book.totalSentences > 0) {
                    book.processedSentences.toFloat() / book.totalSentences
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun progressLabel(book: Book): String = when (book.processingStatus) {
    ProcessingStatus.COMPLETE -> ""
    ProcessingStatus.NOT_STARTED -> "Queued for translation"
    ProcessingStatus.IN_PROGRESS -> {
        val percent = if (book.totalSentences > 0) {
            book.processedSentences * 100 / book.totalSentences
        } else {
            0
        }
        "Translating: $percent%"
    }
}

@Composable
private fun LanguagePickerDialog(
    title: String,
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CommonLanguages.all.forEach { (code, name) ->
                    TextButton(onClick = { onChosen(code) }) { Text(name) }
                }
            }
        },
    )
}

@Composable
private fun NativeLanguageDialog(onChosen: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("What's your native language?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Translations and grammar explanations will be written in this language.")
                CommonLanguages.all.forEach { (code, name) ->
                    TextButton(onClick = { onChosen(code) }) { Text(name) }
                }
            }
        },
    )
}
