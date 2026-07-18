# Bookly

An Android reader for learning a language by reading real books. Import an EPUB, FB2, TXT
or PDF; Bookly translates it sentence by sentence in the background and explains the
grammar as you go — tap a sentence for its translation, long-press a word for a deep dive,
long-press an illustration to translate any text in it.

## Features

- **Multi-format import** — EPUB, FB2, TXT, PDF, including cover art and inline illustrations.
- **Background translation** — the whole book is translated sentence by sentence in the
  background via [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager),
  so you can start reading immediately and the rest catches up while you read.
- **Inline translation** — tap a sentence to expand its translation and a grammar
  explanation right underneath it, without covering the rest of the page.
- **Word-level lookup** — long-press any word for its translation, part of speech, and
  exact grammatical form in context.
- **Image lookup** — long-press an illustration to find and translate any text in it.
- **Resumable reading position** — reopens exactly where you left off.
- **Fully offline once translated** — after the background pass finishes, the whole book
  reads without a network connection; only the initial translation pass needs internet.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Room (local storage), WorkManager (background translation), Navigation Compose
- Jsoup (EPUB/FB2 parsing), PdfBox-Android (PDF text extraction), Coil (images)
- [Gemini API](https://ai.google.dev/) for translation, grammar, and word/image lookup,
  called directly from the device — see [Setup](#setup)

## Setup

1. Get a free Gemini API key at [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
2. Add it to `local.properties` (create the file if it doesn't exist):

   ```properties
   GEMINI_API_KEY=your-key-here
   ```

3. Open the project in Android Studio, or build from the command line:

   ```sh
   ./gradlew assembleDebug
   ```

Without a key, the app falls back to a stub translation service so it still builds and
runs — you just won't get real translations.

## Architecture

- `data/parser` — one `BookParser` per format (EPUB/FB2/TXT/PDF), producing a common list
  of content blocks (heading, paragraph, image).
- `data/db` — Room schema: `Book`, `BookBlock`, `BookSentence`, and `SentenceTranslation`
  (translations are cached by sentence text hash, shared across books).
- `data/book/BookImporter` — copies a picked file into app storage, parses it, splits
  paragraphs into sentences, and schedules the background translation worker.
- `worker/BookProcessingWorker` — translates a book in batches until done, resumable
  after interruption, respecting the Gemini free tier's per-model daily quota.
- `translation/GeminiTranslationService` — calls the Gemini API directly (no backend —
  see the note below), rotating across several free-tier models to make the most of
  each one's independent daily quota.
- `ui/library`, `ui/reader` — the book list and the reading screen.

## A note on the API key

There is no backend server here — the Gemini API key ships inside the app and is called
directly from the device. That's a deliberate tradeoff, only sound for a personal,
undistributed build: an API key embedded in an APK can always be extracted by anyone who
has the APK. If this app is ever published or shared, the key needs to move behind a
backend proxy first.

## Known limitations

- Gemini's free tier varies a lot by model (some cap at 20 requests/day, one goes up to
  500) — a long book can take a day or more to fully translate in the background.
- PDF text extraction is heuristic (no real paragraph markup like EPUB has) and finds no
  text at all in scanned PDFs without a text layer.
- Long-pressing an illustration translates text found in it, but can't redraw the image
  with the translation baked in — Gemini's image-generation models aren't available on
  the free tier.
