/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import com.metrolist.shared.network.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

data class AutoEqEntry(
    val name: String,
    val source: String,
    val rig: String?,
    val form: String,
    val path: String,
)

data class AutoEqModel(
    val name: String,
    val entries: List<AutoEqEntry>,
)

object AutoEqCatalog {
    private const val RESULTS_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"

    private val client by lazy { createHttpClient() }
    private val mutex = Mutex()
    private var models: List<AutoEqModel>? = null

    private const val INDEX_CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    suspend fun models(): List<AutoEqModel> =
        mutex.withLock {
            models ?: groupAutoEqModels(index()).also { models = it }
        }

    // A fresh disk copy avoids the network; a stale one is the offline fallback.
    private suspend fun index(): List<AutoEqEntry> {
        val cached =
            withContext(Dispatchers.IO) { runCatching { readAutoEqIndexCache() }.getOrNull() }
                ?.let { (content, fetchedAt) -> parseAutoEqIndex(content).takeIf { it.isNotEmpty() }?.let { it to fetchedAt } }
        val age = Clock.System.now().toEpochMilliseconds() - (cached?.second ?: 0L)
        if (cached != null && age in 0 until INDEX_CACHE_TTL_MILLIS) return cached.first
        return try {
            val content = fetch("$RESULTS_URL/INDEX.md")
            val entries = parseAutoEqIndex(content)
            if (entries.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { writeAutoEqIndexCache(content) } }
            entries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.first ?: throw e
        }
    }

    suspend fun load(entry: AutoEqEntry): EqualizerProfile {
        val fileName = entry.path.substringAfterLast('/') + "%20ParametricEQ.txt"
        val (preamp, bands) = parseParametricEq(fetch("$RESULTS_URL/${entry.path}/$fileName")) ?: throw InvalidEqualizerProfileException()
        return EqualizerProfile(
            id = "autoeq:${entry.path}",
            name = entry.name,
            bands = bands,
            preamp = preamp,
            source = entry.source,
            rig = entry.rig ?: "unknown",
        )
    }

    private suspend fun fetch(url: String): String {
        val response = client.get(url)
        check(response.status.isSuccess()) { "AutoEq request failed: ${response.status}" }
        return response.bodyAsText()
    }
}

internal expect fun autoEqCacheDirectory(): String?

/** Cached INDEX.md and the epoch milliseconds when it was fetched. */
internal expect fun readAutoEqIndexCache(): Pair<String, Long>?

internal expect fun writeAutoEqIndexCache(content: String)

private val indexLine = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?$""")

fun parseAutoEqIndex(markdown: String): List<AutoEqEntry> =
    markdown
        .lineSequence()
        .mapNotNull { line ->
            val (name, path, source, rig) = indexLine.matchEntire(line.trim())?.destructured ?: return@mapNotNull null
            val formDirectory = path.split('/').getOrNull(1) ?: return@mapNotNull null
            AutoEqEntry(name, source, rig.ifEmpty { null }, formDirectory.substringAfterLast("%20"), path)
        }.toList()

fun groupAutoEqModels(entries: List<AutoEqEntry>): List<AutoEqModel> =
    entries
        .groupBy { it.name.replace(Regex("""\s*\([^)]*\)"""), "").trim() }
        .map { (name, entries) -> AutoEqModel(name, entries) }
        .sortedBy { it.name.lowercase() }

fun searchAutoEqModels(
    models: List<AutoEqModel>,
    query: String,
): List<AutoEqModel> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return models
    val words = needle.split(Regex("\\s+"))
    return models
        .filter { model -> model.name.lowercase().let { name -> words.all(name::contains) } }
        .sortedBy { model ->
            val name = model.name.lowercase()
            when {
                name == needle -> 0
                name.startsWith(needle) -> 1
                name.split(' ', '-').any { it.startsWith(words.first()) } -> 2
                else -> 3
            }
        }
}
