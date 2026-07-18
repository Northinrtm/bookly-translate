package com.northin.bookly.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Thrown when a model can't serve this request right now — quota exhausted (429) or the
 *  model itself is unavailable/retired for this project (404) — so the next model in the
 *  rotation should be tried instead of failing the whole batch. */
private class GeminiModelUnavailableException(message: String) : IOException(message)

/**
 * Calls the Gemini API directly from the device using a Gemini Developer API key
 * (https://aistudio.google.com/apikey), asking for translation + a grammar explanation
 * for a batch of sentences in one request, as a schema-constrained JSON array.
 *
 * There is no backend proxy here: for a personal, undistributed app the key can't leak to
 * anyone but the device owner, so hiding it behind a server buys no real security — see
 * project notes for the trade-off if this app is ever published.
 */
class GeminiTranslationService(
    private val apiKey: String,
    // Per-model daily quota varies a lot (confirmed via aistudio.google.com/rate-limits' real
    // usage graph, not just docs): gemini-3.1-flash-lite alone gets 500 RPD, while the 3-flash/
    // 3.5-flash pair caps at 20 each. gemini-2.5-flash(-lite) intermittently 404 as "no longer
    // available to new users" but do go through some of the time (real usage shows 2/20 and
    // 13/20) — treated as transiently unavailable (skip to next model), not removed outright.
    // Rotating spreads load across all of their independent daily budgets; on a 429 or 404 from
    // one, the next model in the list is tried immediately within the same call.
    private val models: List<String> = listOf(
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-3-flash-preview",
    ),
) : TranslationService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // A large batch can take several minutes for the model to fully generate — 60s was
        // too short and silently timed out every single batch, so nothing ever actually got
        // translated even though the pipeline looked like it was running.
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    @Volatile
    private var modelIndex = 0

    override suspend fun translateBatch(
        sentences: List<SentenceToTranslate>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<SentenceTranslationResult> {
        if (sentences.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            withModelRotation { model ->
                val body = buildTranslateRequestBody(sentences, sourceLanguage, targetLanguage)
                parseTranslateResponse(postJson(model, body))
            }
        }
    }

    override suspend fun explainWord(
        word: String,
        sentenceContext: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): WordExplanation = withContext(Dispatchers.IO) {
        withModelRotation { model ->
            val body = buildExplainWordRequestBody(word, sentenceContext, sourceLanguage, targetLanguage)
            parseWordExplanationResponse(postJson(model, body))
        }
    }

    override suspend fun explainImage(
        imageFile: File,
        sourceLanguage: String,
        targetLanguage: String,
    ): ImageTextExplanation = withContext(Dispatchers.IO) {
        val bytes = imageFile.readBytes()
        val mimeType = mimeTypeFor(imageFile)
        withModelRotation { model ->
            val body = buildExplainImageRequestBody(bytes, mimeType, sourceLanguage, targetLanguage)
            parseImageExplanationResponse(postJson(model, body))
        }
    }

    /** Tries each model in rotation in turn, moving on immediately if one is out of quota or
     *  unavailable (429/404), until one succeeds or all of them have failed. */
    private fun <T> withModelRotation(call: (model: String) -> T): T {
        var lastError: IOException? = null
        repeat(models.size) {
            val model = nextModel()
            try {
                return call(model)
            } catch (e: GeminiModelUnavailableException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No Gemini models configured")
    }

    private fun nextModel(): String = synchronized(this) {
        val model = models[modelIndex % models.size]
        modelIndex++
        model
    }

    private fun postJson(model: String, body: JSONObject): String {
        val request = Request.Builder()
            .url("$ENDPOINT/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (response.code == 429 || response.code == 404) {
                throw GeminiModelUnavailableException("$model unavailable (${response.code}): $responseBody")
            }
            if (!response.isSuccessful) {
                throw IOException("Gemini request failed ($model): ${response.code} $responseBody")
            }
            responseBody
        }
    }

    private fun buildTranslateRequestBody(
        sentences: List<SentenceToTranslate>,
        sourceLanguage: String,
        targetLanguage: String,
    ): JSONObject {
        val sentencesJson = JSONArray().apply {
            sentences.forEach { sentence ->
                put(
                    JSONObject()
                        .put("index", sentence.sentenceIndex)
                        .put("text", sentence.text),
                )
            }
        }

        val prompt = """
            You are a translation and grammar tutor for language learners.
            The JSON array below contains sentences from a book, in the original language
            "$sourceLanguage", each with a stable "index". For every sentence, translate it
            into "$targetLanguage" and write a clear grammar explanation, written in
            "$targetLanguage".

            When writing the grammar explanation:
            - Be concise overall: short clauses, not long paragraphs per word.
            - Quote the original "$sourceLanguage" words or phrases you are explaining
              (not their "$targetLanguage" translation), then briefly explain their
              grammatical role and what they mean.
            - Always explain prepositions, in one short clause each — what it means and why
              it's used here.
            - Always explain articles ("a", "an", "the", or their absence), in one short
              clause each — why that specific article is used here.

            Sentences:
            $sentencesJson
        """.trimIndent()

        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("index", JSONObject().put("type", "INTEGER"))
                    .put("translation", JSONObject().put("type", "STRING"))
                    .put("grammarExplanation", JSONObject().put("type", "STRING")),
            )
            .put("required", JSONArray(listOf("index", "translation", "grammarExplanation")))

        val responseSchema = JSONObject()
            .put("type", "ARRAY")
            .put("items", itemSchema)

        return buildGenerateContentRequest(prompt, responseSchema)
    }

    private fun parseTranslateResponse(responseText: String): List<SentenceTranslationResult> {
        val resultsJson = JSONArray(extractResponseText(responseText))
        return buildList {
            for (i in 0 until resultsJson.length()) {
                val item = resultsJson.getJSONObject(i)
                add(
                    SentenceTranslationResult(
                        sentenceIndex = item.getInt("index"),
                        translation = item.getString("translation"),
                        grammarExplanation = item.getString("grammarExplanation"),
                    ),
                )
            }
        }
    }

    private fun buildExplainWordRequestBody(
        word: String,
        sentenceContext: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): JSONObject {
        val prompt = """
            You are a language tutor. A learner long-pressed the word/phrase "$word" while
            reading this sentence, in the original language "$sourceLanguage":
            "$sentenceContext"

            Explain "$word" for them, in "$targetLanguage", concisely. Give:
            - one or more translation options for it in this specific context (usually one is
              enough; give more only when genuinely different valid renderings exist),
            - its part of speech,
            - its exact grammatical form (tense, case, number, mood — whatever applies),
            - its dictionary/base form (infinitive for verbs, nominative singular for nouns,
              etc.) — if "$word" is already in that base form, just repeat it unchanged,
            - any other short, useful note (idiom, nuance, irregularity) — leave empty if none.
        """.trimIndent()

        val responseSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "translations",
                        JSONObject()
                            .put("type", "ARRAY")
                            .put("items", JSONObject().put("type", "STRING")),
                    )
                    .put("partOfSpeech", JSONObject().put("type", "STRING"))
                    .put("grammaticalForm", JSONObject().put("type", "STRING"))
                    .put("baseForm", JSONObject().put("type", "STRING"))
                    .put("notes", JSONObject().put("type", "STRING")),
            )
            .put(
                "required",
                JSONArray(listOf("translations", "partOfSpeech", "grammaticalForm", "baseForm", "notes")),
            )

        return buildGenerateContentRequest(prompt, responseSchema)
    }

    private fun parseWordExplanationResponse(responseText: String): WordExplanation {
        val json = JSONObject(extractResponseText(responseText))
        val translationsJson = json.getJSONArray("translations")
        return WordExplanation(
            translations = List(translationsJson.length()) { translationsJson.getString(it) },
            partOfSpeech = json.getString("partOfSpeech"),
            grammaticalForm = json.getString("grammaticalForm"),
            baseForm = json.getString("baseForm"),
            notes = json.optString("notes"),
        )
    }

    private fun buildGenerateContentRequest(prompt: String, responseSchema: JSONObject): JSONObject =
        buildGenerateContentRequest(JSONArray().put(JSONObject().put("text", prompt)), responseSchema)

    private fun buildGenerateContentRequest(parts: JSONArray, responseSchema: JSONObject): JSONObject {
        val generationConfig = JSONObject()
            // This task doesn't need extended reasoning — disabling it cuts token usage per
            // request substantially, which matters a lot against the free tier's daily cap.
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            .put("responseMimeType", "application/json")
            .put("responseSchema", responseSchema)

        val content = JSONObject().put("parts", parts)

        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", generationConfig)
    }

    private fun buildExplainImageRequestBody(
        imageBytes: ByteArray,
        mimeType: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): JSONObject {
        val prompt = """
            This image is an illustration from a book written in "$sourceLanguage". If it
            contains any text (captions, labels, signs, dialogue, etc.), break it down into
            individual words or short phrases as they appear, and give each one's translation
            into "$targetLanguage". If the image has no text at all, say so.
        """.trimIndent()

        val imagePart = JSONObject()
            .put(
                "inline_data",
                JSONObject()
                    .put("mime_type", mimeType)
                    .put("data", Base64.getEncoder().encodeToString(imageBytes)),
            )
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(imagePart)

        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("original", JSONObject().put("type", "STRING"))
                    .put("translation", JSONObject().put("type", "STRING")),
            )
            .put("required", JSONArray(listOf("original", "translation")))

        val responseSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("hasText", JSONObject().put("type", "BOOLEAN"))
                    .put("items", JSONObject().put("type", "ARRAY").put("items", itemSchema)),
            )
            .put("required", JSONArray(listOf("hasText", "items")))

        return buildGenerateContentRequest(parts, responseSchema)
    }

    private fun parseImageExplanationResponse(responseText: String): ImageTextExplanation {
        val json = JSONObject(extractResponseText(responseText))
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        val items = List(itemsJson.length()) { i ->
            val item = itemsJson.getJSONObject(i)
            ImageTextItem(original = item.getString("original"), translation = item.getString("translation"))
        }
        return ImageTextExplanation(
            hasText = json.getBoolean("hasText"),
            items = items,
        )
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun extractResponseText(responseText: String): String {
        val root = JSONObject(responseText)
        val firstCandidate = root.getJSONArray("candidates").getJSONObject(0)
        return firstCandidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
