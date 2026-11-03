package dev.phonebot.android.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

internal fun Rect.json() = JSONArray(listOf(left, top, right, bottom))

internal data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int, val window: Rect, val content: Rect, val displayId: Int = Display.DEFAULT_DISPLAY) {
    fun json(): JSONObject = JSONObject().put("displayId", displayId).put("rotation", rotation)
        .put("fullBounds", Rect(0, 0, width, height).json()).put("windowBounds", window.json()).put("contentBounds", content.json())
    fun point(x: Double, y: Double): Pair<Float, Float> {
        require(x.isFinite() && y.isFinite() && x == x.toInt().toDouble() && y == y.toInt().toDouble() && x >= 0 && x < width && y >= 0 && y < height) { "坐标超出屏幕" }
        return x.toFloat() to y.toFloat()
    }
    companion object {
        @Suppress("DEPRECATION")
        fun read(service: AccessibilityService, root: AccessibilityNodeInfo? = null, displayId: Int = Display.DEFAULT_DISPLAY, windows: List<AccessibilityWindowInfo> = service.windows): ScreenGeometry {
            val display = checkNotNull(service.getSystemService(DisplayManager::class.java).getDisplay(displayId)) { "执行屏幕已断开" }
            // Gesture coordinates use the rotated logical display, including system bars.
            val size = Point().also(display::getRealSize)
            val full = Rect(0, 0, size.x, size.y)
            val window = Rect().also { out -> windows.firstOrNull { it.id == root?.windowId }?.getBoundsInScreen(out) }
            if (!window.intersect(full)) window.set(full)
            val content = Rect().also { root?.getBoundsInScreen(it) }
            if (!content.intersect(window)) content.set(window)
            return ScreenGeometry(size.x, size.y, display.rotation, window, content, displayId)
        }
    }
}

internal class ScreenState(private val service: AccessibilityService) {
    var displayId = Display.DEFAULT_DISPLAY
    private data class Entry(val id: String, val node: AccessibilityNodeInfo, val key: String, val unique: String?, val value: JSONObject)
    private var issued = 0L
    private var sequence = 0L
    private var scope = ""
    private var entries = emptyList<Entry>()
    private val remembered = linkedMapOf<String, Entry>()
    private var shown: JSONObject? = null
    lateinit var geometry: ScreenGeometry; private set
    var latest = JSONObject(); private set
    var taskId = ""
    var root: AccessibilityNodeInfo? = null; private set

    fun reset() { entries = emptyList(); remembered.clear(); shown = null; scope = "" }

    private fun identity(node: AccessibilityNodeInfo, ancestry: String): String =
        "$ancestry|${node.className}|${node.viewIdResourceName}|${if (node.isPassword) "" else node.contentDescription?.toString()?.take(120).orEmpty()}|${if (node.isEditable || node.isPassword) "" else node.text?.toString()?.take(120).orEmpty()}"

    fun scan(): JSONObject {
        val windows = runCatching { if (Build.VERSION.SDK_INT >= 30) service.windowsOnAllDisplays.get(displayId).orEmpty() else service.windows }.getOrDefault(emptyList())
        root = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { if (it.isActive) 2 else if (it.isFocused) 1 else 0 }
            .firstNotNullOfOrNull { it.root } ?: if (displayId == Display.DEFAULT_DISPLAY) runCatching { service.rootInActiveWindow }.getOrNull() else null
        val root = root
        geometry = ScreenGeometry.read(service, root, displayId, windows)
        val nextScope = "$displayId:${root?.packageName}:${root?.windowId}:${geometry.width}:${geometry.height}:${geometry.rotation}"
        if (scope != nextScope) { reset(); scope = nextScope }
        val collected = mutableListOf<Triple<AccessibilityNodeInfo, String, Int?>>()
        fun visit(node: AccessibilityNodeInfo, ancestry: String, parentIndex: Int?) {
            var index = parentIndex
            if (node.isVisibleToUser && (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable)) {
                index = collected.size
                collected += Triple(node, ancestry, parentIndex)
            }
            val parent = "$ancestry/${node.viewIdResourceName.orEmpty()}:${node.className}"
            for (child in 0 until node.childCount) node.getChild(child)?.let { visit(it, parent, index) }
        }
        root?.let { visit(it, "", null) }
        val old = entries.groupBy { it.key }
        val used = mutableSetOf<String>()
        val identities = collected.groupingBy { (node, ancestry) -> identity(node, ancestry) }.eachCount()
        entries = collected.map { (node, ancestry) ->
            val text = if (node.isPassword) "[受保护输入]" else node.text?.toString()?.take(120).orEmpty()
            val description = if (node.isPassword) "" else node.contentDescription?.toString()?.take(120).orEmpty()
            val key = identity(node, ancestry)
            val unique = if (Build.VERSION.SDK_INT >= 33) node.uniqueId else null
            val matches = old[key].orEmpty().filter { it.id !in used }
            val exact = matches.filter { if (unique != null) it.unique == unique else it.node == node }
            val previous = exact.singleOrNull() ?: matches.singleOrNull()?.takeIf { identities[key] == 1 }
            val id = previous?.id ?: "e${++issued}"
            used += id
            val bounds = Rect().also(node::getBoundsInScreen)
            val value = JSONObject().put("id", id).put("resourceId", node.viewIdResourceName.orEmpty()).put("text", text).put("description", description)
                .put("bounds", bounds.json()).put("clickable", node.isClickable).put("editable", node.isEditable).put("enabled", node.isEnabled)
                .put("focused", node.isFocused).put("selected", node.isSelected).put("scrollable", node.isScrollable)
                .put("checkable", node.isCheckable).put("checked", node.isChecked).put("class", node.className?.toString())
            if (node.isEditable && !node.isPassword) value.put("hintText", node.hintText?.toString().orEmpty()).put("showingHintText", node.isShowingHintText)
            Entry(id, node, key, unique, value)
        }
        entries.forEachIndexed { index, entry -> entry.value.put("parent", collected[index].third?.let { entries[it].id } ?: JSONObject.NULL) }
        val children = entries.groupBy { it.value.optString("parent") }
        entries.asReversed().forEach { entry ->
            val own = entry.value.getString("text").ifBlank { entry.value.getString("description") }
            entry.value.put("label", own.ifBlank { children[entry.id].orEmpty().map { it.value.getString("label") }.filter(String::isNotBlank).distinct().joinToString(" · ") }.take(120))
        }
        entries.forEach { remembered[it.id] = it }
        while (remembered.size > 320) remembered.remove(remembered.keys.first())
        latest = JSONObject().put("snapshot", ++sequence).put("page", scope).put("package", root?.packageName?.toString() ?: JSONObject.NULL)
            .put("taskId", taskId).put("observedAt", System.currentTimeMillis()).put("treeReadable", root != null)
            .put("windows", JSONArray(windows.map { window -> JSONObject().put("id", window.id).put("type", window.type)
                .put("active", window.isActive).put("focused", window.isFocused) }))
            .put("width", geometry.width).put("height", geometry.height).put("geometry", geometry.json())
            .put("elements", JSONArray(entries.map { it.value })).put("truncated", false)
        return latest
    }

