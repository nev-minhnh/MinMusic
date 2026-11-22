/*
 * MinMusic desktop player pieces ("Sân khấu tối"): the bar's gradient play button, source and quality badges, the
 * output / EQ chips, the lyrics card and the "Up next" queue entry. Used when Appearance -> Player type is MinMusic
 * (the default); the other player types keep the stock Metrolist desktop layout.
 */

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.deviceconnect.rememberDeviceConnectControlledTarget
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.device_connect_controlling
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.mm_desktop_lyrics_peek_hint
import com.metrolist.shared.generated.resources.mm_player_eq_chip
import com.metrolist.shared.generated.resources.mm_player_eq_chip_profile
import com.metrolist.shared.generated.resources.mm_player_lyrics_peek_empty
import com.metrolist.shared.generated.resources.mm_player_queue_open
import com.metrolist.shared.generated.resources.mm_player_queue_song_count
import com.metrolist.shared.generated.resources.mm_player_queue_up_next
import com.metrolist.shared.generated.resources.mm_player_this_device
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.settings.PlayerType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.SourceQualityBadges
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState
import com.metrolist.shared.ui.components.minmusic.MmPlayerChip
import com.metrolist.shared.ui.components.minmusic.MmPlayerLyricsPeek
import com.metrolist.shared.ui.components.minmusic.MmPlayerPlayButton
import com.metrolist.shared.ui.components.minmusic.MmPlayerQueueHandle
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** True when the user's player type is the MinMusic player (the default). */
internal fun isMinMusicPlayer(): Boolean = Settings.shared.playerType == PlayerType.MINMUSIC

/** Source + quality badges of the playing song; the resolved stream wins over the stored format. */
@Composable
internal fun MmDesktopNowPlayingBadges(modifier: Modifier = Modifier) {
    val songId = PlayerState.currentTrack?.id ?: return
    val resource by PlayerState.player.currentPlaybackResource.collectAsState()
    SourceQualityBadges(songId = songId, playbackResource = resource, modifier = modifier)
}

