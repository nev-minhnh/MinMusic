package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.metrolist.desktop.input.SmoothWheelEvent
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseWheelEvent
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Debug-only frame trace for measuring scroll smoothness (`-Dminmusic.debug.frameTrace=<file>`).
 *
 * While enabled the window renders every vsync and each drawn frame logs its frame-clock time, draw time, window
 * size and the total distance scrolled so far (summed from nested scrolling at the root), and every wheel event is
 * logged with its arrival time. Repeated scroll positions while a scroll is in progress are frames the eye sees as
 * stalls. Without the property this is a plain pass-through and costs nothing.
 */
internal object DesktopFrameTrace {
    private val file = System.getProperty("minmusic.debug.frameTrace")?.takeIf(String::isNotBlank)?.let(::File)
    val enabled = file != null
    private val lines = ConcurrentLinkedQueue<String>()
    var scrolledX = 0.0
    var scrolledY = 0.0

    init {
        if (file != null) {
            file.parentFile?.mkdirs()
            file.writeText("")
            thread(name = "frame-trace", isDaemon = true) {
                while (true) {
                    Thread.sleep(500)
                    val chunk = StringBuilder()
                    while (true) chunk.append(lines.poll() ?: break).append('\n')
                    if (chunk.isNotEmpty()) file.appendText(chunk.toString())
                }
            }
        }
    }

    fun log(line: String) {
        if (enabled) lines.add(line)
    }
}

@Composable
internal fun DesktopFrameTraceHost(content: @Composable () -> Unit) {
    if (!DesktopFrameTrace.enabled) {
        content()
        return
    }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame.longValue = it }
    }
    DisposableEffect(Unit) {
        val listener =
            AWTEventListener { event: AWTEvent ->
                if (event is MouseWheelEvent) {
                    val kind = if (event is SmoothWheelEvent) "S" else "W"
                    DesktopFrameTrace.log("$kind ${System.nanoTime()} ${event.wheelRotation} ${event.preciseWheelRotation}")
                }
            }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
    val connection =
        remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    DesktopFrameTrace.scrolledX += consumed.x
                    DesktopFrameTrace.scrolledY += consumed.y
                    return Offset.Zero
                }
            }
        }
    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(connection)
            .drawWithContent {
                val frameNanos = frame.longValue
                drawContent()
                DesktopFrameTrace.log(
                    "F $frameNanos ${System.nanoTime()} ${size.width.toInt()} ${size.height.toInt()} " +
                        "${DesktopFrameTrace.scrolledX} ${DesktopFrameTrace.scrolledY}",
                )
            },
    ) {
        // Own layer, so redrawing the trace every frame does not re-record the whole UI.
        Box(Modifier.fillMaxSize().graphicsLayer()) { content() }
    }
}
