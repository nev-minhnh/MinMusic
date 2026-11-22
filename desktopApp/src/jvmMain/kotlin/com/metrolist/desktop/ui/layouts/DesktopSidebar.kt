/*
 * MinMusic desktop navigation (design/desktop/desktop-layout-spec.md, sections 3.1-3.2): the docked 256 sidebar
 * (Expanded), the 80 rail with labels (Medium) and the 64 icon rail (Compact). Desktop only; the shared
 * AdaptiveNavigationScaffold that Android uses is left untouched.
 */

@file:OptIn(ExperimentalFoundationApi::class)

package com.metrolist.desktop.ui.layouts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.expand_sidebar
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.mm_desk_collapse_sidebar
import com.metrolist.shared.generated.resources.my_albums
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext

/** One navigation destination or action shown in the sidebar and the rails. */
@Immutable
internal data class DesktopNavEntry(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
    val badge: String? = null,
    val shortcut: String? = null,
)

/** The sidebar's groups: primary navigation, "CỦA BẠN" shortcuts, and the pinned settings entry. */
@Immutable
internal data class DesktopNavModel(
    val primary: List<DesktopNavEntry>,
    val yours: List<DesktopNavEntry>,
    val yoursLabel: String,
    val settings: DesktopNavEntry,
    val onOpenUserAlbum: (PlaylistEntity) -> Unit,
)

private val NavItemShape = RoundedCornerShape(20.dp)

@Composable
private fun chromeOverline(): androidx.compose.ui.text.TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)

/** User albums (isUserAlbum playlists), newest first. Empty when the database isn't ready. */
@Composable
internal fun rememberUserAlbums(): State<List<PlaylistEntity>> {
    val flow: Flow<List<PlaylistEntity>> =
        remember {
            runCatching { GlobalContext.get().get<MusicDatabase>().dao().userAlbums() }.getOrNull() ?: flowOf(emptyList())
        }
    return flow.collectAsState(initial = emptyList())
}

/** The owner's logo tile (rounded square, never recoloured). */
@Composable
internal fun MinMusicLogoTile(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(Res.drawable.logo),
        contentDescription = stringResource(Res.string.app_name),
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 4)),
    )
}

/**
 * The docked (or overlaying) 256 sidebar: brand row at title-bar height (a window drag region on Windows), primary
 * navigation, "CỦA BẠN", "ALBUM CỦA TÔI" (the only part that scrolls) and Cài đặt pinned at the bottom.
 */
@Composable
internal fun DesktopSidebar(
    model: DesktopNavModel,
    width: Dp,
    titleBarHeight: Dp,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val userAlbums by rememberUserAlbums()
    Surface(
        modifier = modifier.width(width).fillMaxHeight(),
        color = colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(titleBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(4.dp))
                MinMusicLogoTile(size = 28.dp)
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                ChromeIconButton(
                    icon = MetrolistIcons.AutoMirrored.Filled.MenuOpen,
                    contentDescription = stringResource(Res.string.mm_desk_collapse_sidebar),
                    tooltip = stringResource(Res.string.mm_desk_collapse_sidebar) + "  Ctrl B",
                    onClick = onCollapse,
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                model.primary.forEach { entry -> SidebarItem(entry) }
            }
            if (model.yours.isNotEmpty()) {
                SidebarOverline(model.yoursLabel)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    model.yours.forEach { entry -> SidebarItem(entry) }
                }
            }
            SidebarOverline(stringResource(Res.string.my_albums).uppercase())
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(userAlbums, key = { it.id }, contentType = { "user_album" }) { album ->
                    SidebarAlbumRow(album = album, onClick = { model.onOpenUserAlbum(album) })
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
            Box(Modifier.padding(vertical = 8.dp)) {
                SidebarItem(model.settings)
            }
        }
    }
}

@Composable
private fun SidebarOverline(text: String) {
    Text(
        text = text,
        style = chromeOverline(),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Sidebar item: height 40, radius 20, icon 20; active = accent-container pill + 600 + filled icon; hover = ink 6 %. */
@Composable
private fun SidebarItem(entry: DesktopNavEntry) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val container =
        when {
            entry.selected -> colorScheme.primaryContainer
            hovered -> colorScheme.onSurface.copy(alpha = 0.06f)
            else -> Color.Transparent
        }
    val content = if (entry.selected) colorScheme.onPrimaryContainer else colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(NavItemShape)
                .background(container)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = entry.onClick)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.selected) entry.selectedIcon else entry.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        entry.badge?.let { badge ->
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = colorScheme.onSurface.copy(alpha = 0.56f),
                maxLines = 1,
            )
        }
    }
}

