package com.northin.bookly.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.northin.bookly.data.db.AppDatabase
import com.northin.bookly.translation.TranslationService

class BooklyWorkerFactory(
    private val database: AppDatabase,
    private val translationService: TranslationService,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        BookProcessingWorker::class.java.name ->
            BookProcessingWorker(appContext, workerParameters, database, translationService)
        else -> null
    }
}
