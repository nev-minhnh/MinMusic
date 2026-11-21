/*
 * MinMusic full player building blocks ("Sân khấu tối" design, mockups.html "Trình phát").
 * The brand gradient is used only for the play button and the played part of the progress bar.
 */

package com.metrolist.shared.ui.components.minmusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.collapse
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.repeat_mode_all
import com.metrolist.shared.generated.resources.repeat_mode_off
import com.metrolist.shared.generated.resources.repeat_mode_one
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.ui.theme.MinMotion
import org.jetbrains.compose.resources.stringResource

/** Ink at 56%: secondary and meta text (design-system "ink3"). */
private const val INK3_ALPHA = 0.56f

/** Ink at 76%: body text and resting icons (design-system "ink2"). */
private const val INK2_ALPHA = 0.76f

/**
 * The 72 dp play button: brand gradient fill with #0B0B0B content. It is a squircle (r24) while playing and
 * morphs to a circle when paused. Shape and press scale are applied in the layer block only.
 */
@Composable
fun MmPlayerPlayButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    icon: ImageVector? = null,
    label: String? = null,
) {
    val brand = LocalBrand.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale =
        animateFloatAsState(
            targetValue = if (pressed) MinMotion.PRESS_SCALE else 1f,
            animationSpec = if (pressed) MinMotion.pressIn<Float>() else MinMotion.pressOut<Float>(),
            label = "mmPlayScale",
        )
    val corner =
        animateDpAsState(
            targetValue = if (isPlaying) size / 3 else size / 2,
            animationSpec = MinMotion.standardEnter<Dp>(),
            label = "mmPlayCorner",
        )
    val actionLabel = label ?: stringResource(if (isPlaying) Res.string.pause else Res.string.play)
    val loadingLabel = stringResource(Res.string.loading)
    Box(
        modifier =
            modifier
                .size(size)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    shape = RoundedCornerShape(corner.value)
                    clip = true
                }.background(brand.gradient)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = brand.onGradient),
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = actionLabel,
                    onClick = onClick,
                ).semantics { contentDescription = actionLabel },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp).semantics { contentDescription = loadingLabel },
                color = brand.onGradient,
                strokeWidth = 3.dp,
                trackColor = brand.onGradient.copy(alpha = 0.2f),
            )
        } else {
            Icon(
                imageVector = icon ?: if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                contentDescription = null,
                tint = brand.onGradient,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Shuffle, previous, play/pause, next and repeat on one 72 dp row. [repeatMode] follows the player
 * convention: 0 off, 1 one, 2 all. Null callbacks hide shuffle/repeat (e.g. listen-together guests).
 */
@Composable
fun MmPlayerTransportRow(
    isPlaying: Boolean,
    isLoading: Boolean,
    isShuffleOn: Boolean,
    repeatMode: Int,
    onToggleShuffle: (() -> Unit)?,
    onToggleRepeat: (() -> Unit)?,
    onSkipPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
    playButtonSize: Dp = 72.dp,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    playPauseFocusRequester: FocusRequester? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val restColor = contentColor.copy(alpha = INK2_ALPHA)
    Row(
        modifier = modifier.fillMaxWidth().height(playButtonSize),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggleShuffle != null) {
            val shuffleLabel = stringResource(Res.string.shuffle)
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.Shuffle,
                    contentDescription = shuffleLabel,
                    tint = if (isShuffleOn) accent else restColor,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        IconButton(onClick = onSkipPrevious, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = MetrolistIcons.Rounded.SkipPrevious,
                contentDescription = stringResource(Res.string.previous),
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
        }
        MmPlayerPlayButton(
            isPlaying = isPlaying,
            isLoading = isLoading,
            onClick = onTogglePlay,
            size = playButtonSize,
            focusRequester = playPauseFocusRequester,
        )
        IconButton(onClick = onSkipNext, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = MetrolistIcons.Rounded.SkipNext,
                contentDescription = stringResource(Res.string.next),
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
        }
        if (onToggleRepeat != null) {
            val repeatLabel =
                stringResource(
                    when (repeatMode) {
                        1 -> Res.string.repeat_mode_one
                        2 -> Res.string.repeat_mode_all
                        else -> Res.string.repeat_mode_off
                    },
                )
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    imageVector = if (repeatMode == 1) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                    contentDescription = repeatLabel,
                    tint = if (repeatMode != 0) accent else restColor,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/**
 * Progress track for a Slider: a 4 dp line whose played part uses the brand gradient, and a 4 x 16 dp bar thumb.
 * [progress] and [bufferedFraction] are read only while drawing, so playback never recomposes the slider.
 */
@Composable
fun MmPlayerProgressTrack(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    bufferedFraction: () -> Float = { 0f },
    showThumb: Boolean = true,
) {
    val colors = LocalBrand.current.gradientColors
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val bufferColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(16.dp)
                .drawWithCache {
                    val brush = Brush.horizontalGradient(colors, startX = 0f, endX = size.width)
                    val trackHeight = 4.dp.toPx()
                    val top = (size.height - trackHeight) / 2f
                    val radius = CornerRadius(trackHeight / 2f)
                    val thumbWidth = 4.dp.toPx()
                    onDrawBehind {
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        fun segment(fraction: Float): Pair<Float, Float> {
                            val width = size.width * fraction.coerceIn(0f, 1f)
                            return (if (rtl) size.width - width else 0f) to width
                        }
                        drawRoundRect(trackColor, Offset(0f, top), Size(size.width, trackHeight), radius)
                        val (bufferStart, bufferWidth) = segment(bufferedFraction())
                        if (bufferWidth > 0f) drawRoundRect(bufferColor, Offset(bufferStart, top), Size(bufferWidth, trackHeight), radius)
                        val (playedStart, playedWidth) = segment(progress())
                        if (playedWidth > 0f) drawRoundRect(brush, Offset(playedStart, top), Size(playedWidth, trackHeight), radius)
                        if (showThumb) {
                            val centre = if (rtl) size.width - playedWidth else playedWidth
                            val left = (centre - thumbWidth / 2f).coerceIn(0f, (size.width - thumbWidth).coerceAtLeast(0f))
                            drawRoundRect(brush, Offset(left, 0f), Size(thumbWidth, size.height), CornerRadius(thumbWidth / 2f))
                        }
                    }
                },
    )
}

/** A 36 dp tonal chip button (output device, EQ). [showBrandDot] marks an active sound effect. */
@Composable
fun MmPlayerChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showBrandDot: Boolean = false,
    active: Boolean = false,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (active) scheme.primaryContainer else scheme.surfaceContainer
    val content = if (active) scheme.onPrimaryContainer else scheme.onSurface
    val brand = LocalBrand.current
    Row(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(container)
                .clickable(role = Role.Button, onClick = onClick)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ).padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBrandDot) Box(Modifier.size(6.dp).background(brand.gradient, CircleShape))
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 128.dp),
        )
    }
}

