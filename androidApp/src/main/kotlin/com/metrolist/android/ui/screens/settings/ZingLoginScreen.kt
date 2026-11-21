package com.metrolist.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.android.playlistimport.ProviderLoginShell
import com.metrolist.android.source.credentials.SourceAccounts
import com.metrolist.android.source.credentials.ZingCookieStore
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.source_zing_login
import com.metrolist.shared.generated.resources.source_zing_login_failed
import com.metrolist.shared.generated.resources.source_zing_login_privacy
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Zing MP3 sign-in on Zing's own page. Only the resulting cookie header is kept, in [ZingCookieStore]. */
@Composable
@Suppress("FunctionName")
internal fun ZingLoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }
    var authenticationError by remember { mutableStateOf<String?>(null) }

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
        banner = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.source_zing_login_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        },
    )
}
