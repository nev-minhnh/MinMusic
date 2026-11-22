package com.metrolist.core.bridge

import com.metrolist.core.CoreLog
import java.util.Locale

/**
 * Compose resources' getString() asks skiko for the system theme on desktop (ResourceEnvironment_desktopKt.getSystemEnvironment),
 * and the sidecar image carries no skiko. The sidecar swaps in an environment built from the JVM locale, light theme, mdpi.
 * Reflection, because the hook (`getResourceEnvironment`) and the qualifier constructors are not public Kotlin API.
 */
object HeadlessResources {
    private const val PKG = "org.jetbrains.compose.resources."

    fun install() {
        runCatching {
            headlessEnvironment() // fail here, not inside the first getString()
            Class
                .forName(PKG + "ResourceEnvironmentKt")
                .getMethod("setGetResourceEnvironment", kotlin.reflect.KFunction::class.java)
                .invoke(null, ::headlessEnvironment)
        }.onFailure { CoreLog.error("headless compose resources environment not installed", it) }
    }

    /** Called by compose resources on every lookup, so a language switch (Locale.setDefault) applies at once. */
    fun headlessEnvironment(): Any {
        val locale = Locale.getDefault()
        fun qualifier(name: String, value: String): Any = Class.forName(PKG + name).getConstructor(String::class.java).newInstance(value)
        val theme = Class.forName(PKG + "ThemeQualifier").getField("LIGHT").get(null)
        val density = Class.forName(PKG + "DensityQualifier").getField("MDPI").get(null)
        val envClass = Class.forName(PKG + "ResourceEnvironment")
        return envClass.constructors.single { it.parameterCount == 5 }.newInstance(
            qualifier("LanguageQualifier", locale.language),
            qualifier("ScriptQualifier", locale.script),
            qualifier("RegionQualifier", locale.country),
            theme,
            density,
        )
    }
}
