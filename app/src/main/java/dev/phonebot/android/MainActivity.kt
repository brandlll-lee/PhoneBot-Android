package dev.phonebot.android

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebot.android.engine.EnginePhase
import dev.phonebot.android.phone.PhonePermissions
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val model: PhoneBotModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        openTask(intent)
        setContent { PhoneBotTheme { PhoneBotApp(model) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTask(intent)
    }
    private fun openTask(intent: Intent) {
        val session = intent.getStringExtra("session") ?: return
        val task = intent.getStringExtra("task") ?: return
        model.openTask(session, task)
    }
}

private val Background = Color(0xFF0F0F0F)
private val Sheet = Color(0xFF1D1D1D)
private val Group = Color(0xFF282828)
private val Muted = Color(0xFF999999)
private val Accent = Color(0xFF88ABFF)

@Composable internal fun PhoneBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Background, background = Background, surface = Sheet,
        surfaceContainer = Group, onSurface = Color(0xFFF1F1F1), onSurfaceVariant = Muted, outlineVariant = Color(0xFF393939)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhoneBotApp(model: PhoneBotModel) {
    val engine by model.engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val snackbar = remember { SnackbarHostState() }
    var panel by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(model.focusedTask) {
        if (model.focusedTask.isNotEmpty()) { panel = ""; drawer.close() }
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshPermissions() }
        val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> scope.launch { model.refreshPermissions() } }
        Shizuku.addRequestPermissionResultListener(permissionResult)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); Shizuku.removeRequestPermissionResultListener(permissionResult) }
    }
    LaunchedEffect(model.notice) {
        if (model.notice.isNotEmpty()) { val message = model.notice; model.clearNotice(); snackbar.showSnackbar(message) }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = model.activeTask.isEmpty(), drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Background, modifier = Modifier.fillMaxWidth(.84f), drawerShape = RoundedCornerShape(0.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(query, { query = it }, "搜索对话内容", Modifier.weight(1f).padding(top = 16.dp, bottom = 8.dp))
                    ToolIcon(Icons.Outlined.Add, "新对话", { model.newSession(); scope.launch { drawer.close() } }, enabled = model.activeTask.isEmpty())
                }
                val filtered = model.sessions.filter { it.turns.isNotEmpty() && (it.title.contains(query, true) || it.turns.any { turn -> turn.prompt.contains(query, true) || turn.answer.contains(query, true) }) }
                LazyColumn(Modifier.weight(1f)) {
                    val groups = filtered.groupBy { dateGroup(it.updated) }
                    groups.forEach { (date, sessions) ->
                        item { Text(date, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp)) }
                        items(sessions, key = { it.id }) { session ->
                            var menu by remember { mutableStateOf(false) }
                            val selected = session.id == model.selectedSession
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Color(0xFF283248) else Color.Transparent)
                                .clickable(enabled = model.activeTask.isEmpty()) { model.selectSession(session.id); scope.launch { drawer.close() } }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.title, Modifier.weight(1f).padding(vertical = 15.dp), color = if (selected) Accent else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Box {
                                    ToolIcon(Icons.Outlined.MoreVert, "对话选项", { menu = true }, enabled = model.activeTask.isEmpty())
                                    DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("删除对话") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; model.deleteSession(session.id) }) }
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) item { Text(if (query.isEmpty()) "暂无对话" else "没有匹配的对话", color = Muted, modifier = Modifier.padding(12.dp, 24.dp)) }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().clickable { panel = "settings" }.padding(12.dp, 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
                    Text("PhoneBot", Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    Icon(Icons.Outlined.Settings, "设置", tint = Muted)
                }
            }
        }
    }) {
        Scaffold(containerColor = Background, snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.Outlined.Menu, "打开对话列表", { scope.launch { drawer.open() } }, filled = true)
                    Text(model.session?.title ?: "PhoneBot", Modifier.weight(1f).padding(horizontal = 14.dp), fontWeight = FontWeight.SemiBold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ToolIcon(Icons.Outlined.Add, "新对话", model::newSession, enabled = model.activeTask.isEmpty())
                    ToolIcon(Icons.Outlined.Settings, "设置", { panel = "settings" })
                }
                val turns = model.session?.turns.orEmpty()
                val list = rememberLazyListState()
                LaunchedEffect(model.focusedTask, model.selectedSession, turns.size) {
                    val index = turns.indexOfFirst { it.id == model.focusedTask }
                    if (index >= 0) list.scrollToItem(index)
                }
                LaunchedEffect(turns.size, turns.lastOrNull()?.answer?.length, turns.lastOrNull()?.steps?.size) {
                    if (turns.isNotEmpty() && model.focusedTask.isEmpty() && !list.isScrollInProgress) {
                        if (list.layoutInfo.visibleItemsInfo.none { it.index == turns.lastIndex }) list.animateScrollToItem(turns.lastIndex)
                        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
                        val overflow = (last?.let { it.offset + it.size } ?: 0) - list.layoutInfo.viewportEndOffset
                        if (overflow > 0) list.animateScrollBy(overflow.toFloat())
                    }
                }
                if (turns.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                            Text("PhoneBot", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    items(turns, key = { it.id }) { turn -> ChatTurnView(turn, model.activeTask == turn.id) }
                }
                if (model.permission.isNotEmpty()) {
                    val required = model.permissions.find { it.id == model.permission }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Group).padding(16.dp)) {
                        Text(if (model.permission == "accessibility") "任务已暂停：需要无障碍服务" else "任务已暂停：请亮屏并解锁", fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { panel = "permissions" }) { Text("查看权限") }
                            TextButton(onClick = model::resume, enabled = required?.granted == true) { Text("继续任务") }
                        }
                    }
                }
                Composer(model, engine.phase, { panel = "providers"; if (engine.canRun) model.catalog() })
            }
        }
    }
    if (panel.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { panel = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Sheet, dragHandle = null, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding()) {
                val providerId = panel.removePrefix("provider:")
                val title = when {
                    panel == "settings" -> "设置"
                    panel == "providers" -> "模型服务"
                    panel == "permissions" -> "权限"
                    else -> model.providers.find { it.id == providerId }?.name ?: providerId
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (panel != "settings") ToolIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回", { panel = if (panel.startsWith("provider:")) "providers" else "settings" }) else Spacer(Modifier.width(48.dp))
                    Text(title, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    ToolIcon(Icons.Outlined.Close, "关闭设置", { panel = "" }, filled = true)
                }
                when {
                    panel == "settings" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SettingsGroup {
                            SettingsRow(Icons.Outlined.AccountCircle, "模型服务", model.providers.find { it.id == model.provider }?.name.orEmpty()) { panel = "providers"; model.catalog() }
                            InsetDivider()
                            SettingsRow(Icons.Outlined.Lock, "权限", "${model.permissions.count { it.granted }}/${model.permissions.size}") { model.refreshPermissions(); panel = "permissions" }
                        }
                        SettingsGroup {
                            SettingsRow(Icons.Outlined.PlayArrow, "引擎", phaseName(engine.phase), arrow = false) {}
                            InsetDivider()
                            if (engine.canStart) SettingsRow(Icons.Outlined.PlayArrow, "启动引擎") { model.engine.start() }
                            else SettingsRow(Icons.Outlined.Close, "停止引擎", enabled = engine.canStop) { model.engine.stop() }
                            InsetDivider()
                            SettingsRow(Icons.Outlined.Refresh, "运行自检", enabled = engine.canRun) { model.engine.probe() }
                        }
                        if (engine.phase == EnginePhase.FAILED || engine.detail.startsWith("Error:")) Text(engine.detail.take(500), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        SettingsGroup { SettingsRow(Icons.Outlined.Info, "版本", "0.2.0", arrow = false) {} }
                    }
                    panel == "providers" -> ProviderList(model, engine.canRun) { id -> panel = "provider:$id"; model.catalog(id) }
                    panel == "permissions" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        model.permissions.forEach { permission ->
                            SettingsGroup {
                                SettingsRow(if (permission.id in setOf("notifications", "task_results")) Icons.Outlined.Notifications else Icons.Outlined.Lock, permission.name, if (permission.granted) "已开启" else "待授权") {
                                    if (permission.id == "notifications" && PhonePermissions.notificationPermission() != null && !permission.granted)
                                        (context as? ComponentActivity)?.requestPermissions(arrayOf(PhonePermissions.notificationPermission()!!), 1)
                                    else if (permission.id == "shizuku" && Shizuku.pingBinder() && !permission.granted) Shizuku.requestPermission(2)
                                    else context.startActivity(PhonePermissions.settings(context, permission.id))
                                }
                                Text(permission.purpose, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 58.dp, end = 16.dp, bottom = 18.dp))
                            }
                        }
                    }
                    else -> ProviderDetail(model, providerId, engine.canRun)
                }
            }
        }
    }
    val auth = model.authPrompt
    if (auth != null) {
        val prompt = auth.getJSONObject("params")
        var answer by remember(auth.getString("id")) { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = model::cancel, title = { Text("连接服务商") }, containerColor = Sheet,
            text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                model.authEvent?.let { AuthLinks(it) }
                Text(prompt.getString("message"))
                if (prompt.optString("type") == "select") prompt.getJSONArray("options").objects().forEach { option ->
                    TextButton(onClick = { model.answerAuth(option.getString("id")) }) { Text(option.getString("label")) }
                } else OutlinedTextField(answer, { answer = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (prompt.optString("type") == "secret" && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (prompt.optString("type") == "secret") { { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } } } else null)
            } },
            confirmButton = { if (prompt.optString("type") != "select") TextButton(onClick = { model.answerAuth(answer); answer = "" }, enabled = answer.isNotBlank() || prompt.optString("type") == "text") { Text("继续") } },
            dismissButton = { TextButton(onClick = model::cancel) { Text("取消") } })
    }
}

