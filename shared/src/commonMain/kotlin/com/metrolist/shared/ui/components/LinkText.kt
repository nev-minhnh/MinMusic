package com.metrolist.shared.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * [Text] for strings with [LinkAnnotation]s that is safe inside a LookaheadScope (the app-wide shared transition
 * layout).
 *
 * Compose's own link handling adds a child layout node per link and measures those children only once the new text
 * layout is known, inside its placement block. When the text width changes (window resize, pane docking) the
 * lookahead pass can skip a link child that the main pass then places, which crashes with "LookaheadDelegate has not
 * been measured yet". Here the links are plain styled spans: hover and clicks are hit-tested against the text layout,
 * so the text stays a single layout node.
 */
@Composable
fun LinkText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val links = remember(text) { text.getLinkAnnotations(0, text.length) }
    if (links.isEmpty()) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            textAlign = textAlign,
            softWrap = softWrap,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = onTextLayout ?: {},
        )
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var hovered by remember { mutableStateOf(-1) }
    val uriHandler = LocalUriHandler.current
    val currentLinks by rememberUpdatedState(links)
    val display =
        remember(text, links, hovered) {
            val spans = text.spanStyles.toMutableList()
            links.forEachIndexed { index, link ->
                val styles = link.item.styles
                styles?.style?.let { spans += AnnotatedString.Range(it, link.start, link.end) }
                if (index == hovered) {
                    styles?.hoveredStyle?.let { spans += AnnotatedString.Range(it, link.start, link.end) }
                }
            }
            AnnotatedString(text.text, spans, text.paragraphStyles)
        }
    Text(
        text = display,
        modifier =
            modifier
                .then(if (hovered >= 0) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var pressed = -1
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val index = layout?.let { linkIndexAt(it, change.position, currentLinks) } ?: -1
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    hovered = index
                                }

                                PointerEventType.Exit -> {
                                    hovered = -1
                                }

                                PointerEventType.Press -> {
                                    pressed = if (change.isConsumed) -1 else index
                                    if (pressed >= 0) change.consume()
                                }

                                PointerEventType.Release -> {
                                    if (pressed >= 0) {
                                        change.consume()
                                        if (index == pressed) currentLinks.getOrNull(index)?.item?.let { open(it, uriHandler::openUri) }
                                    }
                                    pressed = -1
                                }
                            }
                        }
                    }
                },
        color = color,
        style = style,
        textAlign = textAlign,
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { result ->
            layout = result
            onTextLayout?.invoke(result)
        },
    )
}

private fun linkIndexAt(
    layout: TextLayoutResult,
    position: Offset,
    links: List<AnnotatedString.Range<LinkAnnotation>>,
): Int {
    if (position.y < 0f || position.y > layout.size.height) return -1
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.x < layout.getLineLeft(line) || position.x > layout.getLineRight(line)) return -1
    val offset = layout.getOffsetForPosition(position)
    return links.indexOfFirst { offset >= it.start && offset < it.end }
}

private fun open(
    link: LinkAnnotation,
    openUri: (String) -> Unit,
) {
    val listener = link.linkInteractionListener
    when {
        listener != null -> listener.onClick(link)
        link is LinkAnnotation.Url -> openUri(link.url)
    }
}
