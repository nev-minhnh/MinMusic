package com.metrolist.core.api

import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import java.io.File
import java.util.jar.JarFile

/**
 * Finds every `com/metrolist/core/api/<pkg>/<Name>Module.class` in the code source of this class (the core jar, or the
 * classes directory in unit tests), instantiates the Kotlin object and merges the handler and topic maps.
 */
object ApiRegistry {
    private val MODULE_ENTRY = Regex("^com/metrolist/core/api/[A-Za-z0-9_]+/[A-Za-z0-9_]+Module\\.class$")

    fun load(): Pair<Map<String, Handler>, Map<String, Topic>> {
        val location = File(ApiRegistry::class.java.protectionDomain.codeSource.location.toURI())
        val names = moduleClassNames(location)
        check(names.isNotEmpty()) { "no API modules found in $location" }
        return merge(names.map { it to instantiate(it) })
    }

    /** Class names (dotted) of the modules in a jar file or a classes directory, sorted for determinism. */
    internal fun moduleClassNames(location: File): List<String> {
        val entries: List<String> =
            if (location.isDirectory) {
                location
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(location).path.replace(File.separatorChar, '/') }
                    .toList()
            } else {
                JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
            }
        return entries
            .filter { MODULE_ENTRY.matches(it) }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted()
    }

    private fun instantiate(className: String): ApiModule = Class.forName(className).getField("INSTANCE").get(null) as ApiModule

    /** Merges modules in the given order; a method or topic defined twice is a start-up error. */
    internal fun merge(modules: List<Pair<String, ApiModule>>): Pair<Map<String, Handler>, Map<String, Topic>> {
        val handlers = linkedMapOf<String, Handler>()
        val topics = linkedMapOf<String, Topic>()
        val handlerOwner = hashMapOf<String, String>()
        val topicOwner = hashMapOf<String, String>()
        for ((name, module) in modules.sortedBy { it.first }) {
            for ((method, handler) in module.handlers) {
                handlerOwner[method]?.let { throw IllegalStateException("duplicate method $method in $it and $name") }
                handlerOwner[method] = name
                handlers[method] = handler
            }
            for ((topicName, topic) in module.topics) {
                topicOwner[topicName]?.let { throw IllegalStateException("duplicate topic $topicName in $it and $name") }
                topicOwner[topicName] = name
                topics[topicName] = topic
            }
        }
        return handlers to topics
    }
}