@Composable private fun Composer(model: PhoneBotModel, phase: EnginePhase, chooseModel: () -> Unit) {
    TaskComposer(model.model.ifEmpty { if (phase == EnginePhase.STARTING) "正在启动" else "选择模型" }, model.models.takeIf { model.modelProvider == model.provider }.orEmpty(),
        model.activeTask.isNotEmpty(), phase == EnginePhase.READY,
        { if (model.provider.isNotEmpty()) model.catalog(model.provider) },
        { model.choose(model.provider, it) }, chooseModel, model::submit, model::cancel)
}

@Composable internal fun TaskComposer(modelName: String, models: List<PiModel>, busy: Boolean, ready: Boolean,
    loadModels: () -> Unit, choose: (String) -> Unit, configure: () -> Unit, submit: (String) -> Boolean, cancel: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val send = {
        if (!busy && ready && input.isNotBlank() && submit(input.trim())) {
            input = ""
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true; loadModels() }, enabled = !busy && ready, shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp)) {
                Text(modelName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Icon(Icons.Outlined.KeyboardArrowDown, "选择模型", modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 360.dp)) {
                models.forEach { item -> DropdownMenuItem(text = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { if (item.id == modelName) Icon(Icons.Outlined.Check, null, tint = Accent) },
                    onClick = { choose(item.id); expanded = false }) }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("管理模型服务") }, onClick = { expanded = false; configure() })
            }
        }
        val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 25.sp)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF252525))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)).testTag("composer")) {
            val width = with(density) { (maxWidth - 88.dp).roundToPx().coerceAtLeast(1) }
            val multiline = input.isNotEmpty() && measurer.measure(input, style, constraints = Constraints(maxWidth = width)).lineCount > 1
            BasicTextField(input, { if (it.length <= 12000) input = it }, maxLines = 5,
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = if (multiline) 18.dp else 68.dp,
                    top = 16.dp, bottom = if (multiline) 64.dp else 16.dp).testTag("task_input"),
                textStyle = style, cursorBrush = SolidColor(Accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> Box { if (input.isEmpty()) Text("给我布置一个任务", color = Muted, style = style); inner() } })
            FilledIconButton(onClick = if (busy) cancel else send, enabled = busy || (input.isNotBlank() && ready),
                shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(48.dp)
                    .semantics { contentDescription = if (busy) "停止任务" else "发送任务" },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFF2F2F2), contentColor = Background)) {
                if (busy) Box(Modifier.size(14.dp).background(Background, RoundedCornerShape(2.dp)))
                else Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.rotate(-90f))
            }
        }
        Text("内容由 AI 生成，请注意核实", color = Muted.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable internal fun ChatTurnView(turn: ChatTurn, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SelectionContainer(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(Color(0xFF303030)).padding(16.dp, 13.dp)) {
                Text(turn.prompt, fontSize = 17.sp, lineHeight = 26.sp)
            }
        }
        if (turn.steps.isNotEmpty() || turn.image.isNotEmpty() || running) {
            var expanded by rememberSaveable(turn.id, running) { mutableStateOf(running) }
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("execution_toggle").clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Text(when { running -> "正在执行"; turn.status == "error" -> "执行失败"; turn.status == "stopped" -> "已停止";
                        turn.status == "interrupted" -> "任务已中断"; else -> "执行过程" }, fontSize = 15.sp, color = Muted, fontWeight = FontWeight.Medium)
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.padding(start = 6.dp).size(20.dp).rotate(if (expanded) 0f else -90f))
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().testTag("execution_trace").drawBehind {
                        drawLine(Color(0xFF333333), Offset(8.dp.toPx(), 12.dp.toPx()), Offset(8.dp.toPx(), size.height - 12.dp.toPx()), 1.dp.toPx())
                    }) {
                        if (running && turn.steps.isEmpty()) Text("正在等待模型", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 30.dp, top = 8.dp, bottom = 8.dp))
                        turn.steps.forEach { step -> key(step.id) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                val dot = if (step.error) MaterialTheme.colorScheme.error else if (step.kind == "tool" && step.done) Color(0xFF77C69B) else Muted
                                Box(Modifier.width(16.dp).padding(top = if (step.kind == "tool") 21.dp else 9.dp), contentAlignment = Alignment.TopCenter) {
                                    if (!step.done && running) CircularProgressIndicator(Modifier.size(10.dp), color = Muted, strokeWidth = 1.dp)
                                    else Box(Modifier.size(7.dp).background(dot, CircleShape))
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (step.kind) {
                                        "tool" -> {
                                            var detail by rememberSaveable(step.id) { mutableStateOf(false) }
                                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tool-${step.id}").clickable { detail = !detail }, verticalAlignment = Alignment.CenterVertically) {
                                                Text(step.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.size(18.dp).rotate(if (detail) 0f else -90f))
                                            }
                                            if (detail) SelectionContainer {
                                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Background).border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))) {
                                                    Text("输入", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(10.dp, 6.dp))
                                                    Text(step.args, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                                                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp, 6.dp))
                                                    HorizontalDivider(color = Color(0xFF333333))
                                                    Text("输出", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(10.dp, 6.dp))
                                                    Text(step.detail, color = if (step.error) MaterialTheme.colorScheme.error else Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                                                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp, 6.dp))
                                                }
                                            }
                                        }
                                        "thinking" -> {
                                            Text("思考", color = Muted, fontSize = 12.sp)
                                            SelectionContainer { Text(step.detail, color = Muted, fontSize = 14.sp, lineHeight = 22.sp) }
                                        }
                                        "text" -> MarkdownAnswer.Content(step.detail, running && !step.done, Muted)
                                        else -> SelectionContainer { Text(step.detail, color = if (step.error) MaterialTheme.colorScheme.error else Muted, fontSize = 14.sp, lineHeight = 22.sp) }
                                    }
                                }
                            }
                        } }
                    }
                    if (turn.image.isNotEmpty()) {
                        val bitmap by produceState<android.graphics.Bitmap?>(null, turn.image) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BitmapFactory.decodeFile(turn.image, BitmapFactory.Options().apply { inSampleSize = 3 }) }
                        }
                        bitmap?.let { Image(it.asImageBitmap(), "任务截图", Modifier.fillMaxWidth(.6f).heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp))) }
                    }
                }
            }
        }
        if (turn.answer.isNotBlank()) MarkdownAnswer.Content(turn.answer)
    }
}

