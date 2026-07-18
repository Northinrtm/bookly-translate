package com.northin.bookly.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

private const val SENTENCE_TAG = "sentence"

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }

    val blocks by viewModel.blocks.collectAsState()
    val selected by viewModel.selectedSentence.collectAsState()
    val wordDetail by viewModel.wordDetail.collectAsState()
    val imageDetail by viewModel.imageDetail.collectAsState()
    val listState = rememberLazyListState()

    // Jump to the last reading position once, as soon as the book's blocks are loaded.
    var hasRestoredScroll by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(bookId, blocks) {
        if (!hasRestoredScroll && blocks.isNotEmpty()) {
            val target = viewModel.initialScrollIndex.coerceIn(0, blocks.lastIndex)
            listState.scrollToItem(target)
            hasRestoredScroll = true
        }
    }

    // Persist the reading position as the user scrolls, and once more on leaving the screen.
    val onSave = rememberUpdatedState(viewModel::saveReadingPosition)
    LaunchedEffect(bookId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { index -> onSave.value(index) }
    }
    DisposableEffect(bookId) {
        onDispose { onSave.value(listState.firstVisibleItemIndex) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            items(blocks) { block ->
                when (block) {
                    is ReaderBlock.Image -> AsyncImage(
                        model = File(block.path),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            .pointerInput(block.path) {
                                detectTapGestures(
                                    onLongPress = { viewModel.onImageLongPress(block.path) },
                                )
                            },
                    )
                    is ReaderBlock.Heading -> TextBlockWithInlineTranslation(
                        sentences = block.sentences,
                        selected = selected,
                        onSentenceClick = viewModel::onSentenceClick,
                        onWordLongPress = viewModel::onWordLongPress,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    is ReaderBlock.Paragraph -> TextBlockWithInlineTranslation(
                        sentences = block.sentences,
                        selected = selected,
                        onSentenceClick = viewModel::onSentenceClick,
                        onWordLongPress = viewModel::onWordLongPress,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    wordDetail?.let { state ->
        WordDetailDialog(state, onDismiss = viewModel::dismissWordDetail)
    }
    imageDetail?.let { state ->
        ImageDetailDialog(state, onDismiss = viewModel::dismissImageDetail)
    }
}

@Composable
private fun TextBlockWithInlineTranslation(
    sentences: List<SentenceUiState>,
    selected: SentenceDetail?,
    onSentenceClick: (SentenceUiState) -> Unit,
    onWordLongPress: (word: String, sentenceContext: String) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SentenceFlowText(
            sentences = sentences,
            onSentenceClick = onSentenceClick,
            onWordLongPress = onWordLongPress,
            style = style,
        )

        val detailForThisBlock = selected?.takeIf { detail ->
            sentences.any { it.index == detail.sentenceIndex }
        }
        detailForThisBlock?.let { detail ->
            SentenceDetailCard(detail, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SentenceFlowText(
    sentences: List<SentenceUiState>,
    onSentenceClick: (SentenceUiState) -> Unit,
    onWordLongPress: (word: String, sentenceContext: String) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(sentences) {
        AnnotatedString.Builder().apply {
            sentences.forEachIndexed { i, sentence ->
                val start = length
                append(sentence.text)
                addStringAnnotation(tag = SENTENCE_TAG, annotation = i.toString(), start = start, end = length)
                if (i != sentences.lastIndex) append(" ")
            }
        }.toAnnotatedString()
    }
    var layoutResult by remember(sentences) { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = modifier.fillMaxWidth().pointerInput(sentences) {
            detectTapGestures(
                onTap = { offset ->
                    val position = layoutResult?.getOffsetForPosition(offset) ?: return@detectTapGestures
                    annotated.getStringAnnotations(SENTENCE_TAG, position, position).firstOrNull()?.let { annotation ->
                        onSentenceClick(sentences[annotation.item.toInt()])
                    }
                },
                onLongPress = { offset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val position = layout.getOffsetForPosition(offset)
                    val sentenceIndex = annotated.getStringAnnotations(SENTENCE_TAG, position, position)
                        .firstOrNull()?.item?.toIntOrNull() ?: return@detectTapGestures
                    val wordRange = layout.getWordBoundary(position)
                    val word = annotated.text.substring(wordRange.start, wordRange.end).trim()
                    if (word.isNotEmpty()) {
                        onWordLongPress(word, sentences[sentenceIndex].text)
                    }
                },
            )
        },
    )
}

@Composable
private fun WordDetailDialog(state: WordDetailState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(state.word) },
        text = {
            when (state) {
                is WordDetailState.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Looking up…")
                }
                is WordDetailState.Failed -> Text("Couldn't look up this word: ${state.message}")
                is WordDetailState.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val translations = state.explanation.translations
                    val translationsText = if (translations.size > 1) {
                        translations.joinToString("\n") { "• $it" }
                    } else {
                        translations.firstOrNull().orEmpty()
                    }
                    LabeledDetail(if (translations.size > 1) "Translations" else "Translation", translationsText)
                    LabeledDetail("Part of speech", state.explanation.partOfSpeech)
                    LabeledDetail("Form", state.explanation.grammaticalForm)
                    if (!state.explanation.baseForm.equals(state.word, ignoreCase = true)) {
                        LabeledDetail("Dictionary form", state.explanation.baseForm)
                    }
                    if (state.explanation.notes.isNotBlank()) {
                        LabeledDetail("Notes", state.explanation.notes)
                    }
                }
            }
        },
    )
}

@Composable
private fun ImageDetailDialog(state: ImageDetailState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = File(state.imagePath),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                )
                when (state) {
                    is ImageDetailState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Reading image…")
                    }
                    is ImageDetailState.Failed -> Text("Couldn't read this image: ${state.message}")
                    is ImageDetailState.Loaded -> if (state.explanation.hasText) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.explanation.items.forEach { item ->
                                Text("${item.original} — ${item.translation}")
                            }
                        }
                    } else {
                        Text("No text found in this image.")
                    }
                }
            }
        },
    )
}

@Composable
private fun LabeledDetail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value)
    }
}

@Composable
private fun SentenceDetailCard(detail: SentenceDetail, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (detail.translation == null) {
                Text("Still translating — check back in a bit.")
            } else {
                Text("Translation", style = MaterialTheme.typography.labelLarge)
                Text(detail.translation)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Grammar", style = MaterialTheme.typography.labelLarge)
                Text(detail.grammarExplanation.orEmpty())
            }
        }
    }
}
