package dev.phonebot.android

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.*
import java.io.File
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test

class TraceFrameTest {
    @Test fun foldingFrameTiming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val turn = ChatTurn("timing", "检查执行过程", status = "done",
            answer = (1..30).joinToString("\n\n") { "**结果 $it**：保留当前页面内容与中文输出。" },
            steps = (1..200).map { TaskStep("timing-$it", "读取页面 $it", detail = "页面内容 $it", done = true) })
        val list = LazyListState()
        val frames = Collections.synchronizedList(mutableListOf<List<Long>>())
        val thread = HandlerThread("PhoneBotFrames").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            frames.add(listOf(FrameMetrics.TOTAL_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
                FrameMetrics.UNKNOWN_DELAY_DURATION).map { frame.getMetric(it) })
        }
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node?.text?.toString() == "执行过程") return node
            return (0 until (node?.childCount ?: 0)).firstNotNullOfOrNull { find(node?.getChild(it)) }
        }
        fun bounds(): Rect {
            val deadline = SystemClock.uptimeMillis() + 5000
            do {
                find(automation.rootInActiveWindow)?.let { return Rect().apply { it.getBoundsInScreen(this) } }
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Execution toggle is not visible")
        }
        fun tap() {
            val rect = bounds()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(if (action == MotionEvent.ACTION_DOWN) 30 else 180)
            }
        }
        try { ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 60f }
                activity.setContent { PhoneBotTheme {
                    val haze = rememberHazeState()
                    Box(Modifier.fillMaxSize()) {
                        ChatMessages(listOf(turn), "", Modifier.fillMaxSize().hazeSource(haze), list,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 80.dp))
                        Box(Modifier.fillMaxWidth().height(64.dp).edgeBlur(haze, true))
                    }
                } }
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            scenario.onActivity { list.requestScrollToItem(list.layoutInfo.totalItemsCount - 2, -list.layoutInfo.viewportEndOffset / 2) }
            repeat(3) { tap(); tap() }
            val initial = bounds()
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            repeat(24) { tap(); assertEquals(initial.top, bounds().top) }
            scenario.onActivity { activity ->
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                val samples = synchronized(frames) { frames.toList() }
                assertTrue(samples.isNotEmpty())
                val summary = (0..3).map { column -> samples.map { it[column] / 1_000_000.0 }.sorted().let {
                    "p50=${it[it.size / 2]} p95=${it[((it.size - 1) * .95).toInt()]} max=${it.last()}"
                } }
                val report = "frames=${samples.size} total/layout/draw/delay(ms)=$summary"
                File(activity.cacheDir, "trace-frame-timing.txt").writeText(report)
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("frameMetrics", report) })
            }
            assertEquals(initial.top, bounds().top)
            assertFalse(list.layoutInfo.visibleItemsInfo.any { it.key.toString().contains("/step/") })
        } } finally { thread.quitSafely() }
    }
}
