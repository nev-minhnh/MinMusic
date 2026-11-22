import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

// MinMusic for Windows v2: headless Kotlin core sidecar (docs/architecture.md sections 2 and 3).
// M0 spike: depends on :desktopApp (Compose UI classes are on the classpath but never instantiated) and :shared.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

evaluationDependsOn(":desktopApp")

// Adoptium publishes no Windows ARM64 JDK 25; Microsoft's OpenJDK build is the one available there.
val jdkVendor =
    if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") {
        JvmVendorSpec.MICROSOFT
    } else {
        JvmVendorSpec.ADOPTIUM
    }

group = "com.metrolist"
version = providers.gradleProperty("appVersion").getOrElse("0.0.0")

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(jdkVendor)
    }
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.addAll("kotlin.RequiresOptIn", "kotlin.ExperimentalStdlibApi", "kotlinx.coroutines.ExperimentalCoroutinesApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("com.metrolist.core.SidecarMainKt")
}

dependencies {
    implementation(project(":desktopApp"))
    implementation(project(":shared")) {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
    implementation(libs.runtime)
    implementation(libs.room.runtime)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.slf4j.simple)
}

// The core has its own single-thread Main dispatcher (CoreMainDispatcherFactory); Swing's would be picked otherwise.
configurations.matching { it.name == "runtimeClasspath" || it.name == "compileClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// Same JVM profile as the v1 Windows launcher (desktopApp/build.gradle.kts desktopWindowsJvmArgs) minus the Skiko flags.
val sidecarJvmArgs =
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "-XX:MaxDirectMemorySize=256m",
        "-Xms128m",
        "-Xmx768m",
        "-XX:+UseG1GC",
        "-XX:MinHeapFreeRatio=40",
        "-XX:MaxHeapFreeRatio=70",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:ParallelGCThreads=4",
        "-XX:ConcGCThreads=2",
        "-XX:CICompilerCount=4",
        "-XX:ReservedCodeCacheSize=128m",
        "-XX:+UseCompactObjectHeaders",
        "-Djdk.nio.maxCachedBufferSize=262144",
        "-Dorg.bytedeco.javacpp.maxPhysicalBytes=0",
        "-Dorg.bytedeco.javacpp.maxBytes=0",
        "-Djava.awt.headless=true",
    )

// architecture.md 3.2 module list, plus java.logging (JUL users such as slf4j-simple's neighbours and JNA) and
// jdk.crypto.mscapi (Windows-ROOT trust store for TLS behind corporate proxies).
val sidecarModules =
    listOf(
        "java.base",
        "java.desktop",
        "java.instrument",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.sql",
        "java.xml",
        "jdk.crypto.ec",
        "jdk.crypto.mscapi",
        "jdk.localedata",
        "jdk.security.auth",
        "jdk.unsupported",
        "jdk.zipfs",
    )

@UntrackedTask(because = "Builds an image outside the project directory and stamps the build time")
abstract class SidecarImageTask : DefaultTask() {
    @get:Classpath
    abstract val runtimeJars: ConfigurableFileCollection

    @get:Internal
    abstract val extraJars: ConfigurableFileCollection

    @get:Internal
    abstract val jdkHome: DirectoryProperty

    @get:Internal
    abstract val scratchDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @get:Input
    abstract val sourceSha: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val modules: ListProperty<String>

