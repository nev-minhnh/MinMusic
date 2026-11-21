/*
 * MinMusic desktop: Settings -> "Nguồn nhạc" (sources) page and the NhacCuaTui sign-in dialog.
 * Same content as the Android page (design-system 2.3 / 9 / 10), laid out for a wide settings pane.
 */

package com.metrolist.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.source.DesktopSourceLogins
import com.metrolist.shared.auth.hasSupportedYouTubeAuthCookie
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_login
import com.metrolist.shared.generated.resources.action_logout
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.password
import com.metrolist.shared.generated.resources.settings_sources_description
import com.metrolist.shared.generated.resources.source_auto_best_quality
import com.metrolist.shared.generated.resources.source_auto_best_quality_desc
import com.metrolist.shared.generated.resources.source_badge_default
import com.metrolist.shared.generated.resources.source_badge_free
import com.metrolist.shared.generated.resources.source_badge_nct
import com.metrolist.shared.generated.resources.source_badge_paused
import com.metrolist.shared.generated.resources.source_badge_sc
import com.metrolist.shared.generated.resources.source_badge_vip
import com.metrolist.shared.generated.resources.source_badge_yt
import com.metrolist.shared.generated.resources.source_badge_zing
import com.metrolist.shared.generated.resources.source_experimental
import com.metrolist.shared.generated.resources.source_hide_password
import com.metrolist.shared.generated.resources.source_logout_confirm
import com.metrolist.shared.generated.resources.source_multi_source_header
import com.metrolist.shared.generated.resources.source_nct_login
import com.metrolist.shared.generated.resources.source_nct_login_error
import com.metrolist.shared.generated.resources.source_nct_login_failed
import com.metrolist.shared.generated.resources.source_nct_login_network_error
import com.metrolist.shared.generated.resources.source_nct_login_subtitle
import com.metrolist.shared.generated.resources.source_nct_logout
import com.metrolist.shared.generated.resources.source_nct_privacy_password
import com.metrolist.shared.generated.resources.source_nct_privacy_title
import com.metrolist.shared.generated.resources.source_nct_privacy_token_desktop
import com.metrolist.shared.generated.resources.source_nct_use_free
import com.metrolist.shared.generated.resources.source_nct_username
import com.metrolist.shared.generated.resources.source_nhaccuatui
import com.metrolist.shared.generated.resources.source_quality_128
import com.metrolist.shared.generated.resources.source_quality_320
import com.metrolist.shared.generated.resources.source_quality_soundcloud
import com.metrolist.shared.generated.resources.source_quality_up_to
import com.metrolist.shared.generated.resources.source_quality_youtube
import com.metrolist.shared.generated.resources.source_quality_zing_vip
import com.metrolist.shared.generated.resources.source_show_password
import com.metrolist.shared.generated.resources.source_sign_in_vip
import com.metrolist.shared.generated.resources.source_soundcloud
import com.metrolist.shared.generated.resources.source_status_combined
import com.metrolist.shared.generated.resources.source_status_degraded
import com.metrolist.shared.generated.resources.source_status_logged_in
import com.metrolist.shared.generated.resources.source_status_logged_in_free
import com.metrolist.shared.generated.resources.source_status_logged_in_vip
import com.metrolist.shared.generated.resources.source_status_logged_out
import com.metrolist.shared.generated.resources.source_status_no_account
import com.metrolist.shared.generated.resources.source_status_session_expired
import com.metrolist.shared.generated.resources.source_status_youtube_guest
import com.metrolist.shared.generated.resources.source_status_youtube_signed_in
import com.metrolist.shared.generated.resources.source_youtube_music
import com.metrolist.shared.generated.resources.source_zing_login_failed
import com.metrolist.shared.generated.resources.source_zing_login_privacy
import com.metrolist.shared.generated.resources.source_zing_logout
import com.metrolist.shared.generated.resources.source_zing_mp3
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceHealth
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.nct.NctAccount
import com.metrolist.shared.source.nct.NctClient
import com.metrolist.shared.source.nct.NctEntitlement
import com.metrolist.shared.source.nct.NctException
import com.metrolist.shared.source.zing.ZingAccount
import com.metrolist.shared.source.zing.ZingClient
import com.metrolist.shared.source.zing.ZingEntitlement
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.components.TagBadge
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SettingsListStyle
import com.metrolist.shared.ui.settings.SettingsOverline
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/** Plan of a signed-in account, as far as the source told us. */
private enum class SourcePlan { UNKNOWN, FREE, VIP }

