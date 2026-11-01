package dev.phonebot.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ChatRow(val turn: ChatTurn, val part: String, val step: TaskStep? = null) {
    val key get() = "${turn.id}/$part/${step?.id.orEmpty()}"
}

@Composable internal fun ChatMessages(turns: List<ChatTurn>, activeTask: String, modifier: Modifier = Modifier,
    list: LazyListState = rememberLazyListState(), focusedTask: String = "",
    contentPadding: PaddingValues = PaddingValues(horizontal = PhoneUi.space, vertical = PhoneUi.gap)) {
    var expanded by rememberSaveable { mutableStateOf(mapOf<String, Boolean>()) }
    var positionedCount by rememberSaveable { mutableIntStateOf(-1) }
    val rows = remember(turns, activeTask, expanded) { buildList {
        turns.forEach { turn ->
            val running = turn.id == activeTask
            val request = turn.steps.maxOfOrNull { it.request } ?: 0
            val steps = turn.steps.filterNot { running && turn.streamText.isNotBlank() && it.kind == "text" && it.request == request }
            add(ChatRow(turn, "prompt"))
            if (steps.isNotEmpty() || turn.image.isNotEmpty() || (running && turn.streamText.isBlank())) {
                add(ChatRow(turn, "trace"))
                if (expanded["${turn.id}/$running"] ?: running) {
                    if (running && turn.steps.isEmpty()) add(ChatRow(turn, "waiting"))
                    steps.forEach { add(ChatRow(turn, "step", it)) }
                    if (turn.image.isNotEmpty()) add(ChatRow(turn, "image"))
                }
            }
            if (turn.answer.isNotBlank() || (running && turn.streamText.isNotBlank())) add(ChatRow(turn, "reply"))
        }
    } }
    LaunchedEffect(focusedTask, turns.size) {
        if (focusedTask.isEmpty() && positionedCount == turns.size) return@LaunchedEffect
        val index = if (focusedTask.isEmpty()) rows.lastIndex else rows.indexOfFirst { it.turn.id == focusedTask && it.part == "prompt" }
        if (index >= 0) { list.scrollToItem(index); positionedCount = turns.size }
    }
    LaunchedEffect(turns.lastOrNull()) {
        if (focusedTask.isNotEmpty() || list.isScrollInProgress || rows.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        if (last.index >= rows.lastIndex - 1) {
            if (last.index < rows.lastIndex) list.animateScrollToItem(rows.lastIndex)
            else (last.offset + last.size - list.layoutInfo.viewportEndOffset).takeIf { it > 0 }?.let { list.scrollBy(it.toFloat()) }
        }
    }
    LazyColumn(state = list, modifier = modifier.testTag("chat_messages"), contentPadding = contentPadding) {
        items(rows, key = { it.key }, contentType = { it.part }) { row ->
            val turn = row.turn
            val running = turn.id == activeTask
            val colors = MaterialTheme.colorScheme
            when (row.part) {
                "prompt" -> BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = PhoneUi.gap), contentAlignment = Alignment.CenterEnd) {
                    SelectionContainer(Modifier.widthIn(max = maxWidth * PhoneUi.bubbleWidthFraction).clip(MaterialTheme.shapes.large)
                        .background(colors.surfaceContainerLow).padding(PhoneUi.space, PhoneUi.inset).testTag("user_message")) {
                        Text(turn.prompt, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                "trace" -> {
                    val key = "${turn.id}/$running"
                    val open = expanded[key] ?: running
                    Box(if (open) Modifier.testTag("execution_trace") else Modifier) {
                        TraceButton(when { running -> "正在执行"; turn.status == "error" -> "执行失败"; turn.status == "stopped" -> "已停止"
                            turn.status == "interrupted" -> "任务已中断"; else -> "执行过程" }, open, "execution_toggle") { expanded = expanded + (key to !open) }
                    }
                }
                "waiting" -> Text("正在等待模型", color = colors.onSurfaceVariant, style = PhoneUi.traceBody, modifier = Modifier.padding(start = 30.dp, top = 8.dp, bottom = 8.dp))
                "step" -> TraceEntry(checkNotNull(row.step), running)
                "image" -> {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, turn.image) {
                        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(turn.image, BitmapFactory.Options().apply { inSampleSize = 3 }) }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), "任务截图", Modifier.fillMaxWidth(.6f).heightIn(max = PhoneUi.attachmentHeight).clip(PhoneUi.traceImageShape)) }
                }
                "reply" -> Box(Modifier.fillMaxWidth().padding(vertical = PhoneUi.space).clip(MaterialTheme.shapes.large)
                    .background(colors.surfaceContainerLow).padding(PhoneUi.space).testTag("assistant_message")) {
                    MarkdownAnswer.Content(turn.answer.ifEmpty { turn.streamText }, running && turn.answer.isEmpty())
                }
            }
        }
    }
}