    private fun runProcess(
        workingDir: File,
        timeoutMinutes: Long,
        command: List<String>,
    ): Int {
        val process =
            ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start()
        process.outputStream.close()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("Timed out: " + command.joinToString(" "))
        }
        return process.exitValue()
    }

    @TaskAction
    fun build() {
        val out = outDir.get().asFile
        val lib = File(out, "lib")
        val runtime = File(out, "runtime")
        out.mkdirs()
        lib.deleteRecursively()
        runtime.deleteRecursively()
        File(out, "core.jsa").delete()
        lib.mkdirs()

        // 1. App jars.
        val copied = mutableMapOf<String, File>()
        (runtimeJars.files + extraJars.files).filter { it.isFile && it.name.endsWith(".jar") }.forEach { jar ->
            var name = jar.name
            var n = 1
            if (copied[name]?.length() == jar.length()) return@forEach
            while (copied.containsKey(name)) name = jar.nameWithoutExtension + "-" + (n++) + ".jar"
            val target = File(lib, name)
            jar.copyTo(target, overwrite = true)
            copied[name] = target
        }
        logger.lifecycle("sidecarImage: ${copied.size} jars -> $lib")

        // 2. jlink runtime.
        val jdk = jdkHome.get().asFile
        val jlink = File(jdk, "bin/jlink.exe").takeIf { it.isFile } ?: File(jdk, "bin/jlink")
        val jlinkExit =
            runProcess(
                out,
                10,
                listOf(jlink.absolutePath) +
                    (if (File(jdk, "jmods").isDirectory) listOf("--module-path", File(jdk, "jmods").absolutePath) else emptyList()) +
                    listOf(
                    "--add-modules",
                    modules.get().joinToString(","),
                    "--strip-debug",
                    "--no-header-files",
                    "--no-man-pages",
                    "--compress",
                    "zip-9",
                    "--output",
                    runtime.absolutePath,
                ),
            )
        check(jlinkExit == 0) { "jlink failed with exit code $jlinkExit" }

        // 3. Stable launcher name for the firewall prompt (windows/WindowsFirewall.kt points at it).
        val exeSuffix = if (File(runtime, "bin/java.exe").isFile) ".exe" else ""
        val exe = File(runtime, "bin/minmusic-core$exeSuffix")
        File(runtime, "bin/java$exeSuffix").copyTo(exe, overwrite = true)
        exe.setExecutable(true)

        // 4. Base CDS archive of the runtime with the same flags as production (compact headers invalidate the default one).
        val flags = jvmArgs.get()
        val dumpExit = runProcess(out, 5, listOf(exe.absolutePath) + flags + "-Xshare:dump")
        if (dumpExit != 0) logger.warn("sidecarImage: base -Xshare:dump failed ($dumpExit); the image still runs without CDS")

        // 5. Argument files. Paths are relative to the image folder, which is the sidecar's working directory.
        File(out, "core.warmup.args").writeText(flags.joinToString("\n") + "\n")

        // 6. AppCDS warm-up: start, open the DB, load Home from a fixture and exit, dumping the dynamic archive.
        val warmDir = File(scratchDir.get().asFile, "warmup").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = File(warmDir, "home.json")
        fixture.writeText(
            """{"shelves":[{"key":"warmup","title":"Warm-up","layout":"rows","items":[]}],"chips":[],"greeting":{"key":"good_morning"},"fromCache":true,"next":null}""",
        )
        val warmExit =
            runProcess(
                out,
                4,
                listOf(exe.absolutePath, "@core.warmup.args", "-XX:ArchiveClassesAtExit=core.jsa", "-cp", "lib/*", "com.metrolist.core.SidecarMainKt") +
                    listOf("--cds-warmup", "--data-root", File(warmDir, "data").absolutePath, "--fixture-home", fixture.absolutePath),
            )
        val jsa = File(out, "core.jsa")
        val hasArchive = warmExit == 0 && jsa.isFile && jsa.length() > 0
        if (!hasArchive) logger.warn("sidecarImage: CDS warm-up failed (exit $warmExit); core.args has no SharedArchiveFile")
        File(out, "core.args").writeText(
            (flags + if (hasArchive) listOf("-XX:SharedArchiveFile=core.jsa", "-Xshare:auto") else listOf("-Xshare:auto")).joinToString("\n") + "\n",
        )

        // 7. sidecar.lock next to dist/.
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val hashes = lib.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.joinToString(",\n") { "    \"${it.name}\": \"${sha(it)}\"" }
        File(out.parentFile, "sidecar.lock").writeText(
            "{\n  \"submoduleSha\": \"${sourceSha.get()}\",\n  \"builtAt\": \"${Instant.now()}\",\n  \"lib\": {\n$hashes\n  }\n}\n",
        )
        logger.lifecycle("sidecarImage: done -> $out (cds=$hasArchive)")
    }
}

val sidecarSourceSha =
    providers
        .exec {
            commandLine("git", "-C", rootDir.absolutePath, "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map(String::trim)
        .orElse("unknown")

// <repo>/../../sidecar/dist when the repo is the submodule core/MinMusic of MinMusic_4_Windows; otherwise under build/.
val defaultSidecarOut =
    if (rootDir.parentFile?.name == "core") {
        File(rootDir, "../../sidecar/dist").canonicalFile
    } else {
        File(rootDir, "build/sidecar/dist")
    }

tasks.register<SidecarImageTask>("sidecarImage") {
    group = "distribution"
    notCompatibleWithConfigurationCache("task class is declared in the build script")
    description = "Builds the headless core runtime image (jlink + AppCDS) into sidecar/dist."
    dependsOn(tasks.named("jar"))
    val platformSqlite = project(":desktopApp").tasks.named("platformSqliteJdbc")
    dependsOn(platformSqlite)
    runtimeJars.from(tasks.named("jar"), configurations.named("runtimeClasspath"))
    // The platform-trimmed sqlite-jdbc jar of desktopApp (a file dependency there); a no-op when it is already on the classpath.
    extraJars.from(platformSqlite)
    jdkHome.set(
        javaToolchains
            .launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(jdkVendor)
            }.map { it.metadata.installationPath },
    )
    scratchDir.set(layout.buildDirectory.dir("sidecar-work"))
    outDir.set(
        providers
            .gradleProperty("sidecarOut")
            .map { layout.projectDirectory.dir(it) }
            .orElse(layout.dir(providers.provider { defaultSidecarOut })),
    )
    sourceSha.set(sidecarSourceSha)
    jvmArgs.set(sidecarJvmArgs)
    modules.set(sidecarModules)
}