@Composable private fun ProviderList(model: PhoneBotModel, ready: Boolean, select: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        SearchField(search, { search = it }, "搜索服务商", Modifier.padding(vertical = 12.dp))
        if (model.providers.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn {
            items(model.providers.filter { it.name.contains(search, true) || it.id.contains(search, true) }, key = { it.id }) { provider ->
                SettingsRow(Icons.Outlined.AccountCircle, provider.name, if (provider.configured) "已配置" else "", enabled = ready) { select(provider.id) }
                InsetDivider()
            }
        }
    }
}

@Composable private fun ProviderDetail(model: PhoneBotModel, id: String, ready: Boolean) {
    val provider = model.providers.find { it.id == id } ?: return
    var search by rememberSaveable(id) { mutableStateOf("") }
    var searchStarted by remember(id) { mutableStateOf(false) }
    LaunchedEffect(search) {
        if (searchStarted) { kotlinx.coroutines.delay(300); model.catalog(id, query = search) }
        else searchStarted = true
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsGroup {
                provider.auth.forEachIndexed { index, auth ->
                    SettingsRow(Icons.Outlined.Lock, if (provider.configured) "修改认证 · ${auth.second}" else auth.second, enabled = ready) { model.login(id, auth.first) }
                    if (index < provider.auth.lastIndex) InsetDivider()
                }
                if (provider.ambient) Text("此服务商使用环境凭据。Android 尚未验证此认证方式。", color = Muted, modifier = Modifier.padding(18.dp))
                if (provider.configured) { InsetDivider(); SettingsRow(Icons.Outlined.Delete, "删除凭据", enabled = ready) { model.logout(id) } }
            }
        }
        val auth = model.authEvent
        if (auth != null) item {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val message = auth.optString("message").ifEmpty { auth.optString("instructions") }
                if (message.isNotEmpty()) Text(message, fontSize = 14.sp)
                AuthLinks(auth)
                TextButton(onClick = model::cancel) { Text("取消认证") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("模型", Modifier.weight(1f), color = Muted, fontSize = 14.sp)
                ToolIcon(Icons.Outlined.Refresh, "刷新 Pi 模型目录", { model.refresh(id) }, enabled = ready)
            }
            SearchField(search, { search = it }, "搜索模型")
        }
        items(model.models.filter { it.name.contains(search, true) || it.id.contains(search, true) }, key = { it.id }) { item ->
            val selected = model.provider == id && model.model == item.id
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0xFF283248) else Color.Transparent)
                .clickable(enabled = ready) { model.choose(id, item.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, fontSize = 16.sp, color = if (selected) Accent else MaterialTheme.colorScheme.onSurface)
                    Text((if (item.image) "支持图片 · " else "") + (if (model.verified(id, item.id)) "Android 连接已验证" else "Android 待验证"), fontSize = 12.sp, color = Muted)
                }
                if (selected) Icon(Icons.Outlined.Check, "默认模型", tint = Accent)
            }
        }
        if (model.models.size < model.modelTotal) item { TextButton(onClick = { model.catalog(id, model.models.size, search) }, enabled = ready) { Text("加载更多模型") } }
        if (model.models.isEmpty()) item { Text(if (ready) "目录为空。连接后刷新模型。" else "正在读取目录", color = Muted, modifier = Modifier.padding(14.dp)) }
        if (model.provider == id && model.model.isNotEmpty()) item {
            FilledTonalButton(onClick = model::verify, enabled = ready && (provider.configured || provider.ambient), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("验证连接") }
        }
    }
}

