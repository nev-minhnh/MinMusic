package com.metrolist.android.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.android.playlistimport.ProviderLoginShell
import com.metrolist.android.source.credentials.SourceAccounts
import com.metrolist.android.source.credentials.ZingCookieStore
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.source_zing_login
import com.metrolist.shared.generated.resources.source_zing_login_auto_close
import com.metrolist.shared.generated.resources.source_zing_login_failed
import com.metrolist.shared.generated.resources.source_zing_login_privacy
import com.metrolist.shared.generated.resources.source_zing_official_site
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Zing MP3 sign-in on Zing's own page. Only the resulting cookie header is kept, in [ZingCookieStore].
 * A read-only address bar shows the page that is really loaded, above a privacy note; the window closes by itself
 * once the session cookie appears.
 */
@Composable
@Suppress("FunctionName")
internal fun ZingLoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    var pageUrl by remember { mutableStateOf(SourceAccounts.ZING_LOGIN_URL) }

    ProviderLoginShell(
        titleRes = Res.string.source_zing_login,
        loginErrorRes = Res.string.source_zing_login_failed,
        cookieUrl = SourceAccounts.ZING_COOKIE_URL,
        loginUrl = SourceAccounts.ZING_LOGIN_URL,
        logTag = "ZingLogin",
        credentialLogMessage = "Zing MP3 WebView session",
        extractCredentials = { header -> header.takeIf { ZingCookieStore.hasSessionCookie(it) } },
        credentialKey = { it },
        isAuthenticating = isAuthenticating,
        authenticationError = authenticationError,
        onClearAuthenticationError = { authenticationError = null },
        onCredentials = { cookieHeader ->
            isAuthenticating = true
            scope.launch {
                SourceAccounts.zingCookies.save(cookieHeader)
                // A null account means Zing could not be reached; keep the session and let Settings check it later.
                val account = SourceAccounts.zingAccount()
                isAuthenticating = false
                if (account != null && !account.loggedIn) {
                    SourceAccounts.logoutZing()
                    authenticationError = getString(Res.string.source_zing_login_failed)
                } else {
                    onLoggedIn()
                }
            }
        },
        onNavigateBack = onNavigateBack,
        useCloseIcon = true,
        framedWebView = true,
        onPageUrlChanged = { pageUrl = it },
        banner = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AddressBar(pageUrl)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(Res.string.source_zing_login_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.source_zing_login_auto_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
    )
}

/** Read-only address of the page in the WebView: a lock for HTTPS, the host, and a note that it is Zing's own site. */
@Composable
private fun AddressBar(url: String) {
    val uri = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val secure = uri?.scheme == "https"
    val host = uri?.host ?: url
    val official = host == "zingmp3.vn" || host.endsWith(".zingmp3.vn")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (secure) {
            Icon(
                imageVector = MetrolistIcons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = if (secure) "https://$host" else url,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (secure && official) {
            Text(
                text = "· " + stringResource(Res.string.source_zing_official_site),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}