/** Collapse chevron, "playing from" overline + queue title, optional actions and the overflow menu. */
@Composable
fun MmPlayerTopBar(
    overline: String,
    title: String?,
    onCollapse: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                imageVector = MetrolistIcons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.collapse),
                tint = contentColor,
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = overline.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = INK3_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
        IconButton(onClick = onMore) {
            Icon(
                imageVector = MetrolistIcons.Default.MoreVert,
                contentDescription = stringResource(Res.string.more_options),
                tint = contentColor,
            )
        }
    }
}

/**
 * The lyrics peek card anchored at the bottom of the player: overline, the current line (title size) and the
 * next line. Tapping it or swiping it up opens the lyrics.
 */
@Composable
fun MmPlayerLyricsPeek(
    title: String,
    hint: String,
    currentLine: String?,
    nextLine: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    shape: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
) {
    val latestOnOpen by rememberUpdatedState(onOpen)
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerInput(Unit) {
                    val threshold = 32.dp.toPx()
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total < -threshold) latestOnOpen() },
                        onDragCancel = { total = 0f },
                    ) { change, dragAmount ->
                        total += dragAmount
                        change.consume()
                    }
                }.clickable(role = Role.Button, onClickLabel = title, onClick = onOpen)
                .padding(bottom = bottomInset)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = ink.copy(alpha = INK3_ALPHA),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = INK3_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = currentLine.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = nextLine.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = INK3_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Height of [MmPlayerQueueHandle]: a 52 dp pill (above the 48 dp touch minimum). */
val MmPlayerQueueHandleHeight = 52.dp

/**
 * The full player's one entry to the playing queue: a full-width pill on the surface container (no gradient,
 * which is reserved for play and progress) with a queue icon, [overline] ("Up next") above [label] (the next
 * song's title or the queue size) and a chevron. Tapping opens the queue; a swipe up is not consumed here, so
 * it drags the queue sheet that hosts this handle.
 */
@Composable
fun MmPlayerQueueHandle(
    overline: String,
    label: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MmPlayerQueueHandleHeight)
                .clip(RoundedCornerShape(MmPlayerQueueHandleHeight / 2))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onOpen)
                .padding(start = 16.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = overline.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = INK3_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = MetrolistIcons.Outlined.KeyboardArrowUp,
            contentDescription = null,
            tint = ink.copy(alpha = INK2_ALPHA),
        )
    }
}

// Relative luminance for CIE L* 20 and L* 90 (design-system section 2.4).
private const val TINT_DARK_MAX_LUMINANCE = 0.03f
private const val TINT_LIGHT_MIN_LUMINANCE = 0.76f

/** Clamps an artwork colour so the player tint stays quiet: L* <= 20 in dark themes, L* >= 90 in light ones. */
fun mmPlayerTint(
    color: Color,
    darkTheme: Boolean,
): Color {
    var tint = color.copy(alpha = 1f)
    repeat(24) {
        val luminance = tint.luminance()
        if (darkTheme && luminance <= TINT_DARK_MAX_LUMINANCE) return tint
        if (!darkTheme && luminance >= TINT_LIGHT_MIN_LUMINANCE) return tint
        tint = lerp(tint, if (darkTheme) Color.Black else Color.White, 0.2f)
    }
    return tint
}

/**
 * The full player's static background: a vertical gradient from the artwork tint to the page background at 62%.
 * The tint cross-fades over 300 ms on track changes; the colour is read while drawing only.
 */
@Composable
fun MmPlayerTintBackground(
    artworkColor: Color?,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    val darkTheme = background.luminance() < 0.5f
    val fallback = MaterialTheme.colorScheme.surfaceContainer
    val target = remember(artworkColor, darkTheme, fallback) { artworkColor?.let { mmPlayerTint(it, darkTheme) } ?: fallback }
    val tint = animateColorAsState(target, tween(MinMotion.TINT_CROSSFADE_MILLIS), label = "mmPlayerTint")
    Spacer(
        modifier =
            modifier.drawBehind {
                drawRect(Brush.verticalGradient(0f to tint.value, 0.62f to background))
            },
    )
}
