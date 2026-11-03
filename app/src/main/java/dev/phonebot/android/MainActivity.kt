package dev.phonebot.android

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.blur.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebot.android.engine.EnginePhase
import dev.phonebot.android.phone.PhonePermissions
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val model: PhoneBotModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTask(intent)
        setContent { PhoneBotTheme(model.appearance) {
            val dark = MaterialTheme.colorScheme.background == Color.Black
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            } }
            PhoneBotApp(model)
        } }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhoneBotApp(model: PhoneBotModel) {
    val engine by model.engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val snackbar = remember { SnackbarHostState() }
    var helperReturn by remember { mutableStateOf("settings") }
    var panel by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val drafts = rememberSaveable(saver = mapSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>(
        save = { it.toMap() }, restore = { values -> values.map { it.key to it.value as String }.toMutableStateMap() },
    )) { mutableStateMapOf<String, String>() }
    LaunchedEffect(model.focusedTask) {
        if (model.focusedTask.isNotEmpty()) { panel = ""; drawer.close() }
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(panel, engine.canRun) {
        if (panel == "settings" && engine.canRun) { model.catalog(); model.helperCommand("status") }
    }
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
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = true, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth(.84f), drawerShape = RectangleShape) {
            Column(Modifier.fillMaxSize().padding(horizontal = PhoneUi.inset)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(query, { query = it }, "搜索 Bot", Modifier.weight(1f).padding(top = PhoneUi.space, bottom = PhoneUi.small))
                    ToolIcon(R.drawable.ic_ui_plus, "创建 Bot", { model.newSession(); panel = "session"; scope.launch { drawer.close() } })
                }
                val filtered = model.sessions.filter { it.title.contains(query, true) || it.turns.any { turn -> turn.prompt.contains(query, true) || turn.answer.contains(query, true) } }.sortedByDescending { it.updated }
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { bot ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .background(if (bot.id == model.selectedSession) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                                .combinedClickable(onClick = { model.selectSession(bot.id); scope.launch { drawer.close() } }, onLongClick = { menu = true })
                                .padding(vertical = PhoneUi.inset), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(PhoneUi.inset)) {
                                BotAvatar(bot.avatarColor, Modifier.size(PhoneUi.touch), working = bot.turns.any { it.status == "running" })
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhoneUi.tiny)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(bot.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm").format(Instant.ofEpochMilli(bot.updated).atZone(ZoneId.systemDefault())),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(bot.turns.lastOrNull()?.let { it.answer.ifBlank { it.prompt } }?.replace(Regex("[\\n\\r*#]"), " ").orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Bot 设置") }, onClick = { menu = false; model.selectSession(bot.id); panel = "session" })
                                DropdownMenuItem(text = { Text("删除 Bot") }, enabled = bot.turns.none { it.status == "running" }, onClick = { menu = false; model.deleteSession(bot.id) })
                            }
                        }
                    }
                    if (filtered.isEmpty()) item { Text("没有匹配的 Bot", Modifier.padding(PhoneUi.space), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().clickable { panel = "settings" }.padding(PhoneUi.inset, PhoneUi.space), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_ui_user_round), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(PhoneUi.send))
                    Text("PhoneBot", Modifier.weight(1f).padding(start = PhoneUi.inset), fontWeight = FontWeight.Medium)
                    Icon(painterResource(R.drawable.ic_ui_settings_2), "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            val actionColor = remember(model.session?.avatarColor) { botActionColor(model.session?.avatarColor ?: 0L) }
            CompositionLocalProvider(LocalBotActionColor provides actionColor) {
            ChatLayout(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), header = {
                    ChatHeader(model.activeTask.isEmpty(), { scope.launch { drawer.open() } }, { panel = "session" }, model::newSession, { panel = "settings" }, model.session)
                }, composer = { Column {
                if (model.permission.isNotEmpty()) {
                    val required = model.permissions.find { it.id == model.permission }
                    Column(Modifier.fillMaxWidth().padding(horizontal = PhoneUi.space).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).padding(PhoneUi.space)) {
                        Text(when (model.permission) {
                            "accessibility" -> "任务已暂停：需要无障碍服务"
                            "unlock" -> "任务已暂停：请亮屏并解锁"
                            "builtin" -> "任务已暂停：内置连接不可用"
                            "shizuku" -> "任务已暂停：请启动并授权 Shizuku"
                            "screen" -> "任务已暂停：目标屏幕不可用"
                            else -> "任务已暂停：请检查连接和工作屏幕"
                        }, fontWeight = FontWeight.Medium)
                        if (model.permissionMessage.isNotBlank()) Text(model.permissionMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = PhoneUi.small))
                        Row(horizontalArrangement = Arrangement.spacedBy(PhoneUi.small)) {
                            TextButton(onClick = {
                                helperReturn = "settings"; model.refreshPermissions(); panel = "helpers"
                            }) { Text(if (model.permission in setOf("builtin", "shizuku", "screen")) "手机控制" else "查看权限") }
                            TextButton(onClick = model::resume, enabled = required?.granted != false) { Text("继续任务") }
                        }
                    }
                }
                TaskComposer(drafts[model.selectedSession].orEmpty(), { drafts[model.selectedSession] = it },
                    model.activeTask.isNotEmpty(), engine.canRun, model::submit, model::cancel)
                } }) { messagePadding ->
                key(model.selectedSession) {
                    val botId = model.selectedSession
                    val list = remember { androidx.compose.foundation.lazy.LazyListState() }
                    val turns = model.session?.turns.orEmpty()
                    if (turns.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(model.session?.title ?: "PhoneBot", style = MaterialTheme.typography.headlineLarge)
                    } else ChatMessages(turns, model.activeTask, Modifier.fillMaxSize(), list, model.focusedTask, messagePadding,
                        model.markdown, botId, model.submissions[botId].orEmpty(), { model.submissions.remove(botId) })
                }
            }
            }
        }
    }
    if (panel.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { panel = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = false, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = null, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).testTag("settings_sheet")) {
                val providerId = panel.removePrefix("provider:")
                val title = when {
                    panel == "settings" -> "设置"
                    panel == "providers" -> "模型账号"
                    panel == "bot-models" -> "选择模型服务"
                    panel.startsWith("bot-provider:") -> model.providers.find { it.id == panel.removePrefix("bot-provider:") }?.name.orEmpty()
                    panel == "notifications" -> "通知"
                    panel == "diagnostics" -> "排查问题"
                    panel == "about" -> "关于 PhoneBot"
                    panel == "session" -> "Bot 设置"
                    panel == "helpers" -> "手机控制"
                    else -> model.providers.find { it.id == providerId }?.name ?: providerId
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = PhoneUi.settingsInset, vertical = PhoneUi.inset), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(R.drawable.ic_ui_arrow_left, "返回", {
                        panel = when {
                            panel == "settings" -> ""
                            panel == "session" -> ""
                            panel.startsWith("bot-provider:") -> "bot-models"
                            panel == "bot-models" -> "session"
                            panel.startsWith("provider:") -> "providers"
                            panel == "helpers" -> helperReturn
                            else -> "settings"
                        }
                    })
                    Text(title, Modifier.weight(1f).padding(start = PhoneUi.small), style = PhoneUi.settingsTitle, maxLines = 2)
                    if (panel != "settings") ToolIcon(R.drawable.ic_ui_x, "关闭设置", { panel = "" })
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("settings_body")) { when {
                    panel == "settings" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = PhoneUi.settingsInset, vertical = PhoneUi.space), verticalArrangement = Arrangement.spacedBy(PhoneUi.gap)) {
                        SettingsGroup {
                            SettingsRow(R.drawable.ic_ui_key_round, "模型账号", if (model.providers.isEmpty()) "" else "已连接 ${model.providers.count { it.configured }} 项") { panel = "providers"; model.catalog() }
                            InsetDivider()
                            SettingsRow(R.drawable.ic_ui_cable, "手机控制", model.helperStates[model.session?.helper]?.let { helperStateName(it.optString("state")) }.orEmpty()) {
                                helperReturn = "settings"; model.refreshPermissions(); panel = "helpers"
                            }
                        }
                        SettingsGroup {
                            SettingsChoice(R.drawable.ic_ui_sun_moon, "外观", model.appearance.name, Appearance.entries.map { it.name to it.label }) { model.chooseAppearance(Appearance.from(it)) }
                            InsetDivider()
                            SettingsRow(R.drawable.ic_ui_bell, "通知", if (model.permissions.firstOrNull { it.id == "notifications" }?.granted == true) "已开启" else "未开启") { model.refreshPermissions(); panel = "notifications" }
                        }
                        SettingsGroup {
                            SettingsRow(R.drawable.ic_ui_info, "关于 PhoneBot") { panel = "about" }
                            InsetDivider()
                            SettingsRow(R.drawable.ic_ui_activity, "排查问题") { panel = "diagnostics" }
                        }
                    }
                    panel == "diagnostics" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(PhoneUi.settingsInset), verticalArrangement = Arrangement.spacedBy(PhoneUi.gap)) {
                        SettingsGroup {
                            SettingsRow(R.drawable.ic_ui_activity, "运行状态", phaseName(engine.phase), arrow = false)
                            InsetDivider()
                            if (engine.canStart) SettingsRow(R.drawable.ic_ui_play, "启动引擎") { model.engine.start() }
                            else SettingsRow(R.drawable.ic_ui_square, "停止引擎", enabled = engine.canStop) { model.engine.stop() }
                            InsetDivider()
                            SettingsRow(R.drawable.ic_ui_refresh_cw, "运行自检", enabled = engine.canRun) { model.engine.probe() }
                        }
                        if (engine.phase == EnginePhase.FAILED || engine.detail.startsWith("Error:")) Text(engine.detail.take(500), color = MaterialTheme.colorScheme.error, style = PhoneUi.settingsNote)
                    }
                    panel == "about" -> Column(Modifier.padding(PhoneUi.settingsInset)) { SettingsGroup { SettingsRow(R.drawable.ic_ui_info, "版本", "0.2.0", arrow = false) } }
                    panel == "notifications" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(PhoneUi.settingsInset)) { PermissionRows(model, setOf("notifications", "task_results")) }
                    panel == "bot-models" -> ProviderList(model, engine.canRun && model.activeTask.isEmpty()) { id -> panel = "bot-provider:$id"; model.catalog(id) }
                    panel.startsWith("bot-provider:") -> ProviderDetail(model, panel.removePrefix("bot-provider:"), engine.canRun && model.activeTask.isEmpty(), true)
                    panel == "providers" -> ProviderList(model, engine.canRun) { id -> panel = "provider:$id"; model.catalog(id) }
                    panel == "session" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = PhoneUi.settingsInset, vertical = PhoneUi.space), verticalArrangement = Arrangement.spacedBy(PhoneUi.gap)) {
                        val current = model.session
                        val enabled = model.activeTask.isEmpty()
                        BotEditor(model, enabled)
                        SettingsGroup {
                            SettingsRow(R.drawable.ic_ui_layers, "模型", current?.model?.ifEmpty { "选择模型" }.orEmpty(), enabled = enabled) { panel = "bot-models"; model.catalog() }
                        }
                        SettingsGroup {
                            SettingsChoice(R.drawable.ic_ui_cable, "控制方式", current?.helper.orEmpty(), HelperOptions, enabled) { model.choosePhone(helper = it) }
                            InsetDivider()
                            SettingsChoice(R.drawable.ic_ui_smartphone, "工作屏幕", current?.screen.orEmpty(),
                                listOf("main" to "手机屏幕", "virtual" to if (Build.VERSION.SDK_INT >= 34) "独立屏幕" else "独立屏幕 · 需要 Android 14"),
                                enabled, if (Build.VERSION.SDK_INT < 34) setOf("virtual") else emptySet()) { model.choosePhone(screen = it) }
                        }
                        SettingsGroup {
                            SettingsRow(R.drawable.ic_ui_check, "设为新 Bot 默认配置", enabled = enabled) { model.savePhoneDefaults() }
                            InsetDivider()
                            SettingsRow(R.drawable.ic_ui_cable, "连接和授权") { helperReturn = "session"; panel = "helpers"; model.refreshPermissions() }
                        }
                    }
                    panel == "helpers" -> HelperSettings(model, engine.canRun)
                    else -> ProviderDetail(model, providerId, engine.canRun)
                } }
            }
        }
    }
    val auth = model.authPrompt
    if (auth != null) {
        val prompt = auth.getJSONObject("params")
        var answer by remember(auth.getString("id")) { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = model::cancelAuth, title = { Text("连接服务商") }, containerColor = MaterialTheme.colorScheme.surface,
            text = { Column(verticalArrangement = Arrangement.spacedBy(PhoneUi.space)) {
                model.authEvent?.let { AuthLinks(it) }
                Text(prompt.getString("message"))
                if (prompt.optString("type") == "select") prompt.getJSONArray("options").objects().forEach { option ->
                    TextButton(onClick = { model.answerAuth(option.getString("id")) }) { Text(option.getString("label")) }
                } else OutlinedTextField(answer, { answer = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (prompt.optString("type") == "secret" && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (prompt.optString("type") == "secret") { { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } } } else null)
            } },
            confirmButton = { if (prompt.optString("type") != "select") TextButton(onClick = { model.answerAuth(answer); answer = "" }, enabled = answer.isNotBlank() || prompt.optString("type") == "text") { Text("继续") } },
            dismissButton = { TextButton(onClick = model::cancelAuth) { Text("取消") } })
    }
}

