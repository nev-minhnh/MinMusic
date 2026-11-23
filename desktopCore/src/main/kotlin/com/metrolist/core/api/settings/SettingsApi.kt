package com.metrolist.core.api.settings

import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.topic
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** api.md 3.10: `settings.schema/get/set/reset`, the `settings` topic, and the lyrics AI key methods. */
object SettingsApi {
    private const val POLL_MS = 1000L

    private fun settings(): Settings = Settings.shared

    private class Schema(
        val owner: Settings,
        val entries: List<SettingEntry>,
    ) {
        val byKey: Map<String, SettingEntry> = entries.associateBy { it.key }
        val defaults: Map<String, Any?> = owner.registry.associate { it.key to it.default }
    }

    @Volatile
    private var schemaCache: Schema? = null

    /** The registry is complete once the `Settings` instance exists, so the schema is built on first use (and again if the instance is replaced). */
    private fun schema(): Schema {
        val s = settings()
        schemaCache?.takeIf { it.owner === s }?.let { return it }
        return Schema(s, SchemaMapper.entries(s.registry)).also { schemaCache = it }
    }

    val handlers: Map<String, Handler> =
        mapOf(
            "settings.schema" to handler { _, _ -> schemaResult() },
            "settings.get" to handler { params, _ -> getResult(params) },
            "settings.set" to handler { params, _ -> setResult(params) },
            "settings.reset" to handler { params, _ -> resetResult(params) },
            "lyrics.ai.setKey" to
                handler { params, _ ->
                    val provider = params.requireStr("provider").takeIf { it.isNotEmpty() } ?: badRequest("provider: non-empty string required")
                    val key = params.requireStr("key")
                    SettingsHub.write { setAiKey(settings(), provider, key) }
                    emptyResult()
                },
            "lyrics.ai.test" to handler { params, _ -> testAi(settings(), params.requireStr("provider")) },
        )

    internal fun schemaResult(): JsonObject = buildJsonObject { put("entries", JsonArray(schema().entries.map { it.toJson() })) }

    internal fun getResult(params: JsonObject): JsonObject {
        val schema = schema()
        val keys = keysParam(params["keys"]) ?: schema.entries.map { it.key }
        val s = settings()
        val values =
            buildJsonObject {
                for (key in keys) {
                    if (key !in schema.byKey) badRequest("unknown key $key")
                    put(key, SettingJson.wire(key, s.getRaw(key)))
                }
            }
        return buildJsonObject { put("values", values) }
    }

    internal fun setResult(params: JsonObject): JsonObject {
        val values = params["values"] as? JsonObject ?: badRequest("values: object required")
        val schema = schema()
        // Validate everything first so one bad entry changes nothing.
        val checked =
            values.map { (key, value) ->
                val entry = schema.byKey[key] ?: badRequest("unknown key $key")
                key to Validation.coerce(entry, value)
            }
        SettingsHub.write {
            for ((key, raw) in checked) guarded(key) { settings().setRaw(key, raw) }
        }
        return emptyResult()
    }

    internal fun resetResult(params: JsonObject): JsonObject {
        val keys = keysParam(params["keys"]) ?: badRequest("keys: string[] required")
        val schema = schema()
        for (key in keys) if (key !in schema.byKey) badRequest("unknown key $key")
        SettingsHub.write {
            for (key in keys) guarded(key) { settings().setRaw(key, schema.defaults[key]) }
        }
        return emptyResult()
    }

    /** Snapshot first, then `{changed}` deltas (see [SettingsHub] for how deltas are found). */
    val settingsTopic: Topic =
        topic { _, emit ->
            SettingsHub.scan()
            coroutineScope {
                launch {
                    SettingsHub.changes
                        .onSubscription {
                            emit("settings.snapshot", buildJsonObject { put("values", JsonObject(SettingsHub.current())) })
                        }.collect { changed ->
                            emit("settings.changed", buildJsonObject { put("changed", changed) })
                        }
                }
                launch {
                    while (currentCoroutineContext().isActive) {
                        delay(POLL_MS)
                        SettingsHub.scan()
                    }
                }
            }
        }

    private fun keysParam(element: JsonElement?): List<String>? {
        if (element == null) return null
        val array = element as? JsonArray ?: badRequest("keys: string[] required")
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: badRequest("keys: string[] required") }
    }

    /** Runs a write, turning the shared `IllegalArgumentException` into `bad_request`. */
    private fun guarded(
        key: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "$key: invalid value")
        }
    }

    /** DeepL has its own key; every other provider uses the per-provider map, mirrored to the legacy key for the active one. */
    internal fun setAiKey(
        s: Settings,
        provider: String,
        key: String,
    ) {
        if (provider.equals("DeepL", ignoreCase = true)) {
            s.lyricsDeepLApiKey = key
            return
        }
        s.setLyricsAiApiKey(provider, key)
        if (provider == s.lyricsAiProvider) s.lyricsAiApiKey = key
    }

    /** One tiny translation through the configured provider. The key itself is never part of the answer. */
    private suspend fun testAi(
        s: Settings,
        provider: String,
    ): JsonElement {
        val isDeepL = provider.equals("DeepL", ignoreCase = true)
        val (apiKey, baseUrl) = if (isDeepL) "" to "" else s.lyricsAiCredentials(provider)
        val hasKey = if (isDeepL) s.lyricsDeepLApiKey.isNotBlank() else apiKey.isNotBlank()
        if (!hasKey) {
            return buildJsonObject {
                put("ok", false)
                put("error", "no key")
            }
        }
        val result =
            LyricsTranslationClient().translateLyricsLines(
                lines = listOf("Hello"),
                provider = provider,
                targetLanguage = "vi",
                mode = "Literal",
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = s.lyricsAiModel,
                deepLApiKey = s.lyricsDeepLApiKey,
                deepLFormality = s.lyricsDeepLFormality,
            )
        return buildJsonObject {
            put("ok", result.isSuccess)
            if (result.isFailure) {
                val secret = if (isDeepL) s.lyricsDeepLApiKey else apiKey
                put("error", (result.exceptionOrNull()?.message ?: "failed").replace(secret, "***").take(200))
            }
        }
    }
}
