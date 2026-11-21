package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.quality_badge_value
import com.metrolist.shared.generated.resources.source_badge_nct
import com.metrolist.shared.generated.resources.source_badge_sc
import com.metrolist.shared.generated.resources.source_badge_yt
import com.metrolist.shared.generated.resources.source_badge_zing
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceOf
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.audioCodecs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.math.abs

/** What a stream or file actually delivers: a codec name and its nominal bitrate tier, or a lossless codec. */
@Immutable
data class AudioQualityLabel(
    val codec: String,
    val kbps: Int?,
    val lossless: Boolean = false,
)

// Codec identifiers are technical names, not translatable text.
private const val CODEC_OPUS = "Opus"
private const val CODEC_AAC = "AAC"
private const val CODEC_MP3 = "MP3"
private const val CODEC_FLAC = "FLAC"

// Nominal tiers of known YouTube audio itags; their measured bitrates drift around these values.
private val youTubeItagKbps = mapOf(249 to 50, 250 to 70, 251 to 160, 774 to 256, 139 to 48, 140 to 128, 141 to 256)
private val bitrateTiers = listOf(48, 64, 96, 128, 160, 192, 256, 320)

fun audioQualityLabel(
    codecs: String,
    mimeType: String,
    bitrate: Int,
    itag: Int = -1,
): AudioQualityLabel? {
    val codec = codecs.substringBefore('.').trim().lowercase()
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val name =
        when {
            codec == "flac" || mime == "audio/flac" -> return AudioQualityLabel(CODEC_FLAC, kbps = null, lossless = true)
            codec == "opus" -> CODEC_OPUS
            codec == "mp4a" || codec == "aac" -> CODEC_AAC
            codec == "mp3" || mime == "audio/mpeg" || mime == "audio/mp3" -> CODEC_MP3
            else -> return null
        }
    val kbps = youTubeItagKbps[itag] ?: (bitrate / 1000).takeIf { it > 0 }?.let { measured -> bitrateTiers.minBy { abs(it - measured) } }
    return AudioQualityLabel(name, kbps)
}

fun FormatEntity.qualityLabel(): AudioQualityLabel? = audioQualityLabel(codecs, mimeType, bitrate, itag)

/** The quality of a resolved stream, known as soon as it resolves (the stored format only exists once cached). */
fun PlaybackResource.qualityLabel(): AudioQualityLabel? {
    // YouTube streams may carry the codec only inside the MIME type (`audio/webm; codecs="opus"`), or only the
    // itag (SABR); fall back to those before giving up.
    val codecs = audioCodecs() ?: return null
    return audioQualityLabel(codecs, mimeType.orEmpty(), bitrate ?: 0, itag ?: -1)
}

private val BadgeShape = RoundedCornerShape(4.dp)
private val BadgeHeight = 18.dp
private val BadgePadding = 5.dp

/** Identification colours from design-system §2.3; only ever drawn as a 6 dp dot. */
private fun sourceColor(source: SourceId): Color =
    when (source) {
        SourceId.YT -> Color(0xFFE8453C)
        SourceId.SC -> Color(0xFFF26B1D)
        SourceId.ZING -> Color(0xFF9B51E0)
        SourceId.NCT -> Color(0xFF2F80ED)
        SourceId.LOCAL -> Color.Transparent
    }

private fun sourceBadgeLabel(source: SourceId): StringResource? =
    when (source) {
        SourceId.YT -> Res.string.source_badge_yt
        SourceId.SC -> Res.string.source_badge_sc
        SourceId.ZING -> Res.string.source_badge_zing
        SourceId.NCT -> Res.string.source_badge_nct
        SourceId.LOCAL -> null
    }

@Composable
private fun BadgeText(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

/** Where a track comes from: a source-colour dot and a short code (YT, SC, Zing, NCT). Local files have none. */
@Composable
fun SourceBadge(
    source: SourceId,
    modifier: Modifier = Modifier,
) {
    val label = sourceBadgeLabel(source) ?: return
    Row(
        modifier =
            modifier
                .height(BadgeHeight)
                .clip(BadgeShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = BadgePadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(sourceColor(source), CircleShape))
        BadgeText(stringResource(label), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the user actually gets. Lossless is the only badge drawn with the brand gradient. */
@Composable
fun QualityBadge(
    quality: AudioQualityLabel,
    modifier: Modifier = Modifier,
) {
    val text = quality.kbps?.let { stringResource(Res.string.quality_badge_value, quality.codec, it) } ?: quality.codec
    if (quality.lossless) {
        val brand = LocalBrand.current
        Box(
            modifier =
                modifier
                    .height(BadgeHeight)
                    .clip(BadgeShape)
                    .background(brand.gradient)
                    .padding(horizontal = BadgePadding),
            contentAlignment = Alignment.Center,
        ) {
            BadgeText(text, brand.onGradient)
        }
    } else {
        Box(
            modifier =
                modifier
                    .height(BadgeHeight)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, BadgeShape)
                    .padding(horizontal = BadgePadding),
            contentAlignment = Alignment.Center,
        ) {
            BadgeText(text, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A small text tag such as "Experimental", in the accent container colours. */
@Composable
fun TagBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier =
            modifier
                .height(BadgeHeight)
                .clip(BadgeShape)
                .background(containerColor)
                .padding(horizontal = BadgePadding),
        contentAlignment = Alignment.Center,
    ) {
        BadgeText(text, contentColor)
    }
}

/** The quality of the stream or file last stored for [songId], or null while it is unknown. */
@Composable
fun rememberSongQuality(songId: String?): AudioQualityLabel? {
    val formatFlow: Flow<FormatEntity?> =
        remember(songId) {
            songId?.let { id -> KoinPlatform.getKoinOrNull()?.getOrNull<MusicDatabase>()?.dao()?.format(id) } ?: flowOf(null)
        }
    val format by formatFlow.collectAsState(initial = null)
    return remember(format) { format?.qualityLabel() }
}

/**
 * Source and quality badges for a song in a mixed-source context, with the 6 dp gap from the design system.
 * Pass the player's resolved [playbackResource] so the quality shows as soon as the stream resolves; it wins
 * over the stored format because it is what is actually playing.
 */
@Composable
fun SourceQualityBadges(
    songId: String?,
    modifier: Modifier = Modifier,
    showSource: Boolean = true,
    playbackResource: PlaybackResource? = null,
) {
    if (songId == null) return
    val storedQuality = rememberSongQuality(songId)
    val resolvedQuality =
        remember(playbackResource, songId) { playbackResource?.takeIf { it.videoId == songId }?.qualityLabel() }
    val quality = resolvedQuality ?: storedQuality
    val source = sourceOf(songId)
    val showSourceBadge = showSource && source != SourceId.LOCAL
    if (!showSourceBadge && quality == null) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSourceBadge) SourceBadge(source)
        quality?.let { QualityBadge(it) }
    }
}