@Composable internal fun ChatLayout(modifier: Modifier = Modifier, header: @Composable () -> Unit,
    composer: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val haze = rememberHazeState()
    SubcomposeLayout(modifier) { constraints ->
        val controls = constraints.copy(minHeight = 0)
        val top = subcompose("header") { Box { header() } }.single().measure(controls)
        val bottom = subcompose("composer") { Box { composer() } }.single().measure(controls)
        val padding = PaddingValues(start = PhoneUi.space, end = PhoneUi.space,
            top = top.height.toDp() + PhoneUi.space, bottom = bottom.height.toDp() + PhoneUi.space)
        val messages = subcompose("messages") { Box(Modifier.fillMaxSize().hazeSource(haze)) { content(padding) } }.single().measure(constraints)
        val edges = subcompose("edges") {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top.height.toDp() + PhoneUi.gap).edgeBlur(haze, true))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom.height.toDp() + PhoneUi.gap).edgeBlur(haze, false))
            }
        }.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            messages.placeRelative(0, 0)
            edges.placeRelative(0, 0)
            top.placeRelative(0, 0)
            bottom.placeRelative(0, constraints.maxHeight - bottom.height)
        }
    }
}

@Composable internal fun ChatHeader(idle: Boolean, conversations: () -> Unit, session: () -> Unit, newChat: () -> Unit, settings: () -> Unit, bot: ChatSession? = null) {
    var menu by remember { mutableStateOf(false) }
    val identity = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(horizontal = PhoneUi.inset, vertical = PhoneUi.small),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneUi.small)) {
        ToolIcon(R.drawable.ic_ui_panel_left, "打开对话列表", conversations, filled = true)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Surface(onClick = session, interactionSource = identity, modifier = Modifier.heightIn(min = PhoneUi.touch).testTag("chat_identity")
                .semantics { contentDescription = listOfNotNull(bot?.title ?: "PhoneBot", bot?.provider?.takeIf { it.isNotBlank() }).joinToString("，") },
                shape = CircleShape, color = MaterialTheme.colorScheme.background.copy(alpha = PhoneUi.glassAlpha), shadowElevation = PhoneUi.floatingElevation, border = BorderStroke(PhoneUi.hairline, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(horizontal = PhoneUi.inset), horizontalArrangement = Arrangement.spacedBy(PhoneUi.identityGap, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    val provider = bot?.provider.orEmpty()
                    Box(Modifier.width(if (provider.isEmpty()) PhoneUi.logo else PhoneUi.logo + PhoneUi.icon - PhoneUi.identityOverlap).height(PhoneUi.logo)) {
                        if (provider.isNotEmpty()) ProviderIcon(provider, Modifier.align(Alignment.CenterEnd).drawWithContent {
                            clipRect(left = PhoneUi.identityOverlap.toPx()) { this@drawWithContent.drawContent() }
                        })
                        BotAvatar(bot?.avatarColor ?: 0L, Modifier.size(PhoneUi.logo), working = !idle, interactionSource = identity)
                    }
                    Text(bot?.title ?: "PhoneBot", modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box {
            ToolIcon(R.drawable.ic_ui_ellipsis, "更多选项", { menu = true }, filled = true)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("创建 Bot") }, onClick = { menu = false; newChat() })
                DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; settings() })
            }
        }
    }
}

