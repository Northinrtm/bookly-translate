package com.northin.bookly

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.northin.bookly.data.book.BookImporter
import com.northin.bookly.data.db.AppDatabase
import com.northin.bookly.data.settings.UserPreferences
import com.northin.bookly.translation.GeminiTranslationService
import com.northin.bookly.translation.StubTranslationService
import com.northin.bookly.translation.TranslationService
import com.northin.bookly.worker.BooklyWorkerFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BooklyApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        // The default androidx.startup WorkManagerInitializer is disabled in the manifest —
        // it no longer looks at Configuration.Provider, so it's initialized by hand here instead.
        WorkManager.initialize(this, workManagerConfiguration)
        applicationScope.launch { bookImporter.cleanupOrphanedFiles() }
    }

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val userPreferences: UserPreferences by lazy { UserPreferences(this) }
    val bookImporter: BookImporter by lazy { BookImporter(this, database) }

    // Falls back to the stub until GEMINI_API_KEY is set in local.properties (see its comment).
    val translationService: TranslationService by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotBlank()) GeminiTranslationService(apiKey) else StubTranslationService()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(BooklyWorkerFactory(database, translationService))
            .build()
}