/** Previous, the brand-gradient play button and next, for the bottom player bar. */
@Composable
internal fun MmDesktopBarTransport(
    playButtonSize: Dp,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { PlayerState.skipPrevious() }, enabled = PlayerState.canSkipPrevious) {
            Icon(
                imageVector = MetrolistIcons.Rounded.SkipPrevious,
                contentDescription = stringResource(Res.string.previous),
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
        }
        MmPlayerPlayButton(
            isPlaying = PlayerState.isPlaying,
            isLoading = PlayerState.isPlaybackLoading,
            onClick = { PlayerState.togglePlay() },
            size = playButtonSize,
            useRoundedRect = Settings.shared.useRoundedRectPlayButton,
        )
        IconButton(onClick = { PlayerState.skipNext() }) {
            Icon(
                imageVector = MetrolistIcons.Rounded.SkipNext,
                contentDescription = stringResource(Res.string.next),
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** The bar's EQ entry: opens the full-window equalizer. Tinted with the accent while a profile is active. */
@Composable
internal fun MmDesktopEqualizerButton(modifier: Modifier = Modifier) {
    val active = Settings.shared.equalizerEnabled && EqualizerProfiles.activeProfile != null
    IconButton(onClick = UiState::openEqualizer, modifier = modifier) {
        Icon(
            imageVector = MetrolistIcons.Outlined.Tune,
            contentDescription = stringResource(Res.string.equalizer),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Output device and "EQ · profile" chips under the full player's controls. The output chip names the cast
 * receiver while casting (and opens the cast dialog), otherwise the output the EQ engine reports, else
 * "This device".
 */
@Composable
internal fun MmDesktopPlayerChips(
    onOpenCast: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val castState by PlayerState.castManager.state.collectAsState()
    val controlledTarget = rememberDeviceConnectControlledTarget(DesktopDeviceConnect.manager)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val outputName =
            controlledTarget?.let { stringResource(Res.string.device_connect_controlling, it.name) }
                ?: castState.device?.name?.takeIf { castState.isConnected && it.isNotBlank() }
                ?: EqualizerProfiles.outputDevice?.productName?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.mm_player_this_device)
        MmPlayerChip(
            label = outputName,
            icon =
                when {
                    controlledTarget != null -> MetrolistIcons.Outlined.Devices
                    castState.isConnected -> MetrolistIcons.Filled.Cast
                    else -> MetrolistIcons.Outlined.Headphones
                },
            // While controlling another device the chip opens the device panel with "play here" and "disconnect".
            onClick = { if (controlledTarget != null) UiState.toggleDeviceConnectPanel() else onOpenCast?.invoke() },
            active = castState.isConnected || controlledTarget != null,
        )
        val profileName =
            EqualizerProfiles.activeProfile?.name?.takeIf { Settings.shared.equalizerEnabled && it.isNotBlank() }
        MmPlayerChip(
            label =
                if (profileName != null) {
                    stringResource(Res.string.mm_player_eq_chip_profile, profileName)
                } else {
                    stringResource(Res.string.mm_player_eq_chip)
                },
            icon = MetrolistIcons.Outlined.Tune,
            onClick = UiState::openEqualizer,
            showBrandDot = profileName != null,
        )
    }
}

/**
 * The lyrics card: the current and next line, following playback. Clicking it opens the lyrics pane (beside the
 * player on wide windows, below it on narrow ones). Hidden while the lyrics pane is open.
 */
@Composable
internal fun MmDesktopLyricsPeek(modifier: Modifier = Modifier) {
    val track = PlayerState.currentTrack
    LaunchedEffect(track?.id, Settings.shared.lyricsProviderOrder, Settings.shared.lyricsEnabledProviders) {
        track?.let { LyricsState.fetchLyrics(it) }
    }
    val lyrics = LyricsState.currentLyricsEntries
    val timed = LyricsUtils.hasPlaybackTiming(lyrics)
    val castState by PlayerState.castManager.state.collectAsState()
    val positionState =
        rememberCaptionLyricsPositionState(
            lyrics = lyrics,
            currentPositionFlow = PlayerState.playbackPosition,
            positionReader = PlayerState::currentMediaPositionMs,
            playbackSpeed =
                PlayerState.player.playbackSpeed
                    .collectAsState()
                    .value,
            isPlaying = PlayerState.isPlaying,
            songId = track?.id,
            timingOffsetMs = LyricsState.currentLyricsOffsetMs,
            isCasting = castState.isConnected,
            isBuffering = castState.isBuffering,
            enabled = timed && !UiState.videoFullscreenObscuresPlayer,
        )
    // Only a line change recomposes the card, not every position tick.
    val lineIndex by remember(lyrics, positionState, timed) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (timed) LyricsUtils.findCurrentLineIndex(lyrics, positionState.value) else -1
        }
    }
    val current = lyrics.getOrNull(lineIndex)?.text?.takeIf { it.isNotBlank() }
    val next =
        if (timed) {
            lyrics.getOrNull(lineIndex + 1)?.text
        } else {
            null
        }
    val emptyHint = stringResource(Res.string.mm_player_lyrics_peek_empty)
    MmPlayerLyricsPeek(
        title = stringResource(Res.string.lyrics),
        hint = stringResource(Res.string.mm_desktop_lyrics_peek_hint),
        currentLine = current ?: lyrics.firstOrNull()?.text?.takeIf { !timed } ?: emptyHint,
        nextLine = next ?: lyrics.getOrNull(1)?.text?.takeIf { !timed },
        onOpen = {
            if (!UiState.showLyricsDrawer) LyricsState.toggleLyricsDrawer()
        },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    )
}

/** "Up next": the next song's title (or the queue size); clicking toggles the queue panel. */
@Composable
internal fun MmDesktopQueueHandle(modifier: Modifier = Modifier) {
    val queueState by PlayerState.queue.state.collectAsState()
    val nextTitle = queueState.entries.getOrNull(queueState.currentIndex + 1)?.song?.title
    val queueSize = queueState.entries.size
    MmPlayerQueueHandle(
        overline = stringResource(Res.string.mm_player_queue_up_next),
        label =
            nextTitle?.takeIf { it.isNotBlank() }
                ?: pluralStringResource(Res.plurals.mm_player_queue_song_count, queueSize, queueSize),
        onOpen = UiState::togglePersistentPlayerDrawer,
        onClickLabel = stringResource(Res.string.mm_player_queue_open),
        modifier = modifier,
    )
}