private val HelperOptions = listOf("builtin" to "内置连接", "shizuku" to "Shizuku")

@Composable private fun PermissionRows(model: PhoneBotModel, ids: Set<String>) {
    val context = LocalContext.current
    SettingsGroup {
        model.permissions.filter { it.id in ids }.forEachIndexed { index, permission ->
            if (index > 0) InsetDivider()
            val title = when (permission.id) {
                "accessibility" -> "读取和操作屏幕"
                "write_settings" -> "调节屏幕亮度"
                "notifications" -> "允许通知"
                "task_results" -> "任务完成提醒"
                else -> permission.name
            }
            SettingsRow(when (permission.id) {
                "notifications", "task_results" -> R.drawable.ic_ui_bell
                "write_settings" -> R.drawable.ic_ui_sun_moon
                else -> R.drawable.ic_ui_smartphone
            },
                title, if (permission.granted) "已开启" else "待开启") {
                if (permission.id == "notifications" && !permission.granted && PhonePermissions.notificationPermission() != null)
                    (context as? ComponentActivity)?.requestPermissions(arrayOf(PhonePermissions.notificationPermission()!!), 1)
                else context.startActivity(PhonePermissions.settings(context, permission.id))
            }
            if (!permission.granted) Text(permission.purpose, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = PhoneUi.settingsNote, modifier = Modifier.padding(start = PhoneUi.rowLeadingInset, end = PhoneUi.space, bottom = PhoneUi.space))
        }
    }
}

