package com.metrolist.desktop.deviceconnect

import com.metrolist.core.CoreConfig
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

enum class CoreFirewallStatus { UNKNOWN, ALLOWED, BLOCKED, NO_RULE }

/**
 * Windows Defender Firewall inbound rules for `minmusic-core.exe` (the listener of Device Connect lives in the core).
 * Replicates the `internal` desktopApp `WindowsFirewall.status()` script, with the program path of the core process
 * (or the `--exe-path` the shell passed). A dismissed first-listen prompt leaves a block rule that silently stops phones.
 */
object WindowsFirewallCore {
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private const val CACHE_MS = 60_000L

    @Volatile private var cached: Pair<Long, CoreFirewallStatus>? = null

    /** The core launcher; null for development runs through java.exe. */
    internal fun programPath(): String? {
        val command =
            CoreConfig.options.exePath?.takeIf { it.isNotBlank() }
                ?: ProcessHandle.current().info().command().orElse(null)
                ?: return null
        val name = File(command).name.lowercase()
        return command.takeIf { name.endsWith(".exe") && name != "java.exe" && name != "javaw.exe" }
    }

    /** PowerShell script that prints NONE, BLOCK or ALLOW for the inbound rules of [executable] (quotes doubled). */
    internal fun script(executable: String): String {
        val program = executable.replace("'", "''")
        return "\$rules = Get-NetFirewallApplicationFilter -Program '$program' -ErrorAction SilentlyContinue | " +
            "Get-NetFirewallRule -ErrorAction SilentlyContinue | " +
            "Where-Object { \$_.Enabled -eq 'True' -and \$_.Direction -eq 'Inbound' }; " +
            "if (-not \$rules) { 'NONE' } elseif (\$rules | Where-Object { \$_.Action -eq 'Block' }) { 'BLOCK' } else { 'ALLOW' }"
    }

    internal fun parse(output: String): CoreFirewallStatus =
        when (output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }) {
            "ALLOW" -> CoreFirewallStatus.ALLOWED
            "BLOCK" -> CoreFirewallStatus.BLOCKED
            "NONE" -> CoreFirewallStatus.NO_RULE
            else -> CoreFirewallStatus.UNKNOWN
        }

    /** Cached for 60 s unless [refresh]. */
    suspend fun status(refresh: Boolean = false): CoreFirewallStatus {
        val now = System.currentTimeMillis()
        cached?.let { (at, value) -> if (!refresh && now - at < CACHE_MS) return value }
        val value = query()
        cached = System.currentTimeMillis() to value
        return value
    }

    /** The last known value without running PowerShell (UNKNOWN before the first [status]). */
    fun lastKnown(): CoreFirewallStatus = cached?.second ?: CoreFirewallStatus.UNKNOWN

    private suspend fun query(): CoreFirewallStatus =
        withContext(Dispatchers.IO) {
            if (!isWindows) return@withContext CoreFirewallStatus.UNKNOWN
            val executable = programPath() ?: return@withContext CoreFirewallStatus.UNKNOWN
            try {
                val process =
                    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script(executable))
                        .redirectErrorStream(true)
                        .start()
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@withContext CoreFirewallStatus.UNKNOWN
                }
                parse(process.inputStream.bufferedReader().readText())
            } catch (error: Exception) {
                Logger.w("WindowsFirewallCore", "Could not read firewall rules: ${error.message}")
                CoreFirewallStatus.UNKNOWN
            }
        }

    /** Opens Control Panel > Windows Defender Firewall > Allowed apps (same arguments as v1). */
    fun openAllowedApps() {
        runCatching {
            ProcessBuilder("control.exe", "/name", "Microsoft.WindowsFirewall", "/page", "pageConfigureApps").start()
        }.onFailure { Logger.w("WindowsFirewallCore", "Could not open firewall settings: ${it.message}") }
    }
}