@Composable private fun AuthLinks(event: org.json.JSONObject) {
    val context = LocalContext.current
    if (event.has("userCode")) SelectionContainer { Text(event.getString("userCode"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
    val url = event.optString("url").ifEmpty { event.optString("verificationUri") }
    if (url.isNotEmpty()) TextButton(onClick = {
        val uri = url.toUri()
        if (uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }) { Text("在浏览器中认证") }
    event.optJSONArray("links")?.objects()?.forEach { link ->
        TextButton(onClick = {
            val uri = link.getString("url").toUri()
            if (uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }) { Text(link.optString("label", "查看认证说明")) }
    }
}

@Composable private fun SearchField(value: String, change: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(value, change, modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(placeholder, fontSize = 16.sp) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) }, shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = Group, unfocusedContainerColor = Group, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}
@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Group), content = content)
}
@Composable private fun InsetDivider() { HorizontalDivider(Modifier.padding(start = 58.dp), color = Color(0xFF393939), thickness = .5.dp) }
@Composable private fun SettingsRow(icon: ImageVector, title: String, value: String = "", enabled: Boolean = true, arrow: Boolean = true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(enabled = enabled && arrow, onClick = click).padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else Muted, modifier = Modifier.size(24.dp))
        Text(title, Modifier.weight(1f).padding(start = 16.dp, end = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted)
        if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = Muted, maxLines = 2, modifier = Modifier.widthIn(max = 110.dp), overflow = TextOverflow.Ellipsis)
        if (arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}
@Composable private fun ToolIcon(icon: ImageVector, label: String, click: () -> Unit, enabled: Boolean = true, filled: Boolean = false) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).then(if (filled) Modifier.background(Group, CircleShape) else Modifier)) {
        Icon(icon, label, modifier = Modifier.size(24.dp))
    }
}
private fun phaseName(phase: EnginePhase) = when (phase) {
    EnginePhase.STOPPED -> "已停止"; EnginePhase.STARTING -> "正在启动"; EnginePhase.READY -> "就绪"
    EnginePhase.RUNNING -> "运行中"; EnginePhase.STOPPING -> "正在停止"; EnginePhase.FAILED -> "启动失败"
}
private fun dateGroup(time: Long): String {
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    return when { date == LocalDate.now() -> "今天"; date == LocalDate.now().minusDays(1) -> "昨天"; date.isAfter(LocalDate.now().minusDays(7)) -> "7 天内"; else -> "更早" }
}
