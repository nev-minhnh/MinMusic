package com.metrolist.desktop.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.device_connect_firewall_allowed
import com.metrolist.shared.generated.resources.device_connect_firewall_blocked
import com.metrolist.shared.generated.resources.device_connect_firewall_no_rule
import com.metrolist.shared.generated.resources.device_connect_firewall_open
import com.metrolist.shared.generated.resources.device_connect_firewall_title
import com.metrolist.shared.generated.resources.device_connect_firewall_unknown
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.concurrent.TimeUnit

internal enum class WindowsFirewallStatus { UNKNOWN, ALLOWED, BLOCKED, NO_RULE }

/**
 * Windows Defender Firewall asks once whether MinMusic.exe may accept connections when Device Connect first listens.
 * A dismissed prompt leaves a block rule, which silently stops phones from connecting; this reads the rules for
 * the packaged executable so the settings page can say so and open the allowed-apps page.
 */
internal object WindowsFirewall {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The packaged launcher (MinMusic.exe); null for development runs through java.exe. */
    private fun appExecutable(): String? {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        val name = File(command).name.lowercase()
        return command.takeIf { name.endsWith(".exe") && name != "java.exe" && name != "javaw.exe" }
    }

    suspend fun status(): WindowsFirewallStatus =
        withContext(Dispatchers.IO) {
            if (!isWindows) return@withContext WindowsFirewallStatus.UNKNOWN
            val executable = appExecutable() ?: return@withContext WindowsFirewallStatus.UNKNOWN
            val program = executable.replace("'", "''")
            val script =
                "\$rules = Get-NetFirewallApplicationFilter -Program '$program' -ErrorAction SilentlyContinue | " +
                    "Get-NetFirewallRule -ErrorAction SilentlyContinue | " +
                    "Where-Object { \$_.Enabled -eq 'True' -and \$_.Direction -eq 'Inbound' }; " +
                    "if (-not \$rules) { 'NONE' } elseif (\$rules | Where-Object { \$_.Action -eq 'Block' }) { 'BLOCK' } else { 'ALLOW' }"
            try {
                val process =
                    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                        .redirectErrorStream(true)
                        .start()
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@withContext WindowsFirewallStatus.UNKNOWN
                }
                val result =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .lines()
                        .map { it.trim() }
                        .lastOrNull { it.isNotEmpty() }
                when (result) {
                    "ALLOW" -> WindowsFirewallStatus.ALLOWED
                    "BLOCK" -> WindowsFirewallStatus.BLOCKED
                    "NONE" -> WindowsFirewallStatus.NO_RULE
                    else -> WindowsFirewallStatus.UNKNOWN
                }
            } catch (error: Exception) {
                Logger.w("WindowsFirewall", "Could not read firewall rules: ${error.message}")
                WindowsFirewallStatus.UNKNOWN
            }
        }

    /** Opens Control Panel > Windows Defender Firewall > Allowed apps. */
    fun openAllowedApps() {
        runCatching {
            ProcessBuilder("control.exe", "/name", "Microsoft.WindowsFirewall", "/page", "pageConfigureApps").start()
        }.onFailure { Logger.w("WindowsFirewall", "Could not open firewall settings: ${it.message}") }
    }
}

@Composable
internal fun WindowsFirewallNotice() {
    val state by DesktopDeviceConnect.manager.state.collectAsState()
    var status by remember { mutableStateOf(WindowsFirewallStatus.UNKNOWN) }
    LaunchedEffect(state.serverRunning, state.serverPort) {
        if (state.serverRunning) status = WindowsFirewall.status()
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.device_connect_firewall_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                when (status) {
                    WindowsFirewallStatus.ALLOWED -> Res.string.device_connect_firewall_allowed
                    WindowsFirewallStatus.BLOCKED -> Res.string.device_connect_firewall_blocked
                    WindowsFirewallStatus.NO_RULE -> Res.string.device_connect_firewall_no_rule
                    WindowsFirewallStatus.UNKNOWN -> Res.string.device_connect_firewall_unknown
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (status == WindowsFirewallStatus.BLOCKED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        TextButton(onClick = WindowsFirewall::openAllowedApps) {
            Text(stringResource(Res.string.device_connect_firewall_open))
        }
    }
}