private class SourceRowState(
    val signedIn: Boolean,
    val plan: SourcePlan,
    val accountLine: String,
    val displayName: String?,
)

// Source identification colours (design-system 2.3): only as the 40 dp tile here and as badge dots elsewhere.
private val YouTubeColor = Color(0xFFE8453C)
private val SoundCloudColor = Color(0xFFF26B1D)
private val ZingColor = Color(0xFF9B51E0)
private val NctColor = Color(0xFF2F80ED)
private val TileShape = RoundedCornerShape(12.dp)
private val GroupShape = RoundedCornerShape(SettingsListStyle.OuterCorner)

// The clients W2 registers for playback (shared key and cookie caches); null until the desktop sources are wired.
private fun registry(): SourceRegistry? = KoinPlatform.getKoinOrNull()?.getOrNull<SourceRegistry>()

private suspend fun zingAccountOrNull(): ZingAccount? =
    try {
        KoinPlatform.getKoinOrNull()?.getOrNull<ZingClient>()?.let { client -> withContext(Dispatchers.IO) { client.account() } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private suspend fun nctAccountOrNull(): NctAccount? =
    try {
        KoinPlatform.getKoinOrNull()?.getOrNull<NctClient>()?.let { client -> withContext(Dispatchers.IO) { client.account() } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * Settings -> Sources on Windows: one card with every source (colour tile, VIP / Free / Paused badges, account and
 * quality line, on/off switch), Zing "Sign in for VIP" through [DesktopSourceLogins.loginZing], the NhacCuaTui
 * sign-in dialog, sign-out with a confirmation, and the "Prefer the highest quality" switch.
 */
@Composable
fun SourcesSettingsScreen() {
    val settings = Settings.shared
    val scope = rememberCoroutineScope()
    val registry = remember { registry() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var zingAccount by remember { mutableStateOf<ZingAccount?>(null) }
    var nctAccount by remember { mutableStateOf<NctAccount?>(null) }
    var pendingLogout by remember { mutableStateOf<SourceId?>(null) }
    var showNctLogin by remember { mutableStateOf(false) }
    var zingLoggingIn by remember { mutableStateOf(false) }
    var zingError by remember { mutableStateOf<String?>(null) }
    val zingStored = settings.zingSourceCookie.isNotBlank()
    val nctStored = settings.nctSourceToken.isNotBlank()
    LaunchedEffect(refreshKey, zingStored) {
        zingAccount = null
        zingAccount = zingAccountOrNull()
    }
    LaunchedEffect(refreshKey, nctStored) {
        nctAccount = null
        nctAccount = nctAccountOrNull()
    }

    val zing = zingAccount
    val zingSignedIn = zingStored || zing?.loggedIn == true
    val zingState =
        SourceRowState(
            signedIn = zingSignedIn,
            plan =
                when {
                    !zingSignedIn || zing == null || !zing.loggedIn -> SourcePlan.UNKNOWN
                    zing.entitlement == ZingEntitlement.VIP -> SourcePlan.VIP
                    zing.entitlement == ZingEntitlement.FREE -> SourcePlan.FREE
                    else -> SourcePlan.UNKNOWN
                },
            accountLine =
                when {
                    !zingSignedIn -> stringResource(Res.string.source_status_logged_out)
                    zing == null -> stringResource(Res.string.source_status_logged_in)
                    !zing.loggedIn -> stringResource(Res.string.source_status_session_expired)
                    zing.entitlement == ZingEntitlement.VIP -> stringResource(Res.string.source_status_logged_in_vip)
                    zing.entitlement == ZingEntitlement.FREE -> stringResource(Res.string.source_status_logged_in_free)
                    else -> stringResource(Res.string.source_status_logged_in)
                },
            displayName = zing?.displayName,
        )
    val nct = nctAccount
    val nctSignedIn = nctStored || nct?.loggedIn == true
    val nctState =
        SourceRowState(
            signedIn = nctSignedIn,
            plan =
                when {
                    !nctSignedIn || nct == null || !nct.loggedIn -> SourcePlan.UNKNOWN
                    nct.entitlement == NctEntitlement.VIP -> SourcePlan.VIP
                    else -> SourcePlan.FREE
                },
            accountLine =
                when {
                    !nctSignedIn -> stringResource(Res.string.source_status_logged_out)
                    nct == null -> stringResource(Res.string.source_status_logged_in)
                    !nct.loggedIn -> stringResource(Res.string.source_status_session_expired)
                    nct.entitlement == NctEntitlement.VIP -> stringResource(Res.string.source_status_logged_in_vip)
                    else -> stringResource(Res.string.source_status_logged_in_free)
                },
            displayName = nct?.displayName,
        )
    val youTubeSignedIn = hasSupportedYouTubeAuthCookie(settings.innerTubeCookie)
    val zingQuality =
        when {
            zingState.plan == SourcePlan.VIP -> stringResource(Res.string.source_quality_zing_vip)
            zingState.signedIn && zingState.plan == SourcePlan.UNKNOWN -> null
            else -> stringResource(Res.string.source_quality_128)
        }
    val nctQuality =
        when {
            nctState.plan == SourcePlan.VIP -> stringResource(Res.string.source_quality_320)
            nctState.signedIn && nctState.plan == SourcePlan.UNKNOWN -> null
            else -> stringResource(Res.string.source_quality_128)
        }
    val zingFailedMessage = stringResource(Res.string.source_zing_login_failed)

    fun health(id: SourceId): SourceHealth = registry?.health(id) ?: SourceHealth.HEALTHY

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScrollWithDrag(rememberScrollState())
                    .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_sources_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SourceGroup {
                SourceRow(
                    tileColor = YouTubeColor,
                    tileLabel = Res.string.source_badge_yt,
                    title = stringResource(Res.string.source_youtube_music),
                    subtitle =
                        joinStatus(
                            stringResource(
                                if (youTubeSignedIn) Res.string.source_status_youtube_signed_in else Res.string.source_status_youtube_guest,
                            ),
                            stringResource(Res.string.source_quality_youtube),
                        ),
                    badges = {
                        TagBadge(
                            text = stringResource(Res.string.source_badge_default),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                SourceDivider()
                val zingDegraded = settings.zingSourceEnabled && health(SourceId.ZING) == SourceHealth.DEGRADED
                SourceRow(
                    tileColor = ZingColor,
                    tileLabel = Res.string.source_badge_zing,
                    title = stringResource(Res.string.source_zing_mp3),
                    subtitle =
                        if (zingDegraded) {
                            stringResource(Res.string.source_status_degraded)
                        } else {
                            joinStatus(zingState.accountLine, zingQuality)
                        },
                    subtitleIsError = zingDegraded,
                    badges = { PlanBadges(plan = zingState.plan, degraded = zingDegraded) },
                    checked = settings.zingSourceEnabled,
                    onCheckedChange = { settings.zingSourceEnabled = it },
                )
                AccountActionRow(
                    signedIn = zingState.signedIn,
                    displayName = zingState.displayName,
                    busy = zingLoggingIn,
                    error = zingError,
                    footnote = stringResource(Res.string.source_zing_login_privacy),
                    onSignIn = {
                        if (!zingLoggingIn) {
                            zingLoggingIn = true
                            zingError = null
                            scope.launch {
                                try {
                                    DesktopSourceLogins
                                        .loginZing()
                                        .onSuccess { refreshKey++ }
                                        .onFailure { zingError = zingFailedMessage }
                                } finally {
                                    zingLoggingIn = false
                                }
                            }
                        }
                    },
                    onSignOut = { pendingLogout = SourceId.ZING },
                )
                SourceDivider()
                val nctDegraded = settings.nctSourceEnabled && health(SourceId.NCT) == SourceHealth.DEGRADED
                SourceRow(
                    tileColor = NctColor,
                    tileLabel = Res.string.source_badge_nct,
                    title = stringResource(Res.string.source_nhaccuatui),
                    subtitle =
                        if (nctDegraded) {
                            stringResource(Res.string.source_status_degraded)
                        } else {
                            joinStatus(nctState.accountLine, nctQuality)
                        },
                    subtitleIsError = nctDegraded,
                    badges = {
                        TagBadge(text = stringResource(Res.string.source_experimental))
                        PlanBadges(plan = nctState.plan, degraded = nctDegraded)
                    },
                    checked = settings.nctSourceEnabled,
                    onCheckedChange = { settings.nctSourceEnabled = it },
                )
                AccountActionRow(
                    signedIn = nctState.signedIn,
                    displayName = nctState.displayName,
                    busy = false,
                    error = null,
                    footnote = null,
                    onSignIn = { showNctLogin = true },
                    onSignOut = { pendingLogout = SourceId.NCT },
                )
                SourceDivider()
                val soundCloudDegraded = settings.soundCloudSourceEnabled && health(SourceId.SC) == SourceHealth.DEGRADED
                SourceRow(
                    tileColor = SoundCloudColor,
                    tileLabel = Res.string.source_badge_sc,
                    title = stringResource(Res.string.source_soundcloud),
                    subtitle =
                        if (soundCloudDegraded) {
                            stringResource(Res.string.source_status_degraded)
                        } else {
                            joinStatus(
                                stringResource(Res.string.source_status_no_account),
                                stringResource(Res.string.source_quality_up_to, stringResource(Res.string.source_quality_soundcloud)),
                            )
                        },
                    subtitleIsError = soundCloudDegraded,
                    badges = { PlanBadges(plan = SourcePlan.UNKNOWN, degraded = soundCloudDegraded) },
                    checked = settings.soundCloudSourceEnabled,
                    onCheckedChange = { settings.soundCloudSourceEnabled = it },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsOverline(
                    text = stringResource(Res.string.source_multi_source_header),
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                )
                SourceGroup {
                    SwitchRow(
                        title = stringResource(Res.string.source_auto_best_quality),
                        subtitle = stringResource(Res.string.source_auto_best_quality_desc),
                        checked = settings.autoBestSource,
                        onCheckedChange = { settings.autoBestSource = it },
                    )
                }
            }
        }
    }

    if (showNctLogin) {
        NctLoginDialog(
            onLoggedIn = {
                showNctLogin = false
                refreshKey++
            },
            onDismiss = { showNctLogin = false },
        )
    }

    pendingLogout?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingLogout = null },
            title = {
                Text(stringResource(if (source == SourceId.ZING) Res.string.source_zing_logout else Res.string.source_nct_logout))
            },
            text = { Text(stringResource(Res.string.source_logout_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLogout = null
                        scope.launch {
                            DesktopSourceLogins.logout(source)
                            if (source == SourceId.ZING) zingError = null
                            refreshKey++
                        }
                    },
                ) { Text(stringResource(Res.string.action_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLogout = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/**
 * NhacCuaTui sign-in. The password lives only in this dialog's state, is sent once through
 * [DesktopSourceLogins.loginNct] and is cleared right after (and when the dialog closes); errors show inline.
 */
@Composable
private fun NctLoginDialog(
    onLoggedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { password = "" } }
    val brand = LocalBrand.current
    val canSubmit = !isLoggingIn && username.isNotBlank() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        isLoggingIn = true
        error = null
        val secret = password
        password = ""
        scope.launch {
            try {
                val result = DesktopSourceLogins.loginNct(username.trim(), secret)
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    onLoggedIn()
                } else {
                    error =
                        getString(
                            when (failure) {
                                is NctException.AuthFailed -> Res.string.source_nct_login_failed
                                is NctException.Network -> Res.string.source_nct_login_network_error
                                else -> Res.string.source_nct_login_error
                            },
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = getString(Res.string.source_nct_login_error)
            } finally {
                isLoggingIn = false
            }
        }
    }

    val errorMessage = error
    AlertDialog(
        onDismissRequest = { if (!isLoggingIn) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.source_nct_login))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagBadge(text = stringResource(Res.string.source_experimental))
                    Text(
                        text = stringResource(Res.string.source_nct_login_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 360.dp, max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    label = { Text(stringResource(Res.string.source_nct_username)) },
                    leadingIcon = { Icon(MetrolistIcons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    enabled = !isLoggingIn,
                    isError = errorMessage != null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text(stringResource(Res.string.password)) },
                    leadingIcon = { Icon(MetrolistIcons.Rounded.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) MetrolistIcons.Filled.VisibilityOff else MetrolistIcons.Filled.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (passwordVisible) Res.string.source_hide_password else Res.string.source_show_password,
                                    ),
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isLoggingIn,
                    isError = errorMessage != null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(MetrolistIcons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(Res.string.source_nct_privacy_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = stringResource(Res.string.source_nct_privacy_password),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(Res.string.source_nct_privacy_token_desktop),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = brand.onGradient,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                modifier =
                    Modifier
                        .height(48.dp)
                        .clip(CircleShape)
                        .then(if (canSubmit) Modifier.background(brand.gradient) else Modifier),
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = brand.onGradient, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.action_login), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoggingIn) {
                Text(stringResource(Res.string.source_nct_use_free))
            }
        },
    )
}

@Composable
private fun joinStatus(
    first: String,
    second: String?,
): String = if (second == null) first else stringResource(Res.string.source_status_combined, first, second)

@Composable
private fun SourceGroup(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(GroupShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        content()
    }
}

@Composable
private fun SourceDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PlanBadges(
    plan: SourcePlan,
    degraded: Boolean,
) {
    val brand = LocalBrand.current
    when (plan) {
        SourcePlan.VIP -> {
            TagBadge(
                text = stringResource(Res.string.source_badge_vip),
                containerColor = brand.goldContainer,
                contentColor = brand.gold,
            )
        }

        SourcePlan.FREE -> {
            TagBadge(
                text = stringResource(Res.string.source_badge_free),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SourcePlan.UNKNOWN -> {}
    }
    if (degraded) {
        TagBadge(
            text = stringResource(Res.string.source_badge_paused),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun SourceTile(
    color: Color,
    label: StringResource,
) {
    Box(
        modifier = Modifier.size(40.dp).clip(TileShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun SourceRow(
    tileColor: Color,
    tileLabel: StringResource,
    title: String,
    subtitle: String,
    badges: @Composable () -> Unit,
    subtitleIsError: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(
                    if (checked != null) {
                        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceTile(tileColor, tileLabel)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badges()
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
        }
        if (checked != null) {
            ExpressiveSwitch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Sign-in (or sign-out) under a source with accounts, aligned with the text column; errors stay inline. */
@Composable
private fun AccountActionRow(
    signedIn: Boolean,
    displayName: String?,
    busy: Boolean,
    error: String?,
    footnote: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (signedIn) {
                OutlinedButton(onClick = onSignOut) { Text(stringResource(Res.string.action_logout)) }
                displayName?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                FilledTonalButton(onClick = onSignIn, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(Res.string.source_sign_in_vip))
                }
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!signedIn && footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        ExpressiveSwitch(checked = checked, onCheckedChange = null)
    }
}
