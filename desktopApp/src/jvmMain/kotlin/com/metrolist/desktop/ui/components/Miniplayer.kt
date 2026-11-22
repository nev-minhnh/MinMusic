/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.materialkolor.ktx.isLight
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.DesktopDensityProvider
import com.metrolist.desktop.ui.layouts.minMusicBrandLogo
import com.metrolist.desktop.ui.theme.MetrolistTheme
import com.metrolist.desktop.utils.ComposeTrayManager
import com.metrolist.desktop.utils.WindowDragHandle
import com.metrolist.desktop.window.WindowVisibility
import com.metrolist.desktop.window.WindowsMiniPlayerWindowFrame
import com.metrolist.desktop.window.restoreWindowPosition
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cd_playback_position
import com.metrolist.shared.generated.resources.close_miniplayer
import com.metrolist.shared.generated.resources.enter_focus_mode
import com.metrolist.shared.generated.resources.exit_focus_mode
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.miniplayer
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.no_track_playing
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.tray_open_app
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.ArtistSubtitle
import com.metrolist.shared.ui.components.MetadataLink
import com.metrolist.shared.ui.components.playbackMarquee
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.ui.theme.MinMotion
import com.metrolist.shared.ui.theme.MinMusicBrand
import com.metrolist.shared.ui.utils.ArtworkBlurStyle
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MiniplayerWindow() {
    if (!PlayerState.showMiniplayer || !PlayerState.miniplayerAvailable) return

    val isFocusMode = PlayerState.miniplayerFocusMode
    // MinMusic (Windows): a 320×88 strip, or the 320×320 square artwork mode.
    val windowSize =
        when {
            OsUtil.isWindows && isFocusMode -> DpSize(320.dp, 320.dp)
            OsUtil.isWindows -> DpSize(320.dp, 88.dp)
            isFocusMode -> DpSize(300.dp, 300.dp)
            else -> DpSize(420.dp, 160.dp)
        }
    val windowState =
        remember {
            val savedPosition =
                restoreWindowPosition(
                    Settings.shared.desktopMiniPlayerPosition,
                    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds },
                )
            WindowState(
                position = savedPosition?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.Aligned(Alignment.BottomEnd),
                size = windowSize,
            )
        }
    // Remember where the user dragged the mini-player; disposal also runs when the app quits.
    DisposableEffect(windowState) {
        onDispose {
            (windowState.position as? WindowPosition.Absolute)?.let {
                Settings.shared.desktopMiniPlayerPosition = "${it.x.value.toInt()},${it.y.value.toInt()}"
            }
        }
    }

    val isNightMode = Settings.shared.isPureBlackTheme()
    // Linux backends can consume the render loop without drawing transparent undecorated windows.
    val transparentWindow = !(OsUtil.isLinux || OsUtil.isWindows)

    Window(
        onCloseRequest = { PlayerState.showMiniplayer = false },
        title = stringResource(Res.string.miniplayer),
        icon = minMusicBrandLogo(),
        resizable = false,
        visible = true,
        undecorated = !OsUtil.isWindows,
        transparent = transparentWindow,
        state = windowState,
        alwaysOnTop = true,
        onKeyEvent = { event ->
            // Esc returns to the main window (desktop spec §5.6).
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                returnToMainWindow()
                true
            } else {
                false
            }
        },
    ) {
        val windowDensity = LocalDensity.current.density
        MetrolistTheme {
            DesktopDensityProvider {
                val scale = LocalDensity.current.density / windowDensity
                LaunchedEffect(windowSize, scale) {
                    windowState.size = DpSize(windowSize.width * scale, windowSize.height * scale)
                }
                WindowVisibility(window) {
                    if (OsUtil.isWindows) {
                        val areWeInDarkMode = remember { mutableStateOf(false) }
                        WindowsMiniPlayerWindowFrame(
                            onCloseRequest = { PlayerState.showMiniplayer = false },
                            content = {
                                val colorScheme = MaterialTheme.colorScheme
                                areWeInDarkMode.value = !MaterialTheme.colorScheme.background.isLight()
                                WindowDraggableArea {
                                    if (isFocusMode) {
                                        FocusModeMiniplayer(colorScheme, isNightMode, transparentWindow)
                                    } else {
                                        MinMusicMiniStrip(colorScheme, isNightMode)
                                    }
                                }
                            },
                            state = windowState,
                            darkTheme = areWeInDarkMode.value,
                        )
                    } else {
                        val colorScheme = MaterialTheme.colorScheme

                        WindowDragHandle(isMaximized = false, onRestoreFromMaximized = {}) {
                            if (isFocusMode) {
                                FocusModeMiniplayer(colorScheme, isNightMode, transparentWindow)
                            } else {
                                StandardMiniplayer(colorScheme, isNightMode, transparentWindow)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandardMiniplayer(
    colorScheme: ColorScheme,
    isNightMode: Boolean,
    transparentWindow: Boolean,
) {
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    Surface(
        modifier =
            Modifier
                .fillMaxSize(),
        shape = RoundedCornerShape(if (transparentWindow) 16.dp else 0.dp),
        color = if (isNightMode) Color.Black else colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (!OsUtil.isLinux) {
                if (Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.BLURRED_ARTWORK) {
                    AsyncImage(
                        url = PlayerState.currentArtworkUrl ?: "",
                        artworkPreview = PlayerState.artworkPreview,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(if (OsUtil.isWindows) 6.dp else 16.dp)),
                        contentScale = ContentScale.Crop,
                        blur =
                            ArtworkBlurStyle(
                                radius = 60.dp,
                                backgroundColor = if (isNightMode) Color.Black else colorScheme.surfaceContainerHigh,
                            ),
                    )
                }
                if (!PlayerState.miniplayerFocusMode) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(if (OsUtil.isWindows) 6.dp else 16.dp))
                                .background(
                                    if (isNightMode || !MaterialTheme.colorScheme.background.isLight()) {
                                        colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        colorScheme.primary.copy(alpha = 0.25f)
                                    },
                                ),
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        url = PlayerState.currentArtworkUrl ?: "",
                        artworkPreview = PlayerState.artworkPreview,
                        modifier = Modifier.songThumbSize(PlayerState.currentTrack, 80.dp).clip(RoundedCornerShape(4.dp)),
                        shape = RoundedCornerShape(4.dp),
                        contentScale = if (Settings.shared.cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                    )

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = PlayerState.currentTrack?.title ?: stringResource(Res.string.no_track_playing),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 84.dp)
                                    .playbackMarquee(
                                        iterations = 1,
                                        initialDelayMillis = 10000,
                                        velocity = 30.dp,
                                    ),
                        )

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 84.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val artists = PlayerState.currentTrack?.artists ?: emptyList()
                            ArtistSubtitle(
                                artists = artists,
                                onArtistClick = { artistId ->
                                    ArtistState.fetchArtistData(artistId)
                                    ComposeTrayManager.showWindow()
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .weight(1f, fill = false)
                                        .playbackMarquee(
                                            iterations = 1,
                                            initialDelayMillis = 10000,
                                            velocity = 30.dp,
                                        ),
                            )
                            val album = PlayerState.currentTrack?.album
                            if (album != null && album.name.isNotBlank()) {
                                Text(
                                    " • ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                MetadataLink(
                                    text = album.name,
                                    id = album.id,
                                    onClick = { albumId ->
                                        PlaylistAlbumState.fetchAlbumData(albumId)
                                        ComposeTrayManager.showWindow()
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier
                                            .weight(1f, fill = false)
                                            .playbackMarquee(
                                                iterations = 1,
                                                initialDelayMillis = 10000,
                                                velocity = 30.dp,
                                            ),
                                )
                            }
                        }

                        if (!isListenTogetherGuest) {
                            Spacer(Modifier.height(8.dp))

                            PlaybackControls(colorScheme)
                        }
                    }
                }

                MiniplayerProgress(
                    colorScheme = colorScheme,
                    enabled = !isListenTogetherGuest,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { Settings.shared.miniplayerFocusMode = !Settings.shared.miniplayerFocusMode },
                    modifier = Modifier.size(40.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        MetrolistIcons.Outlined.Fullscreen,
                        stringResource(Res.string.enter_focus_mode),
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { PlayerState.showMiniplayer = false },
                    modifier = Modifier.size(40.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        MetrolistIcons.Outlined.Close,
                        stringResource(Res.string.close_miniplayer),
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun returnToMainWindow() {
    ComposeTrayManager.showWindow()
    PlayerState.showMiniplayer = false
}

// Detects a double click without consuming the presses, so WindowDraggableArea can still move the window.
private fun Modifier.onDoubleClickPassive(onDoubleClick: () -> Unit): Modifier =
    pointerInput(onDoubleClick) {
        awaitPointerEventScope {
            var lastPressMillis = 0L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val pressMillis = event.changes.firstOrNull()?.uptimeMillis ?: continue
                if (lastPressMillis != 0L && pressMillis - lastPressMillis <= viewConfiguration.doubleTapTimeoutMillis) {
                    lastPressMillis = 0L
                    onDoubleClick()
                } else {
                    lastPressMillis = pressMillis
                }
            }
        }
    }

/**
 * MinMusic mini-player strip (desktop spec §5.6): 320×88, artwork 64, title and artist, previous / play / next,
 * a 2 dp brand-gradient progress line on the bottom edge. Hovering shows square-art, open-app and close buttons.
 * Drag anywhere outside the buttons to move it; double-click or Esc returns to the main window.
 */
@Composable
private fun MinMusicMiniStrip(
    colorScheme: ColorScheme,
    isNightMode: Boolean,
) {
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    val brand = LocalBrand.current
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    val track = PlayerState.currentTrack
    val title = track?.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.no_track_playing)
    val artists = track?.artists.orEmpty().joinToString { it.displayName }
    val surface = if (isNightMode) Color.Black else colorScheme.surfaceContainer

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surface)
                .hoverable(hoverSource),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).onDoubleClickPassive(::returnToMainWindow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    url = PlayerState.currentArtworkUrl ?: "",
                    artworkPreview = PlayerState.artworkPreview,
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentScale = if (Settings.shared.cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (artists.isNotBlank()) {
                        Text(
                            text = artists,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!isListenTogetherGuest) {
                Spacer(Modifier.width(8.dp))
                MiniStripIconButton(
                    icon = MetrolistIcons.Rounded.SkipPrevious,
                    contentDescription = stringResource(Res.string.previous),
                    enabled = PlayerState.canSkipPrevious,
                    tint = colorScheme.onSurface,
                    buttonSize = 32.dp,
                    iconSize = 20.dp,
                    onClick = { PlayerState.skipPrevious() },
                )
                MiniStripPlayButton(brand)
                MiniStripIconButton(
                    icon = MetrolistIcons.Rounded.SkipNext,
                    contentDescription = stringResource(Res.string.next),
                    enabled = true,
                    tint = colorScheme.onSurface,
                    buttonSize = 32.dp,
                    iconSize = 20.dp,
                    onClick = { PlayerState.skipNext() },
                )
            }
        }

        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn(animationSpec = MinMotion.fast()),
            exit = fadeOut(animationSpec = MinMotion.fast()),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp),
        ) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(surface.copy(alpha = 0.92f)),
            ) {
                MiniStripIconButton(
                    icon = MetrolistIcons.Outlined.Fullscreen,
                    contentDescription = stringResource(Res.string.enter_focus_mode),
                    enabled = true,
                    tint = colorScheme.onSurfaceVariant,
                    buttonSize = 22.dp,
                    iconSize = 14.dp,
                    onClick = { Settings.shared.miniplayerFocusMode = true },
                )
                MiniStripIconButton(
                    icon = MetrolistIcons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(Res.string.tray_open_app),
                    enabled = true,
                    tint = colorScheme.onSurfaceVariant,
                    buttonSize = 22.dp,
                    iconSize = 14.dp,
                    onClick = { ComposeTrayManager.showWindow() },
                )
                MiniStripIconButton(
                    icon = MetrolistIcons.Outlined.Close,
                    contentDescription = stringResource(Res.string.close_miniplayer),
                    enabled = true,
                    tint = colorScheme.onSurfaceVariant,
                    buttonSize = 22.dp,
                    iconSize = 14.dp,
                    onClick = { PlayerState.showMiniplayer = false },
                )
            }
        }

        MiniStripProgressLine(
            gradient = brand.gradient,
            trackColor = colorScheme.onSurface.copy(alpha = 0.12f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
        )
    }
}

@Composable
private fun MiniStripIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(buttonSize),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun MiniStripPlayButton(brand: MinMusicBrand) {
    val isLoading = PlayerState.isPlaybackLoading
    val isPlaying = PlayerState.isPlaying
    val description =
        stringResource(
            when {
                isLoading -> Res.string.loading
                isPlaying -> Res.string.pause
                else -> Res.string.play
            },
        )
    Box(
        modifier =
            Modifier
                .padding(horizontal = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(brand.gradient)
                .clickable(role = Role.Button) { PlayerState.togglePlay() }
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            LoadingIndicator(modifier = Modifier.size(20.dp), color = brand.onGradient)
        } else {
            Icon(
                imageVector = if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = brand.onGradient,
            )
        }
    }
}

// Thin progress line: the position is read in the draw phase only, so playback ticks never recompose the strip.
@Composable
private fun MiniStripProgressLine(
    gradient: Brush,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val isLive = playerIsLiveStream()
    val playerDuration by PlayerState.playbackDuration.collectAsState()
    var lineWidthPx by remember { mutableIntStateOf(0) }
    val position = rememberPlayerProgressPosition(playerDuration, lineWidthPx, isLive)
    Box(
        modifier =
            modifier
                .onSizeChanged { lineWidthPx = it.width }
                .drawBehind {
                    drawRect(color = trackColor)
                    val fraction =
                        when {
                            isLive -> 1f
                            playerDuration > 0L -> (position.longValue.toFloat() / playerDuration).coerceIn(0f, 1f)
                            else -> 0f
                        }
                    if (fraction > 0f) {
                        drawRect(brush = gradient, size = Size(size.width * fraction, size.height))
                    }
                },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FocusModeMiniplayer(
    colorScheme: ColorScheme,
    isNightMode: Boolean,
    transparentWindow: Boolean,
) {
    val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var hasFocus by remember { mutableStateOf(false) }
    val showControls = isHovered || hasFocus
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .hoverable(interactionSource)
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusable(interactionSource = interactionSource),
        shape = RoundedCornerShape(if (transparentWindow) 12.dp else 0.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                url = PlayerState.currentArtworkUrl ?: "",
                artworkPreview = PlayerState.artworkPreview,
                modifier =
                    Modifier.fillMaxSize().then(
                        if (!OsUtil.isLinux && showControls) Modifier.blur(16.dp) else Modifier,
                    ),
                shape = RoundedCornerShape(0.dp),
                contentScale = ContentScale.Crop,
            )

            if (showControls) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                if (isNightMode || !MaterialTheme.colorScheme.background.isLight()) {
                                    colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    colorScheme.primary.copy(alpha = 0.25f)
                                },
                            ),
                )
            }

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { Settings.shared.miniplayerFocusMode = !Settings.shared.miniplayerFocusMode },
                            modifier = Modifier.size(40.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.Outlined.FullscreenExit,
                                stringResource(Res.string.exit_focus_mode),
                                modifier = Modifier.size(18.dp),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = { PlayerState.showMiniplayer = false },
                            modifier = Modifier.size(40.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.Outlined.Close,
                                stringResource(Res.string.close_miniplayer),
                                modifier = Modifier.size(18.dp),
                                tint = Color.White,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = PlayerState.currentTrack?.title ?: stringResource(Res.string.no_track_playing),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .playbackMarquee(
                                        iterations = 1,
                                        initialDelayMillis = 10000,
                                        velocity = 30.dp,
                                    ),
                        )
                        ArtistSubtitle(
                            artists = PlayerState.currentTrack?.artists.orEmpty(),
                            onArtistClick = { artistId ->
                                ArtistState.fetchArtistData(artistId)
                                ComposeTrayManager.showWindow()
                            },
                            style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                            color = Color.White.copy(alpha = 0.7f),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .playbackMarquee(
                                        iterations = 1,
                                        initialDelayMillis = 10000,
                                        velocity = 30.dp,
                                    ),
                        )

                        if (!isListenTogetherGuest) {
                            Spacer(Modifier.height(24.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                IconButton(
                                    onClick = { PlayerState.skipPrevious() },
                                    enabled = PlayerState.canSkipPrevious,
                                    modifier = Modifier.size(40.dp),
                                    colors =
                                        IconButtonDefaults.iconButtonColors(
                                            containerColor = colorScheme.secondaryContainer,
                                            contentColor = colorScheme.onSecondaryContainer,
                                        ),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        MetrolistIcons.Rounded.SkipPrevious,
                                        stringResource(Res.string.previous),
                                        modifier = Modifier.size(28.dp),
                                        tint = colorScheme.onSecondaryContainer,
                                    )
                                }

                                Surface(
                                    onClick = { PlayerState.togglePlay() },
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.size(56.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (PlayerState.isPlaybackLoading) {
                                            val loadingText = stringResource(Res.string.loading)
                                            LoadingIndicator(
                                                modifier = Modifier.size(28.dp).semantics { contentDescription = loadingText },
                                                color = Color.White,
                                            )
                                        } else {
                                            val playPauseIcon =
                                                if (PlayerState.isPlaying) {
                                                    MetrolistIcons.Rounded.Pause
                                                } else {
                                                    MetrolistIcons.Rounded.PlayArrow
                                                }
                                            val playPauseDesc =
                                                if (PlayerState.isPlaying) {
                                                    stringResource(Res.string.pause)
                                                } else {
                                                    stringResource(Res.string.play)
                                                }
                                            Icon(
                                                imageVector = playPauseIcon,
                                                contentDescription = playPauseDesc,
                                                modifier = Modifier.size(32.dp),
                                                tint = Color.White,
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { PlayerState.skipNext() },
                                    modifier = Modifier.size(40.dp),
                                    colors =
                                        IconButtonDefaults.iconButtonColors(
                                            containerColor = colorScheme.secondaryContainer,
                                            contentColor = colorScheme.onSecondaryContainer,
                                        ),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        MetrolistIcons.Rounded.SkipNext,
                                        stringResource(Res.string.next),
                                        modifier = Modifier.size(28.dp),
                                        tint = colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }

                    MiniplayerProgress(
                        colorScheme = colorScheme,
                        enabled = !isListenTogetherGuest,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .height(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackControls(colorScheme: ColorScheme) {
    val backInteractionSource = remember { MutableInteractionSource() }
    val nextInteractionSource = remember { MutableInteractionSource() }
    val playPauseInteractionSource = remember { MutableInteractionSource() }

    val isBackPressed by backInteractionSource.collectIsPressedAsState()
    val isNextPressed by nextInteractionSource.collectIsPressedAsState()
    val isPlayPausePressed by playPauseInteractionSource.collectIsPressedAsState()

    val playPauseWeight by animateFloatAsState(
        targetValue =
            if (isPlayPausePressed) {
                1.8f
            } else if (isBackPressed || isNextPressed) {
                1.1f
            } else {
                1.3f
            },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
    )
    val backButtonWeight by animateFloatAsState(
        targetValue =
            if (isBackPressed) {
                0.65f
            } else if (isPlayPausePressed) {
                0.35f
            } else {
                0.45f
            },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
    )
    val nextButtonWeight by animateFloatAsState(
        targetValue =
            if (isNextPressed) {
                0.65f
            } else if (isPlayPausePressed) {
                0.35f
            } else {
                0.45f
            },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
    )

    Row(
        modifier = Modifier.width(180.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = { PlayerState.skipPrevious() },
            enabled = PlayerState.canSkipPrevious,
            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
            interactionSource = backInteractionSource,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor =
                        colorScheme.surfaceContainerHighest.copy(
                            alpha = 1.0f,
                        ),
                    contentColor = colorScheme.onSurface,
                ),
            modifier = Modifier.fillMaxHeight().weight(backButtonWeight),
        ) { Icon(MetrolistIcons.Rounded.SkipPrevious, stringResource(Res.string.previous), modifier = Modifier.size(20.dp)) }

        Spacer(Modifier.width(8.dp))

        val playDesc = stringResource(Res.string.play)
        val pauseDesc = stringResource(Res.string.pause)
        val loadingDesc = stringResource(Res.string.loading)

        MetrolistPlayPauseButton(
            isPlaying = PlayerState.isPlaying,
            isLoading = PlayerState.isPlaybackLoading,
            onClick = { PlayerState.togglePlay() },
            colorScheme = colorScheme,
            isWide = false,
            interactionSource = playPauseInteractionSource,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .weight(playPauseWeight)
                    .semantics {
                        contentDescription =
                            if (PlayerState.isPlaybackLoading) {
                                loadingDesc
                            } else if (PlayerState.isPlaying) {
                                pauseDesc
                            } else {
                                playDesc
                            }
                    },
        )

        Spacer(Modifier.width(8.dp))

        FilledIconButton(
            onClick = { PlayerState.skipNext() },
            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
            interactionSource = nextInteractionSource,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor =
                        colorScheme.surfaceContainerHighest.copy(
                            alpha = 1.0f,
                        ),
                    contentColor = colorScheme.onSurface,
                ),
            modifier = Modifier.fillMaxHeight().weight(nextButtonWeight),
        ) { Icon(MetrolistIcons.Rounded.SkipNext, stringResource(Res.string.next), modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun MiniplayerProgress(
    colorScheme: ColorScheme,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val isLive = playerIsLiveStream()
    val canSeek = enabled && !isLive
    val playerDuration by PlayerState.playbackDuration.collectAsState()
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    val smoothPosition = rememberPlayerProgressPosition(playerDuration, sliderWidthPx, isLive)
    // Read the initial slider value without subscribing this row to position samples.
    val sampledProgress = if (playerDuration > 0L) PlayerState.playbackPosition.value.toFloat() / playerDuration else 0f
    var isDragging by remember { mutableStateOf(false) }
    var pendingSeekTargetMs by remember { mutableStateOf<Long?>(null) }
    var dragStartPosMs by remember { mutableLongStateOf(-1L) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    val playbackPositionDescription = stringResource(Res.string.cd_playback_position)

    LaunchedEffect(pendingSeekTargetMs) {
        if (pendingSeekTargetMs != null) {
            delay(1200.milliseconds)
            pendingSeekTargetMs = null
        }
    }

    LaunchedEffect(isDragging, pendingSeekTargetMs, dragStartPosMs) {
        if (isDragging) return@LaunchedEffect
        val targetMs = pendingSeekTargetMs ?: return@LaunchedEffect
        PlayerState.playbackPosition.collect { sample ->
            if (sample != dragStartPosMs && abs(sample - targetMs) < 2000L) {
                pendingSeekTargetMs = null
            }
        }
    }
    if (isLive) {
        LiveProgressBar(
            modifier = modifier,
            color = colorScheme.primary,
        )
    } else {
        PlayerSlider(
            value = if (isDragging || pendingSeekTargetMs != null) sliderValue else sampledProgress.coerceIn(0f, 1f),
            valueProvider = {
                when {
                    isDragging || pendingSeekTargetMs != null -> sliderValue
                    playerDuration > 0L -> (smoothPosition.longValue.toFloat() / playerDuration).coerceIn(0f, 1f)
                    else -> 0f
                }
            },
            onValueChange = {
                if (canSeek) {
                    if (!isDragging) {
                        isDragging = true
                        dragStartPosMs = PlayerState.playbackPosition.value
                    }
                    pendingSeekTargetMs = null
                    sliderValue = it
                }
            },
            onValueChangeFinished = {
                if (canSeek) {
                    val targetMs = (sliderValue * playerDuration).toLong()
                    PlayerState.seekTo(sliderValue)
                    isDragging = false
                    pendingSeekTargetMs = targetMs
                }
            },
            style = Settings.shared.sliderStyle,
            isPlaying = PlayerState.isPlaying,
            color = colorScheme.primary,
            bufferedValue = PlayerState.bufferedProgress,
            enabled = canSeek,
            modifier =
                modifier
                    .onSizeChanged { sliderWidthPx = it.width }
                    .semantics { contentDescription = playbackPositionDescription },
        )
    }
}
