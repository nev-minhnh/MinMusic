package com.metrolist.core.api.settings

/**
 * String-typed settings that are really closed sets. They are exposed as `enum` and `settings.set` checks membership.
 * Same table as the mock generator (`gen-settings-schema.mjs`, `ENUM`).
 */
object StringEnums {
    val table: Map<String, List<String>> =
        mapOf(
            "appLanguage" to listOf("SYSTEM", "en", "vi"),
            "audioQuality" to listOf("LOW", "AUTO", "HIGH"),
            "lyricsTextPosition" to listOf("LEFT", "CENTER", "RIGHT"),
            "lyricsAiTranslationMode" to listOf("Literal", "Transcreation"),
            "lyricsAiActivationMode" to listOf("Automatic", "Manual"),
            "lyricsDeepLFormality" to listOf("default", "more", "less"),
            "lyricsAnimationStyle" to listOf("None", "Fade", "Glow", "Slide", "Karaoke", "Apple"),
            "audioCacheLocation" to listOf("INTERNAL", "EXTERNAL"),
            "downloadLocation" to listOf("INTERNAL", "EXTERNAL", "CUSTOM"),
        )
}