@Composable private fun HelperSettings(model: PhoneBotModel, ready: Boolean) {
    val context = LocalContext.current
    var helper by rememberSaveable { mutableStateOf(model.session?.helper ?: "builtin") }
    var port by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val state = model.helperStates[helper]
    val enabled = (model.activeTask.isEmpty() || model.permission.isNotEmpty()) && state?.optString("state") != "connecting"
    val paired = state?.optBoolean("paired") == true || state?.optString("state") in setOf("ready", "running")
    val online = state?.optString("state") in setOf("ready", "running")
    val network = model.permissions.firstOrNull { it.id == "local_network" }
    LaunchedEffect(helper, ready) { if (ready) model.helperCommand("status", helper) }
    LaunchedEffect(paired) { if (paired) code = "" }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = PhoneUi.settingsInset, vertical = PhoneUi.space),
        verticalArrangement = Arrangement.spacedBy(PhoneUi.gap)) {
        PermissionRows(model, setOf("accessibility", "write_settings"))
        SettingsGroup {
            SettingsChoice(R.drawable.ic_ui_cable, "控制方式", helper, HelperOptions) { helper = it }
            InsetDivider()
            SettingsRow(R.drawable.ic_ui_activity, "连接状态", helperStateName(state?.optString("state")), arrow = false, trailing = {
                Box {
                    ToolIcon(R.drawable.ic_ui_ellipsis, "更多连接操作", { menu = true })
                    DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        DropdownMenuItem(text = { Text("刷新连接状态") }, onClick = { menu = false; model.helperCommand("status", helper) }, enabled = ready)
                        if (helper == "builtin" && paired) DropdownMenuItem(text = { Text("解除配对", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; model.helperCommand("forget", helper); code = ""; port = "" }, enabled = model.activeTask.isEmpty())
                    }
                }
            })
        }
        if (!online) state?.optString("message")?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = PhoneUi.settingsNote)
        }
        if (helper == "builtin") {
            if (Build.VERSION.SDK_INT < 31) Text("内置连接需要 Android 12 及以上。", style = PhoneUi.settingsNote)
            else {
                if (network != null && !network.granted) OutlinedButton(onClick = {
                    (context as? ComponentActivity)?.requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 3)
                }) { Text("允许查找本机连接") }
                if (!paired) {
                    SettingsGroup {
                        SettingsRow(R.drawable.ic_ui_smartphone, "打开无线调试设置") {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(PhoneUi.space)) {
                        Text("在“无线调试”中打开“使用配对码配对设备”，填写弹窗中的端口和配对码。", style = PhoneUi.settingsNote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("配对端口") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("六位配对码") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation())
                        Button(onClick = { model.helperCommand("pair", helper, port.toInt(), code); code = "" },
                            enabled = enabled && ready && (port.toIntOrNull() ?: 0) in 1..65535 && code.length == 6 && network?.granted != false,
                            modifier = Modifier.fillMaxWidth()) { Text("连接") }
                    }
                } else {
                    if (!online) Button(onClick = { model.helperCommand("connect", helper) }, enabled = enabled && ready && network?.granted != false) { Text("重新连接") }
                    SettingsGroup {
                        SettingsRow(R.drawable.ic_ui_refresh_cw, "重启后自动恢复", arrow = false, trailing = {
                            Switch(checked = state?.optBoolean("autoRestore") == true,
                                onCheckedChange = { model.helperCommand("recovery", helper, enabled = it) }, enabled = model.activeTask.isEmpty() && ready)
                        })
                    }
                    Text("需开启无线调试并连接可信 Wi-Fi。", style = PhoneUi.settingsNote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (!online) {
            val needsGrant = Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED
            Button(onClick = {
                if (needsGrant) Shizuku.requestPermission(2)
                else context.startActivity(PhonePermissions.settings(context, "shizuku"))
            }, enabled = enabled) { Text(if (needsGrant) "授权 PhoneBot" else "打开 Shizuku") }
        }
    }
}

private fun helperStateName(state: String?): String = when (state) {
    "ready", "running" -> "可用"
    "connecting" -> "连接中"
    "not_paired" -> "未配对"
    "permission_required" -> "待授权"
    "wireless_debugging_off" -> "无线调试已关闭"
    "unsupported" -> "系统不支持"
    else -> "未连接"
}

@Composable internal fun TaskComposer(input: String, onInput: (String) -> Unit, busy: Boolean, ready: Boolean, submit: (String) -> Boolean, cancel: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val send = {
        if (!busy && ready && input.isNotBlank() && submit(input.trim())) {
            onInput("")
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = PhoneUi.inset, vertical = PhoneUi.tiny), verticalArrangement = Arrangement.spacedBy(PhoneUi.tiny)) {
        val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surface.copy(alpha = PhoneUi.glassAlpha))
            .border(PhoneUi.hairline, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge).heightIn(min = PhoneUi.touch).testTag("composer")) {
            val width = with(density) { (maxWidth - PhoneUi.composerEnd).roundToPx().coerceAtLeast(1) }
            val multiline = input.isNotEmpty() && measurer.measure(input, style, constraints = Constraints(maxWidth = width)).lineCount > 1
            BasicTextField(input, { if (it.length <= 12000) onInput(it) }, maxLines = 5,
                modifier = Modifier.fillMaxWidth().padding(start = PhoneUi.space, end = if (multiline) PhoneUi.space else PhoneUi.touch,
                    top = PhoneUi.inset, bottom = if (multiline) PhoneUi.touch else PhoneUi.inset).testTag("task_input"),
                textStyle = style, cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> Box { if (input.isEmpty()) Text("给我布置一个任务", color = MaterialTheme.colorScheme.onSurfaceVariant, style = style); inner() } })
            val enabled = busy || (input.isNotBlank() && ready)
            IconButton(onClick = if (busy) cancel else send, enabled = enabled,
                modifier = Modifier.align(Alignment.BottomEnd).size(PhoneUi.touch).semantics { contentDescription = if (busy) "停止任务" else "发送任务" }) {
                Box(Modifier.size(PhoneUi.send).testTag("task_action").background(if (enabled) LocalBotActionColor.current else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    if (busy) Box(Modifier.size(PhoneUi.inset).background(Color.White, MaterialTheme.shapes.small))
                    else Icon(painterResource(R.drawable.ic_ui_arrow_up), null, tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = PhoneUi.disabledAlpha), modifier = Modifier.size(PhoneUi.icon))
                }
            }
        }
        Text("内容由 AI 生成，请注意核实", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PhoneUi.hintAlpha), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable private fun ProviderList(model: PhoneBotModel, ready: Boolean, select: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = PhoneUi.settingsInset)) {
        SearchField(search, { search = it }, "搜索服务商", Modifier.padding(vertical = PhoneUi.inset))
        if (model.providers.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.clip(PhoneUi.settingsShape).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            items(model.providers.filter { it.name.contains(search, true) || it.id.contains(search, true) }, key = { it.id }) { provider ->
                SettingsRow(title = provider.name, value = if (provider.configured) "已配置" else "", enabled = ready,
                    leading = { ProviderIcon(provider.id) }) { select(provider.id) }
                InsetDivider()
            }
        }
    }
}