@Composable private fun TraceButton(text: String, expanded: Boolean, tag: String, tool: Boolean = false, click: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val color = if (pressed || hovered || focused) MaterialTheme.colorScheme.tertiary
        else if (tool) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = PhoneUi.touch).testTag(tag)
        .clickable(source, indication = null, role = Role.Button, onClick = click), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = if (tool) PhoneUi.traceBody else PhoneUi.traceHeading, color = color,
            fontWeight = if (tool) FontWeight.SemiBold else FontWeight.Medium, modifier = if (tool) Modifier.weight(1f) else Modifier)
        Icon(Icons.Outlined.KeyboardArrowDown, null, tint = color, modifier = Modifier.padding(start = 6.dp).size(20.dp).rotate(if (expanded) 0f else -90f))
    }
}

@Composable private fun TraceEntry(step: TaskStep, running: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().drawBehind {
        drawLine(colors.outlineVariant, Offset(8.dp.toPx(), 0f), Offset(8.dp.toPx(), size.height), 1.dp.toPx())
    }.padding(vertical = 10.dp)) {
        val dot = if (step.error) colors.error else if (step.kind == "tool" && step.done) colors.secondary else colors.onSurfaceVariant
        Box(Modifier.width(16.dp).padding(top = if (step.kind == "tool") 21.dp else 9.dp), contentAlignment = Alignment.TopCenter) {
            if (!step.done && running) CircularProgressIndicator(Modifier.size(10.dp), color = colors.onSurfaceVariant, strokeWidth = 1.dp)
            else Box(Modifier.size(7.dp).background(dot, CircleShape))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (step.kind) {
                "tool" -> {
                    var detail by rememberSaveable { mutableStateOf(false) }
                    TraceButton(step.name, detail, "tool-${step.id}", tool = true) { detail = !detail }
                    if (detail) SelectionContainer {
                        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(colors.background).border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)) {
                            Text("输入", color = colors.onSurfaceVariant, style = PhoneUi.hint, modifier = Modifier.padding(10.dp, 6.dp))
                            Text(step.args, color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = PhoneUi.traceCode,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp, 6.dp))
                            HorizontalDivider(color = colors.outlineVariant)
                            Text("输出", color = colors.onSurfaceVariant, style = PhoneUi.hint, modifier = Modifier.padding(10.dp, 6.dp))
                            Text(step.detail, color = if (step.error) colors.error else colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = PhoneUi.traceCode,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp, 6.dp))
                        }
                    }
                }
                "thinking" -> {
                    Text("思考", color = colors.onSurfaceVariant, style = PhoneUi.traceCode)
                    SelectionContainer { Text(step.detail, color = colors.onSurfaceVariant, style = PhoneUi.traceBody) }
                }
                "text" -> MarkdownAnswer.Content(step.detail, running && !step.done, colors.onSurfaceVariant)
                else -> SelectionContainer { Text(step.detail, color = if (step.error) colors.error else colors.onSurfaceVariant, style = PhoneUi.traceBody) }
            }
        }
    }
}