    fun resolve(params: JSONObject): AccessibilityNodeInfo {
        val id = params.optString("element")
        val candidates = if (id.isNotEmpty()) {
            val original = entries.find { it.id == id } ?: remembered[id] ?: error("元素编号已过期。请重新读取页面。")
            entries.filter { it.key == original.key }.let { matches ->
                matches.filter { it.id == id }.singleOrNull()?.let(::listOf) ?: matches
            }
        } else {
            require(params.has("text") || params.has("resourceId")) { "请提供目标元素、文字或资源 ID" }
            entries.filter { entry ->
                val node = entry.node
                !node.isPassword && (!params.has("text") || node.text?.toString() == params.getString("text") || node.contentDescription?.toString() == params.getString("text")) &&
                    (!params.has("resourceId") || node.viewIdResourceName == params.getString("resourceId"))
            }
        }
        check(candidates.size == 1) { "目标不存在或匹配不唯一。请重新读取页面。" }
        val node = candidates.single().node
        check(node.refresh() && node.isVisibleToUser && node.isEnabled) { "目标不可操作。请重新读取页面。" }
        return node
    }

    fun text(): Set<String> = entries.filterNot { it.node.isPassword }.mapNotNull { it.node.text?.toString() }.toSet()
    fun condition(expected: JSONObject): Boolean {
        if (!latest.optBoolean("treeReadable")) return false
        if (expected.has("package") && latest.optString("package") != expected.getString("package")) return false
        val selectors = listOf("element", "text", "textContains", "resourceId", "editable", "enabled", "selected", "checked", "withinResourceId")
        if (selectors.none(expected::has)) return !expected.optBoolean("absent")
        val found = entries.any { entry ->
            val node = entry.node
            !node.isPassword && node.isVisibleToUser &&
                (!expected.has("element") || entry.id == expected.getString("element")) &&
                (!expected.has("text") || (node.text?.toString() == expected.getString("text") || node.contentDescription?.toString() == expected.getString("text")) && node.isEditable == expected.optBoolean("editable")) &&
                (!expected.has("textContains") || (node.text?.contains(expected.getString("textContains")) == true || node.contentDescription?.contains(expected.getString("textContains")) == true) && node.isEditable == expected.optBoolean("editable")) &&
                (!expected.has("resourceId") || node.viewIdResourceName == expected.getString("resourceId")) &&
                (!expected.has("editable") || node.isEditable == expected.getBoolean("editable")) &&
                (!expected.has("enabled") || node.isEnabled == expected.getBoolean("enabled")) &&
                (!expected.has("selected") || node.isSelected == expected.getBoolean("selected")) &&
                (!expected.has("checked") || node.isChecked == expected.getBoolean("checked")) &&
                (!expected.has("withinResourceId") || entry.key.contains("/${expected.getString("withinResourceId")}:"))
        }
        return found != expected.optBoolean("absent")
    }
    fun editable(): AccessibilityNodeInfo? = entries.map { it.node }.singleOrNull { it.isEditable && it.isFocused && !it.isPassword }

    fun fingerprint(): String = if (latest.optBoolean("treeReadable")) latest.optString("page") + entries.joinToString { it.value.toString() } else ""

    fun present(full: Boolean): JSONObject {
        val current = latest
        val base = shown
        val values = entries.associate { it.id to it.value }
        val old = base?.optJSONArray("elements")?.let { array -> (0 until array.length()).associate { array.getJSONObject(it).let { value -> value.getString("id") to value } } }.orEmpty()
        val retained = values.keys.intersect(old.keys).size
        val replace = full || base == null || base.getString("page") != current.getString("page") || retained < minOf(old.size, values.size) / 2
        val result = JSONObject(current.toString())
        if (!replace) {
            result.remove("elements")
            result.put("baseSnapshot", base!!.getLong("snapshot"))
                .put("added", JSONArray(values.filterKeys { it !in old }.values.toList()))
                .put("removed", JSONArray(old.keys.filter { it !in values }))
                .put("updated", JSONArray(values.filter { (id, value) -> id in old && value.toString() != old[id].toString() }.values.toList()))
        }
        result.put("kind", if (replace) "full" else "diff")
        shown = JSONObject(current.toString())
        return result
    }
}