@Composable private fun ProviderDetail(model: PhoneBotModel, id: String, ready: Boolean, selectModel: Boolean = false) {
    val provider = model.providers.find { it.id == id } ?: return
    var search by rememberSaveable(id) { mutableStateOf("") }
    var searchStarted by remember(id) { mutableStateOf(false) }
    LaunchedEffect(search) {
        if (searchStarted) { kotlinx.coroutines.delay(300); model.catalog(id, query = search) }
        else searchStarted = true
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PhoneUi.settingsInset), verticalArrangement = Arrangement.spacedBy(PhoneUi.gap)) {
        if (!selectModel) item {
            SettingsGroup {
                provider.auth.forEachIndexed { index, auth ->
                    SettingsRow(R.drawable.ic_ui_key_round, if (provider.configured) "修改认证 · ${auth.second}" else auth.second, enabled = ready) { model.login(id, auth.first) }
                    if (index < provider.auth.lastIndex) InsetDivider()
                }
                if (provider.ambient) Text("此服务商使用环境凭据。Android 尚未验证此认证方式。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(PhoneUi.space))
                if (provider.configured) { InsetDivider(); SettingsRow(R.drawable.ic_ui_trash, "删除凭据", enabled = ready) { model.logout(id) } }
            }
        }
        val auth = model.authEvent
        if (auth != null) item {
            Column(Modifier.fillMaxWidth().padding(PhoneUi.inset), verticalArrangement = Arrangement.spacedBy(PhoneUi.inset)) {
                val message = auth.optString("message").ifEmpty { auth.optString("instructions") }
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium)
                AuthLinks(auth)
                TextButton(onClick = model::cancelAuth) { Text("取消认证") }
            }
        }
        if (selectModel) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("模型", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                ToolIcon(R.drawable.ic_ui_refresh_cw, "刷新 Pi 模型目录", { model.refresh(id) }, enabled = ready)
            }
            SearchField(search, { search = it }, "搜索模型")
        }
        if (selectModel) items(model.models.filter { it.name.contains(search, true) || it.id.contains(search, true) }, key = { it.id }) { item ->
            val selected = model.provider == id && model.model == item.id
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                .clickable(enabled = ready) { model.choose(id, item.id) }.padding(PhoneUi.space), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhoneUi.tiny)) {
                    Text(item.name, style = PhoneUi.settingsLabel, color = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                    Text((if (item.image) "支持图片 · " else "") + (if (model.verified(id, item.id)) "Android 连接已验证" else "Android 待验证"), style = PhoneUi.settingsNote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected) Icon(painterResource(R.drawable.ic_ui_check), "默认模型", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (selectModel && model.models.size < model.modelTotal) item { TextButton(onClick = { model.catalog(id, model.models.size, search) }, enabled = ready) { Text("加载更多模型") } }
        if (selectModel && model.models.isEmpty()) item { Text(if (ready) "目录为空。连接后刷新模型。" else "正在读取目录", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(PhoneUi.space)) }
        if (model.provider == id && model.model.isNotEmpty()) item {
            FilledTonalButton(onClick = model::verify, enabled = ready && (provider.configured || provider.ambient), modifier = Modifier.fillMaxWidth().padding(top = PhoneUi.inset)) { Text("验证连接") }
        }
    }
}

@Composable private fun AuthLinks(event: org.json.JSONObject) {
    val context = LocalContext.current
    if (event.has("userCode")) SelectionContainer { Text(event.getString("userCode"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
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
    TextField(value, change, modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_ui_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}
@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(PhoneUi.settingsShape).background(MaterialTheme.colorScheme.surfaceContainerLowest), content = content)
}
@Composable private fun InsetDivider() { HorizontalDivider(Modifier.padding(start = PhoneUi.rowLeadingInset), color = MaterialTheme.colorScheme.outlineVariant, thickness = PhoneUi.hairline) }
@Composable private fun SettingsRow(icon: Int? = null, title: String, value: String = "", enabled: Boolean = true, arrow: Boolean = true,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, click: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = PhoneUi.rowHeight)
        .then(if (click != null) Modifier.clickable(enabled = enabled, onClick = click) else Modifier)
        .padding(PhoneUi.space), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) leading() else if (icon != null) Icon(painterResource(icon), null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(PhoneUi.icon))
        Text(title, Modifier.weight(1f).padding(start = PhoneUi.space, end = PhoneUi.small), style = PhoneUi.settingsLabel,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) {
            if (value.isNotEmpty()) Text(value, style = PhoneUi.settingsValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing()
        } else {
            if (value.isNotEmpty()) Text(value, style = PhoneUi.settingsValue, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, modifier = Modifier.widthIn(max = PhoneUi.rowValueWidth), overflow = TextOverflow.Ellipsis)
            if (arrow) Icon(painterResource(R.drawable.ic_ui_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = PhoneUi.small).size(PhoneUi.icon))
        }
    }
}
@Composable private fun SettingsChoice(icon: Int, title: String, selected: String, options: List<Pair<String, String>>,
    enabled: Boolean = true, disabled: Set<String> = emptySet(), choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(icon, title, enabled = enabled, arrow = false, trailing = {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), style = PhoneUi.settingsValue,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = PhoneUi.rowValueWidth))
                Icon(painterResource(R.drawable.ic_ui_chevrons_up_down), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = PhoneUi.small).size(PhoneUi.icon))
            }
            DropdownMenu(expanded, { expanded = false }, shape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp, shadowElevation = PhoneUi.menuElevation) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label, style = PhoneUi.menuText) }, enabled = enabled && id !in disabled,
                        trailingIcon = { if (selected == id) Icon(painterResource(R.drawable.ic_ui_check), "已选择", Modifier.size(PhoneUi.icon)) },
                        onClick = { expanded = false; choose(id) })
                }
            }
        }
    }) { expanded = true }
}
@Composable private fun ToolIcon(icon: Int, label: String, click: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = false) {
    IconButton(onClick = click, enabled = enabled, modifier = modifier.size(PhoneUi.touch).then(if (filled)
        Modifier.background(MaterialTheme.colorScheme.background, CircleShape).border(PhoneUi.hairline, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier)) {
        Icon(painterResource(icon), label, modifier = Modifier.size(PhoneUi.icon))
    }
}
private fun phaseName(phase: EnginePhase) = when (phase) {
    EnginePhase.STOPPED -> "已停止"; EnginePhase.STARTING -> "正在启动"; EnginePhase.READY -> "就绪"
    EnginePhase.RUNNING -> "运行中"; EnginePhase.STOPPING -> "正在停止"; EnginePhase.FAILED -> "启动失败"
}
