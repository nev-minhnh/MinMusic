package com.metrolist.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.android.source.credentials.SourceAccounts
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.source_logout_confirm
import com.metrolist.shared.generated.resources.source_nct_login
import com.metrolist.shared.generated.resources.source_nct_login_desc
import com.metrolist.shared.generated.resources.source_nct_logout
import com.metrolist.shared.generated.resources.source_status_combined
import com.metrolist.shared.generated.resources.source_status_degraded
import com.metrolist.shared.generated.resources.source_status_disabled
import com.metrolist.shared.generated.resources.source_status_enabled
import com.metrolist.shared.generated.resources.source_status_logged_in
import com.metrolist.shared.generated.resources.source_status_logged_in_free
import com.metrolist.shared.generated.resources.source_status_logged_in_vip
import com.metrolist.shared.generated.resources.source_status_logged_out
import com.metrolist.shared.generated.resources.source_status_no_account
import com.metrolist.shared.generated.resources.source_status_session_expired
import com.metrolist.shared.generated.resources.source_zing_login
import com.metrolist.shared.generated.resources.source_zing_login_desc
import com.metrolist.shared.generated.resources.source_zing_logout
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceHealth
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.isSourceEnabled
import com.metrolist.shared.source.nct.NctAccount
import com.metrolist.shared.source.nct.NctEntitlement
import com.metrolist.shared.source.zing.ZingAccount
import com.metrolist.shared.source.zing.ZingEntitlement
import com.metrolist.shared.ui.settings.SettingsHostAction
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** What Settings → Sources shows on Android: a status line per source and the account sign-in or sign-out rows. */
internal class SourceSettingsUi(
    val statuses: Map<SourceId, String>,
    val accountActions: List<SettingsHostAction>,
)

@Composable
internal fun rememberSourceSettingsUi(
    onZingLogin: () -> Unit,
    onNctLogin: () -> Unit,
): SourceSettingsUi {
    val settings = Settings.shared
    val registry: SourceRegistry = koinInject()
    val scope = rememberCoroutineScope()
    val zingSignedIn = settings.zingSourceCookie.isNotBlank()
    val nctSignedIn = settings.nctSourceToken.isNotBlank()
    var zingAccount by remember { mutableStateOf<ZingAccount?>(null) }
    var nctAccount by remember { mutableStateOf<NctAccount?>(null) }
    LaunchedEffect(zingSignedIn) {
        zingAccount = null
        if (zingSignedIn) zingAccount = SourceAccounts.zingAccount()
    }
    LaunchedEffect(nctSignedIn) {
        nctAccount = null
        if (nctSignedIn) nctAccount = SourceAccounts.nctAccount()
    }

    // A null account while signed in means the check has not finished or Zing could not be reached.
    val zing = zingAccount
    val zingAccountLine =
        when {
            !zingSignedIn -> stringResource(Res.string.source_status_logged_out)
            zing == null -> stringResource(Res.string.source_status_logged_in)
            !zing.loggedIn -> stringResource(Res.string.source_status_session_expired)
            zing.entitlement == ZingEntitlement.VIP -> stringResource(Res.string.source_status_logged_in_vip)
            zing.entitlement == ZingEntitlement.FREE -> stringResource(Res.string.source_status_logged_in_free)
            else -> stringResource(Res.string.source_status_logged_in)
        }
    val nct = nctAccount
    val nctAccountLine =
        when {
            !nctSignedIn -> stringResource(Res.string.source_status_logged_out)
            nct == null -> stringResource(Res.string.source_status_logged_in)
            !nct.loggedIn -> stringResource(Res.string.source_status_session_expired)
            nct.entitlement == NctEntitlement.VIP -> stringResource(Res.string.source_status_logged_in_vip)
            else -> stringResource(Res.string.source_status_logged_in_free)
        }
    val statuses =
        mapOf(
            SourceId.SC to sourceStatus(SourceId.SC, registry, stringResource(Res.string.source_status_no_account)),
            SourceId.ZING to sourceStatus(SourceId.ZING, registry, zingAccountLine),
            SourceId.NCT to sourceStatus(SourceId.NCT, registry, nctAccountLine),
        )

    val logoutConfirm = stringResource(Res.string.source_logout_confirm)
    val zingAction =
        if (zingSignedIn) {
            SettingsHostAction(
                id = "zingLogout",
                title = stringResource(Res.string.source_zing_logout),
                description = zing?.displayName,
                confirmationMessage = logoutConfirm,
                onClick = { scope.launch { SourceAccounts.logoutZing() } },
            )
        } else {
            SettingsHostAction(
                id = "zingLogin",
                title = stringResource(Res.string.source_zing_login),
                description = stringResource(Res.string.source_zing_login_desc),
                onClick = onZingLogin,
            )
        }
    val nctAction =
        if (nctSignedIn) {
            SettingsHostAction(
                id = "nctLogout",
                title = stringResource(Res.string.source_nct_logout),
                description = nct?.displayName,
                confirmationMessage = logoutConfirm,
                onClick = { scope.launch { SourceAccounts.logoutNct() } },
            )
        } else {
            SettingsHostAction(
                id = "nctLogin",
                title = stringResource(Res.string.source_nct_login),
                description = stringResource(Res.string.source_nct_login_desc),
                onClick = onNctLogin,
            )
        }
    return SourceSettingsUi(statuses, listOf(zingAction, nctAction))
}

@Composable
private fun sourceStatus(
    id: SourceId,
    registry: SourceRegistry,
    accountLine: String,
): String {
    val state =
        when {
            !Settings.shared.isSourceEnabled(id) -> stringResource(Res.string.source_status_disabled)
            registry.health(id) == SourceHealth.DEGRADED -> stringResource(Res.string.source_status_degraded)
            else -> stringResource(Res.string.source_status_enabled)
        }
    return stringResource(Res.string.source_status_combined, state, accountLine)
}
