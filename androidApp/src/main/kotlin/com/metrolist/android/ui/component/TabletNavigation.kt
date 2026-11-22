/*
 * MinMusic tablet navigation: one component in two widths.
 * Medium windows (600-839 dp) get the 72 dp rail, expanded windows (>= 840 dp) the 240 dp sidebar,
 * which the user can collapse back to the rail. Both start at the top edge with the logo, so they line up
 * with the top bar instead of floating in the middle of the screen.
 */

package com.metrolist.android.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.mm_library_downloaded
import com.metrolist.shared.generated.resources.mm_tablet_nav_collapse
import com.metrolist.shared.generated.resources.mm_tablet_nav_expand
import com.metrolist.shared.generated.resources.mm_tablet_nav_my_albums
import com.metrolist.shared.generated.resources.mm_tablet_nav_yours
import com.metrolist.shared.generated.resources.mm_tablet_search_hint
import com.metrolist.shared.generated.resources.recognize_music
import com.metrolist.shared.generated.resources.tablet_nav_close_menu
import com.metrolist.shared.generated.resources.tablet_nav_open_menu
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.AdaptiveNavigationItem
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

val TabletRailWidth = 72.dp
val TabletSidebarWidth = 240.dp

/** Sidebar opened from the rail's menu button as an overlay (Medium width, or wherever the 240 sidebar is unavailable). */
val TabletOverlaySidebarWidth = 280.dp

/** Width the tablet navigation occupies, for insets such as the mini player offset. */
fun tabletNavigationWidth(sidebar: Boolean): Dp = if (sidebar) TabletSidebarWidth else TabletRailWidth

