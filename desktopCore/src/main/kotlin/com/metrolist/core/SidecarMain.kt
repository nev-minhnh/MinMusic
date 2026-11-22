package com.metrolist.core

import com.metrolist.core.api.HomeApi
import com.metrolist.core.api.LibraryApi
import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.SysApi
import com.metrolist.core.server.CoreServer
import com.metrolist.core.server.str
import com.metrolist.desktop.utils.DesktopSingleInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import sun.misc.Signal
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * Entry point of the headless core (docs/architecture.md 3.1 and 3.3). Sequence: parse args, read the token line from
 * stdin, install the snapshot pump, take the single-instance lock, start the backend in the background, start the
 * server, write core.pid, print READY, then watch stdin EOF and the parent process.
 */
fun main(args: Array<String>) {
    val jvmStartMs = ManagementFactory.getRuntimeMXBean().startTime
    val options = CoreOptions.parse(args)
    CoreConfig.options = options
    options.dataRoot?.let { System.setProperty("minmusic.dataRoot", it) }
    System.setProperty("java.awt.headless", "true")
    System.getProperties().putIfAbsent("java.net.useSystemProxies", "true")
    System.setProperty("org.bytedeco.javacpp.maxPhysicalBytes", "0")
    System.setProperty("org.bytedeco.javacpp.maxBytes", "0")

    // READY goes to the real stdout; AppLogger later replaces System.out with a tee.
    val stdout = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    val stdin = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    if (options.cdsWarmup) {
        CoreConfig.token = randomHex(32)
        CoreConfig.artToken = randomHex(16)
    } else {
        val line = stdin.readLine()
        val handshake = line?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val token = handshake?.str("token")
        if (token.isNullOrBlank()) {
            CoreLog.error("no token on stdin, exiting")
            exitProcess(2)
        }
        CoreConfig.token = token
        CoreConfig.artToken = handshake?.str("artToken").orEmpty()
    }

    // Dispatchers.Main must be the single core-main thread before anything in PlayerState runs.
    val mainThread = runBlocking(Dispatchers.Main) { Thread.currentThread().name }
    CoreLog.info("Dispatchers.Main=${Dispatchers.Main} thread=$mainThread")
    SnapshotPump.install()

    if (!DesktopSingleInstance.acquire()) {
        CoreLog.error("metrolist.lock is held by another instance (MinMusic 1.x?), exiting with 20")
        exitProcess(20)
    }
    installSignals()
    CoreBackend.start()

    val handlers = SysApi.handlers + HomeApi.handlers + LibraryApi.handlers + PlayerApi.handlers
    val topics = mapOf("player" to PlayerApi.playerTopic, "app" to SysApi.appTopic)
    val server = CoreServer(options.dev, handlers, topics)
    val port = server.start()
    CoreShutdown.onBeforeExit = { server.stop() }
    writeCorePid(options)

    if (!options.cdsWarmup) {
        watchStdin(stdin)
        options.parentPid?.let(::watchParent)
    }
    if (options.readyAfterInit || options.cdsWarmup) {
        try {
            runBlocking { CoreBackend.await() }
        } catch (error: Throwable) {
            CoreLog.error("backend failed before READY", error)
        }
    }
    stdout.println("READY " + buildJsonObject {
        put("port", port)
        put("pid", ProcessHandle.current().pid())
        put("version", CoreConfig.VERSION)
        put("protocol", CoreConfig.PROTOCOL)
    })
    CoreLog.info("READY after ${System.currentTimeMillis() - jvmStartMs} ms of JVM uptime, port $port")

    if (options.cdsWarmup) {
        try {
            Warmup.run(port, CoreConfig.token)
        } catch (error: Throwable) {
            CoreLog.error("warm-up failed", error)
        }
        // No hard cap: the dynamic CDS archive is written during the normal JVM exit that follows.
        CoreShutdown.run("cds-warmup", hardCap = false)
    }
}

private fun randomHex(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

private fun installSignals() {
    runCatching { Signal.handle(Signal("TERM")) { CoreShutdown.run("SIGTERM") } }
    runCatching { Signal.handle(Signal("INT")) { CoreShutdown.run("SIGINT") } }
}

private fun writeCorePid(options: CoreOptions) {
    runCatching {
        val handle = ProcessHandle.current()
        val file = CoreConfig.corePidFile
        file.parentFile.mkdirs()
        file.writeText(
            buildJsonObject {
                put("pid", handle.pid())
                put("startTimeMs", handle.info().startInstant().map { it.toEpochMilli() }.orElse(0L))
                put("exe", options.exePath ?: handle.info().command().orElse(""))
            }.toString(),
        )
    }.onFailure { CoreLog.error("cannot write core.pid", it) }
}

/** Control lines after the token line: `{"cmd":"shutdown"}`. EOF means the shell is gone. */
private fun watchStdin(stdin: BufferedReader) {
    Thread({
        try {
            while (true) {
                val line = stdin.readLine() ?: break
                val command = runCatching { Json.parseToJsonElement(line).jsonObject.str("cmd") }.getOrNull()
                if (command == "shutdown") CoreShutdown.run("shutdown-cmd")
            }
        } catch (_: IOException) {
            // Pipe closed.
        }
        CoreShutdown.run("stdin-eof")
    }, "StdinWatcher").apply {
        isDaemon = true
        start()
    }
}

/** ProcessHandle.onExit plus a 2 s polling fallback (architecture 3.3, self-termination). */
private fun watchParent(parentPid: Long) {
    val parent = ProcessHandle.of(parentPid)
    if (parent.isEmpty) {
        CoreShutdown.run("parent-missing")
        return
    }
    parent.get().onExit().thenRun { CoreShutdown.run("parent-exit") }
    Thread({
        while (true) {
            runCatching { Thread.sleep(PARENT_POLL_MS) }
            if (!ProcessHandle.of(parentPid).map { it.isAlive }.orElse(false)) {
                CoreShutdown.run("parent-poll")
                break
            }
        }
    }, "ParentPoll").apply {
        isDaemon = true
        start()
    }
}

private const val PARENT_POLL_MS = 2_000L
