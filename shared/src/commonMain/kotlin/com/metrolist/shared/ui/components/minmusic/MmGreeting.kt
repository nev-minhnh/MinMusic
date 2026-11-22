package com.metrolist.shared.ui.components.minmusic

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.mm_greeting_afternoon
import com.metrolist.shared.generated.resources.mm_greeting_evening
import com.metrolist.shared.generated.resources.mm_greeting_morning
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private fun greetingFor(hour: Int): StringResource =
    when (hour) {
        in 4..10 -> Res.string.mm_greeting_morning
        in 11..17 -> Res.string.mm_greeting_afternoon
        else -> Res.string.mm_greeting_evening
    }

/** Home top bar title: the 32 dp logo tile and a time-of-day greeting in the display style. */
@Composable
fun MmGreetingTitle(
    modifier: Modifier = Modifier,
    showLogo: Boolean = true,
    // The coloured brand tile from the platform (Android launcher icon); the monochrome glyph otherwise.
    logo: Painter? = null,
) {
    val greeting =
        remember {
            greetingFor(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour)
        }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tablets already show the logo in the navigation brand row on the same line.
        if (showLogo) {
            Image(
                painter = logo ?: painterResource(Res.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        // The top bar actions leave ~150 dp at 360 dp width: shrink the greeting to fit instead of ellipsizing it.
        val style = MaterialTheme.typography.headlineMedium
        Text(
            text = stringResource(greeting),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
        )
    }
}
