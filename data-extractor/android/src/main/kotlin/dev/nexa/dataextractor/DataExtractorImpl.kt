package dev.nexa.dataextractor

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import dev.nexa.core.NexaRuntimeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** Thin wrapper around Android's system text classifier. */
public class DataExtractorImpl : DataExtractorSpec {
    private val textClassifier: TextClassifier? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context: Context = NexaRuntimeCore.context().applicationContext
        context.getSystemService(TextClassificationManager::class.java)?.textClassifier
    }
    private val entityConfig: TextClassifier.EntityConfig by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildEntityConfig()
    }
    private val extractionMutex = Mutex()

    override suspend fun extract(text: String): List<ExtractedData> = withContext(Dispatchers.Default) {
        extractionMutex.withLock {
            if (text.isBlank()) return@withLock emptyList()
            val classifier = textClassifier ?: return@withLock emptyList()

            val requestBuilder = TextLinks.Request.Builder(text)
                .setDefaultLocales(LocaleList.getDefault())
            requestBuilder.setEntityConfig(entityConfig)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestBuilder.setReferenceTime(ZonedDateTime.now())
            }

            val links = classifier.generateLinks(requestBuilder.build()).links
            if (links.isEmpty()) return@withLock emptyList()

            val matches = ArrayList<ExtractedData>(links.size)
            for (link in links) {
                var kind: ExtractedDataKind? = null
                var hasTime = false
                var timePrecisionAvailable = false
                for (index in 0 until link.entityCount) {
                    val entity = link.getEntity(index)
                    kind = kindFor(entity)
                    if (kind != null) {
                        hasTime = entity == TextClassifier.TYPE_DATE_TIME
                        timePrecisionAvailable = kind == ExtractedDataKind.date
                        break
                    }
                }
                val detectedKind = kind ?: continue

                val start = link.start
                val end = link.end
                if (start < 0 || end <= start || end > text.length) continue

                matches.add(
                    ExtractedData(
                        kind = detectedKind,
                        text = text.substring(start, end),
                        start = start,
                        length = end - start,
                        // TextClassifier reports date ranges and date/date-time kinds,
                        // but does not expose a normalized instant in TextLinks.
                        timestampMillis = null,
                        hasTime = hasTime,
                        timePrecisionAvailable = timePrecisionAvailable,
                    ),
                )
            }
            matches.sortBy(ExtractedData::start)
            matches
        }
    }

    private fun kindFor(entity: String): ExtractedDataKind? = when (entity) {
        TextClassifier.TYPE_DATE -> ExtractedDataKind.date
        TextClassifier.TYPE_DATE_TIME -> ExtractedDataKind.date
        TextClassifier.TYPE_PHONE -> ExtractedDataKind.phoneNumber
        TextClassifier.TYPE_URL -> ExtractedDataKind.url
        TextClassifier.TYPE_EMAIL -> ExtractedDataKind.email
        TextClassifier.TYPE_ADDRESS -> ExtractedDataKind.address
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun buildEntityConfig(): TextClassifier.EntityConfig {
        val types = listOf(
            TextClassifier.TYPE_DATE,
            TextClassifier.TYPE_DATE_TIME,
            TextClassifier.TYPE_PHONE,
            TextClassifier.TYPE_URL,
            TextClassifier.TYPE_EMAIL,
            TextClassifier.TYPE_ADDRESS,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TextClassifier.EntityConfig.Builder()
                .includeTypesFromTextClassifier(false)
                .setIncludedTypes(types)
                .build()
        } else {
            TextClassifier.EntityConfig.createWithExplicitEntityList(types)
        }
    }
}
