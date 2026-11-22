package com.metrolist.core.api

import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.topic
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiRegistryTest {
    private fun module(
        vararg methods: String,
        topicNames: List<String> = emptyList(),
    ): ApiModule =
        object : ApiModule {
            override val handlers: Map<String, Handler> = methods.associateWith { handler { _, _ -> emptyResult() } }
            override val topics: Map<String, Topic> = topicNames.associateWith { topic { _, _ -> } }
        }

    @Test
    fun loadsEveryModuleOfTheCore() {
        val (handlers, topics) = ApiRegistry.load()
        for (method in listOf("home.get", "player.play", "queue.window", "search.run", "sys.ping", "library.window", "album.get", "app.openUrl")) {
            assertTrue(method in handlers, "missing $method")
        }
        for (name in listOf("player", "queue", "app")) assertTrue(name in topics, "missing topic $name")
    }

    @Test
    fun findsAtLeastFiveModules() {
        val location = File(ApiRegistry::class.java.protectionDomain.codeSource.location.toURI())
        val names = ApiRegistry.moduleClassNames(location)
        assertTrue(names.size >= 5, "modules: $names")
        assertEquals(names.sorted(), names)
        assertEquals(names.size, names.toSet().size)
        assertTrue("com.metrolist.core.api.m0.M0Module" in names)
    }

    @Test
    fun duplicateMethodNamesBothModules() {
        val error =
            assertFailsWith<IllegalStateException> {
                ApiRegistry.merge(listOf("pkg.AModule" to module("x.y", "x.z"), "pkg.BModule" to module("x.y")))
            }
        assertEquals("duplicate method x.y in pkg.AModule and pkg.BModule", error.message)
    }

    @Test
    fun duplicateTopicIsAnError() {
        assertFailsWith<IllegalStateException> {
            ApiRegistry.merge(listOf("a.AModule" to module(topicNames = listOf("t")), "b.BModule" to module(topicNames = listOf("t"))))
        }
    }

    @Test
    fun mergeOrdersModulesByClassName() {
        val (handlers, _) = ApiRegistry.merge(listOf("b.BModule" to module("b.one"), "a.AModule" to module("a.one")))
        assertEquals(listOf("a.one", "b.one"), handlers.keys.toList())
    }

    @Test
    fun scansClassDirectoryAndJar() {
        val root = Files.createTempDirectory("apireg").toFile()
        try {
            val entries =
                listOf(
                    "com/metrolist/core/api/foo/FooModule.class",
                    "com/metrolist/core/api/foo/FooApi.class",
                    "com/metrolist/core/api/foo/FooModule\$Inner.class",
                    "com/metrolist/core/api/RootModule.class",
                    "com/metrolist/core/api/bar/BarModule.class",
                    "com/other/baz/BazModule.class",
                )
            for (entry in entries) File(root, entry).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(0))
            val expected = listOf("com.metrolist.core.api.bar.BarModule", "com.metrolist.core.api.foo.FooModule")
            assertEquals(expected, ApiRegistry.moduleClassNames(root))

            val jar = File(root, "core.jar")
            JarOutputStream(jar.outputStream()).use { out ->
                for (entry in entries) {
                    out.putNextEntry(JarEntry(entry))
                    out.write(0)
                    out.closeEntry()
                }
            }
            assertEquals(expected, ApiRegistry.moduleClassNames(jar))
        } finally {
            root.deleteRecursively()
        }
    }
}