@Composable
fun TabletNavigation(
    navigator: Navigator,
    items: List<AdaptiveNavigationItem<Screen>>,
    selectedKey: Screen?,
    onSelect: (Screen) -> Unit,
    sidebar: Boolean,
    onToggleSidebar: (() -> Unit)?,
    containerColor: Color,
    modifier: Modifier = Modifier,
    itemModifier: @Composable (Screen) -> Modifier = { Modifier },
    // No width animation: the content beside it would relayout every frame (brief: animate offset/scale/alpha only).
    width: Dp = tabletNavigationWidth(sidebar),
    // True when the menu button opens (rail) or closes (sidebar) the overlay sidebar instead of the docked one.
    overlayMenu: Boolean = false,
    // Called after any destination in the column is chosen, so the overlay can close itself.
    onNavigated: () -> Unit = {},
) {
    val database: MusicDatabase = koinInject()
    val userAlbums by remember(database) { database.dao().userAlbums() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier =
            modifier
                .width(width)
                .fillMaxHeight()
                .background(containerColor)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        TabletNavigationBrand(sidebar = sidebar, onToggleSidebar = onToggleSidebar, overlayMenu = overlayMenu)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (sidebar) 12.dp else 0.dp),
            horizontalAlignment = if (sidebar) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (sidebar) 2.dp else 4.dp),
        ) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                if (sidebar) {
                    SidebarItem(
                        label = item.label,
                        icon = if (selected) item.selectedIcon else item.icon,
                        selected = selected,
                        onClick = {
                            onSelect(item.key)
                            onNavigated()
                        },
                        modifier = itemModifier(item.key),
                    )
                } else {
                    RailItem(
                        label = item.label,
                        icon = if (selected) item.selectedIcon else item.icon,
                        selected = selected,
                        onClick = {
                            onSelect(item.key)
                            onNavigated()
                        },
                        modifier = itemModifier(item.key),
                    )
                }
            }

            SectionDivider(sidebar)
            if (sidebar) Overline(stringResource(Res.string.mm_tablet_nav_yours))
            val shortcuts =
                listOf(
                    Triple(stringResource(Res.string.liked_songs), MetrolistIcons.Outlined.FavoriteBorder, Screen.AutoPlaylist("liked")),
                    Triple(
                        stringResource(Res.string.mm_library_downloaded),
                        MetrolistIcons.Outlined.Download,
                        Screen.AutoPlaylist("downloaded"),
                    ),
                    Triple(stringResource(Res.string.equalizer), MetrolistIcons.Outlined.Equalizer, Screen.Equalizer()),
                )
            shortcuts.forEach { (label, icon, route) ->
                if (sidebar) {
                    SidebarItem(label = label, icon = icon, selected = false, onClick = {
                        navigator.navigate(route)
                        onNavigated()
                    })
                } else {
                    RailIconButton(label = label, icon = icon, onClick = {
                        navigator.navigate(route)
                        onNavigated()
                    })
                }
            }

            if (userAlbums.isNotEmpty()) {
                SectionDivider(sidebar)
                if (sidebar) Overline(stringResource(Res.string.mm_tablet_nav_my_albums))
                userAlbums.forEach { album ->
                    UserAlbumEntry(
                        album = album,
                        sidebar = sidebar,
                        onClick = {
                            navigator.navigate(Screen.Playlist(album.id, thumbnailHint = album.thumbnailUrl))
                            onNavigated()
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // Settings is the gear in the top bar (tablet spec 5.2). The rail keeps the menu button at its foot:
        // it expands the docked sidebar where one fits (>= 1024 dp), otherwise it opens the overlay sidebar.
        if (!sidebar && onToggleSidebar != null) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onToggleSidebar) {
                    Icon(
                        MetrolistIcons.Filled.Menu,
                        contentDescription =
                            stringResource(if (overlayMenu) Res.string.tablet_nav_open_menu else Res.string.mm_tablet_nav_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Brand row: the same 64 dp height as the top bar, so logo and greeting sit on one line. */
@Composable
private fun TabletNavigationBrand(
    sidebar: Boolean,
    onToggleSidebar: (() -> Unit)?,
    overlayMenu: Boolean,
) {
    val logo: @Composable () -> Unit = {
        Image(
            painter = painterResource(Res.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
    if (sidebar) {
        Row(
            modifier = Modifier.fillMaxWidth().height(LocalTabTopBarHeight.current).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            logo()
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (onToggleSidebar != null) {
                IconButton(onClick = onToggleSidebar) {
                    Icon(
                        MetrolistIcons.AutoMirrored.Filled.MenuOpen,
                        contentDescription =
                            stringResource(if (overlayMenu) Res.string.tablet_nav_close_menu else Res.string.mm_tablet_nav_collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(LocalTabTopBarHeight.current),
            contentAlignment = Alignment.Center,
        ) { logo() }
    }
}

/** Rail destination: 56 x 32 indicator pill with the icon, and a 12 sp label under it. */
@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .width(TabletRailWidth)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Sidebar row: 48 dp pill, accent container when active. */
@Composable
private fun SidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primaryContainer else Color.Transparent)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RailIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UserAlbumEntry(
    album: PlaylistEntity,
    sidebar: Boolean,
    onClick: () -> Unit,
) {
    if (sidebar) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumThumb(album)
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = album.name },
            contentAlignment = Alignment.Center,
        ) {
            AlbumThumb(album)
        }
    }
}

@Composable
private fun AlbumThumb(album: PlaylistEntity) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (album.thumbnailUrl != null) {
            AsyncImage(url = album.thumbnailUrl, modifier = Modifier.size(40.dp))
        } else {
            Icon(
                MetrolistIcons.Outlined.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionDivider(sidebar: Boolean) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = if (sidebar) 4.dp else 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Overline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

/** Tablet top bar search field: a 40 dp pill that opens the Search tab, with recognize music at its end. */
@Composable
fun TabletTopBarSearch(
    onClick: () -> Unit,
    onRecognize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .widthIn(min = 220.dp, max = 360.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            MetrolistIcons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(Res.string.mm_tablet_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRecognize, modifier = Modifier.size(36.dp)) {
            Icon(
                MetrolistIcons.Outlined.AudioCapture,
                contentDescription = stringResource(Res.string.recognize_music),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
