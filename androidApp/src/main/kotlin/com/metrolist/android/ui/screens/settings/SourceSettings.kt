package com.metrolist.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.android.source.credentials.SourceAccounts
import com.metrolist.shared.auth.hasSupportedYouTubeAuthCookie
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_logout
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.settings_sources
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
import com.metrolist.shared.generated.resources.source_logout_confirm
import com.metrolist.shared.generated.resources.source_multi_source_header
import com.metrolist.shared.generated.resources.source_nct_logout
import com.metrolist.shared.generated.resources.source_nhaccuatui
import com.metrolist.shared.generated.resources.source_quality_128
import com.metrolist.shared.generated.resources.source_quality_320
import com.metrolist.shared.generated.resources.source_quality_soundcloud
import com.metrolist.shared.generated.resources.source_quality_up_to
import com.metrolist.shared.generated.resources.source_quality_youtube
import com.metrolist.shared.generated.resources.source_quality_zing_vip
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
import com.metrolist.shared.generated.resources.source_zing_logout
import com.metrolist.shared.generated.resources.source_zing_mp3
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceHealth
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.nct.NctAccount
import com.metrolist.shared.source.nct.NctEntitlement
import com.metrolist.shared.source.zing.ZingAccount
import com.metrolist.shared.source.zing.ZingEntitlement
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.components.TagBadge
import com.metrolist.shared.ui.settings.SettingsListStyle
import com.metrolist.shared.ui.settings.SettingsOverline
import com.metrolist.shared.ui.theme.LocalBrand
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Plan of a signed-in account, as far as the source told us. */
private enum class SourcePlan { UNKNOWN, FREE, VIP }

/** What one source row needs, computed once per composition of the page. */
private class SourceRowState(
    val signedIn: Boolean,
    val plan: SourcePlan,
    val accountLine: String,
    val displayName: String?,
)

// Source identification colours (design-system 2.3): only as the 40 dp tile on this page and as badge dots.
private val YouTubeColor = Color(0xFFE8453C)
private val SoundCloudColor = Color(0xFFF26B1D)
private val ZingColor = Color(0xFF9B51E0)
private val NctColor = Color(0xFF2F80ED)
private val TileShape = RoundedCornerShape(12.dp)
private val GroupShape = RoundedCornerShape(SettingsListStyle.OuterCorner)

/**
 * Settings → Sources ("Nguồn nhạc") on Android: a switch per source with its account and quality, the VIP sign-in or
 * sign-out button under Zing MP3 and NhacCuaTui, and the highest-quality preference.
 */
@Composable
@Suppress("FunctionName")
internal fun SourcesSettingsScreen(
    onNavigateBack: () -> Unit,
    onZingLogin: () -> Unit,
    onNctLogin: () -> Unit,
) {
    val settings = Settings.shared
    val registry: SourceRegistry = koinInject()
    val scope = rememberCoroutineScope()
    val zingSignedIn = settings.zingSourceCookie.isNotBlank()
    val nctSignedIn = settings.nctSourceToken.isNotBlank()
    var zingAccount by remember { mutableStateOf<ZingAccount?>(null) }
    var nctAccount by remember { mutableStateOf<NctAccount?>(null) }
    var pendingLogout by remember { mutableStateOf<SourceId?>(null) }
    LaunchedEffect(zingSignedIn) {
        zingAccount = null
        if (zingSignedIn) zingAccount = SourceAccounts.zingAccount()
    }
    LaunchedEffect(nctSignedIn) {
        nctAccount = null
        if (nctSignedIn) nctAccount = SourceAccounts.nctAccount()
    }

    // A null account while signed in means the check has not finished or the source could not be reached.
    val zing = zingAccount
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

    SettingsSubScreen(
        title = stringResource(Res.string.settings_sources),
        onNavigateBack = onNavigateBack,
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
            val zingDegraded = settings.zingSourceEnabled && registry.health(SourceId.ZING) == SourceHealth.DEGRADED
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
                onSignIn = onZingLogin,
                onSignOut = { pendingLogout = SourceId.ZING },
            )
            SourceDivider()
            val nctDegraded = settings.nctSourceEnabled && registry.health(SourceId.NCT) == SourceHealth.DEGRADED
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
                onSignIn = onNctLogin,
                onSignOut = { pendingLogout = SourceId.NCT },
            )
            SourceDivider()
            val soundCloudDegraded =
                settings.soundCloudSourceEnabled && registry.health(SourceId.SC) == SourceHealth.DEGRADED
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
                            if (source == SourceId.ZING) SourceAccounts.logoutZing() else SourceAccounts.logoutNct()
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

/** One source: colour tile, name with badges, account and quality line, and the on/off switch when it has one. */
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

/** The sign-in or sign-out button under a source that has accounts, aligned with the text column. */
@Composable
private fun AccountActionRow(
    signedIn: Boolean,
    displayName: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(52.dp))
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
            FilledTonalButton(onClick = onSignIn) { Text(stringResource(Res.string.source_sign_in_vip)) }
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