/** "Album của tôi" row: 48 high, art 40 r8, name 14. */
@Composable
private fun SidebarAlbumRow(
    album: PlaylistEntity,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAlbumArt(album = album, size = 40.dp)
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
private fun UserAlbumArt(
    album: PlaylistEntity,
    size: Dp,
) {
    val url = album.customThumbnailUrl ?: album.thumbnailUrl
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = MetrolistIcons.Outlined.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        } else {
            com.metrolist.shared.ui.components.images.AsyncImage(
                url = url,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * Navigation rail: Medium 80 (logo 28, ☰, three rail items with 12 sp labels, divider, Thích / Đã tải / EQ as
 * 40 icon buttons, divider, user albums as 40 art tiles, Cài đặt at the bottom) or Compact 64 (logo 24 and 40
 * icon buttons without labels). Every button has a tooltip with its shortcut.
 */
@Composable
internal fun DesktopNavRail(
    model: DesktopNavModel,
    compact: Boolean,
    titleBarHeight: Dp,
    onOpenSidebar: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val userAlbums by rememberUserAlbums()
    val width = if (compact) DesktopLayoutRules.RailCompactWidth else DesktopLayoutRules.RailMediumWidth
    Surface(
        modifier = modifier.width(width).fillMaxHeight(),
        color = colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(titleBarHeight), contentAlignment = Alignment.Center) {
                MinMusicLogoTile(size = if (compact) 24.dp else 28.dp)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onOpenSidebar != null) {
                    ChromeIconButton(
                        icon = MetrolistIcons.Filled.Menu,
                        contentDescription = stringResource(Res.string.expand_sidebar),
                        tooltip = stringResource(Res.string.expand_sidebar) + "  Ctrl B",
                        onClick = onOpenSidebar,
                    )
                }
                model.primary.forEach { entry ->
                    if (compact) RailIconItem(entry) else RailLabeledItem(entry)
                }
                if (model.yours.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    model.yours.forEach { entry -> RailIconItem(entry) }
                }
                if (userAlbums.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    userAlbums.forEach { album ->
                        RailAlbumTile(album = album, onClick = { model.onOpenUserAlbum(album) })
                    }
                }
            }
            Box(Modifier.padding(vertical = 8.dp)) {
                RailIconItem(model.settings)
            }
        }
    }
}

/** M3 rail item: pill 56×32 with the icon, 12 sp label under it. */
@Composable
private fun RailLabeledItem(entry: DesktopNavEntry) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Column(
        modifier =
            Modifier
                .width(72.dp)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = entry.onClick)
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .width(56.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        when {
                            entry.selected -> colorScheme.primaryContainer
                            hovered -> colorScheme.onSurface.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (entry.selected) entry.selectedIcon else entry.icon,
                contentDescription = null,
                tint = if (entry.selected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (entry.selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 40×40 icon button (radius 20) with a tooltip "label  shortcut". */
@Composable
private fun RailIconItem(entry: DesktopNavEntry) {
    val tooltip = if (entry.shortcut != null) entry.label + "  " + entry.shortcut else entry.label
    ChromeIconButton(
        icon = if (entry.selected) entry.selectedIcon else entry.icon,
        contentDescription = entry.label,
        tooltip = tooltip,
        onClick = entry.onClick,
        selected = entry.selected,
    )
}

@Composable
private fun RailAlbumTile(
    album: PlaylistEntity,
    onClick: () -> Unit,
) {
    ChromeTooltip(text = album.name) {
        val interactionSource = remember { MutableInteractionSource() }
        val hovered by interactionSource.collectIsHoveredAsState()
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
                    .hoverable(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            UserAlbumArt(album = album, size = 40.dp)
        }
    }
}

/** Desktop tooltip (shown after 500 ms) used by the chrome's icon buttons. */
@Composable
internal fun ChromeTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = 500,
    ) {
        content()
    }
}

/** 36-40 dp round icon button with hover fill and a tooltip; the chrome's standard button. */
@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tooltip: String? = contentDescription,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val button: @Composable () -> Unit = {
        val interactionSource = remember { MutableInteractionSource() }
        val hovered by interactionSource.collectIsHoveredAsState()
        Box(
            modifier =
                modifier
                    .size(size)
                    .clip(RoundedCornerShape(size / 2))
                    .background(
                        when {
                            selected -> colorScheme.primaryContainer
                            hovered && enabled -> colorScheme.onSurface.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                    ).hoverable(interactionSource, enabled = enabled)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint =
                    when {
                        selected -> colorScheme.onPrimaryContainer
                        enabled -> colorScheme.onSurfaceVariant
                        else -> colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (tooltip != null && enabled) ChromeTooltip(text = tooltip, content = button) else button()
}
