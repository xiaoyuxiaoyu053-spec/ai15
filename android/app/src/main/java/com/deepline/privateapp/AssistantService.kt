package com.deepline.privateapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One visible element of the current screen, as exposed by Android's accessibility tree. */
class UiNode(
    val id: Int,
    val node: AccessibilityNodeInfo,
    val className: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val password: Boolean
) {
    val label: String get() = text.ifBlank { desc }.ifBlank { viewId.substringAfterLast('/') }
}

class ScreenSnapshot(val packageName: String, val nodes: List<UiNode>) {
    /** Cheap signature used to verify that an action changed (or did not change) the screen. */
    fun fingerprint(): Int {
        var h = packageName.hashCode()
        for (n in nodes) h = 31 * h + (n.text.hashCode() xor (n.desc.hashCode() * 7) xor n.bounds.hashCode())
        return h
    }

    fun describe(maxChars: Int): String {
        val sb = StringBuilder()
        sb.append("前台应用：").append(packageName.ifBlank { "未知" }).append('\n')
        if (nodes.isEmpty()) {
            sb.append("（没有读取到可见的界面元素：可能是受保护界面、游戏/视频画面或不支持无障碍的界面；可尝试 ocr 动作）")
            return sb.toString()
        }
        for (n in nodes) {
            val flags = ArrayList<String>()
            if (n.clickable) flags.add("可点击")
            if (n.longClickable) flags.add("可长按")
            if (n.editable) flags.add("输入框")
            if (n.scrollable) flags.add("可滚动")
            if (n.checkable) flags.add(if (n.checked) "已勾选" else "未勾选")
            val shown = when {
                n.password -> "（密码框：助手不会读取或输入）"
                n.text.isNotBlank() -> "\"" + n.text.take(80).replace('\n', ' ') + "\""
                n.desc.isNotBlank() -> "描述=\"" + n.desc.take(80).replace('\n', ' ') + "\""
                else -> ""
            }
            val line = "[${n.id}] ${n.className.substringAfterLast('.')} $shown ${flags.joinToString("/")} (${n.bounds.centerX()},${n.bounds.centerY()})\n"
            if (sb.length + line.length > maxChars) { sb.append("…（其余元素已省略，可滚动查看）"); break }
            sb.append(line)
        }
        return sb.toString()
    }
}

/**
 * The only component that touches other apps. It is an Android AccessibilityService, so it works only after the
 * user switches it on in system Settings, and only through the interfaces Android gives accessibility services.
 */
class DeepLineAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: DeepLineAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        /** Whether the user has switched the service on in system Accessibility settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val cn = ComponentName(context, DeepLineAccessibilityService::class.java)
            val enabled: String = (try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) {
                null
            }) ?: return false
            return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
        }

        fun openAccessibilitySettings(context: Context) {
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var overlay: AssistantOverlay? = null
        private set
    @Volatile
    var lastPackage: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = AssistantOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    override fun onInterrupt() {
        AssistantController.stop("系统中断了无障碍服务，任务已停止。")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AssistantController.stop("无障碍权限已关闭，任务已停止。")
        overlay?.hideAll()
        overlay = null
        instance = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    // ───────────── reading the screen ─────────────

    fun isDeviceLocked(): Boolean =
        try { (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked } catch (_: Exception) { false }

    fun captureSnapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val out = ArrayList<UiNode>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || out.size >= 150) return
            if (node.isVisibleToUser) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val interesting = text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
                if (interesting) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (!r.isEmpty) {
                        out.add(
                            UiNode(
                                out.size + 1, node, node.className?.toString().orEmpty(), text, desc,
                                node.viewIdResourceName.orEmpty(), r, node.isClickable, node.isLongClickable, node.isEditable,
                                node.isScrollable, node.isCheckable, node.isChecked, node.isPassword
                            )
                        )
                    }
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return ScreenSnapshot(root.packageName?.toString().orEmpty(), out)
    }

    // ───────────── acting on the screen ─────────────

    fun clickNode(n: UiNode): Boolean {
        try { n.node.refresh() } catch (_: Exception) { }
        var cur: AccessibilityNodeInfo? = n.node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }

    fun longClickNode(n: UiNode): Boolean {
        try { n.node.refresh() } catch (_: Exception) { }
        var cur: AccessibilityNodeInfo? = n.node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isLongClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }

    fun setNodeText(n: UiNode, text: String): Boolean {
        if (n.password) return false
        try { n.node.refresh() } catch (_: Exception) { }
        n.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun nodeText(n: UiNode): String {
        try { n.node.refresh() } catch (_: Exception) { }
        return n.node.text?.toString().orEmpty()
    }

    suspend fun gesture(path: Path, durationMs: Long): Boolean {
        overlay?.setTouchable(false)
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))).build()
            val started = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!started && cont.isActive) cont.resume(false)
        }
        overlay?.setTouchable(true)
        return ok
    }

    suspend fun tap(x: Float, y: Float, longPress: Boolean = false): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(p, if (longPress) 900L else 60L)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(p, durationMs)
    }

    suspend fun scroll(direction: String, target: UiNode?): Boolean {
        val action = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> return false
        }
        if (target != null) {
            try { target.node.refresh() } catch (_: Exception) { }
            if (target.node.performAction(action)) return true
        }
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return when (direction) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.30f)
            "up" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.72f)
            "left" -> swipe(w * 0.75f, h / 2, w * 0.25f, h / 2)
            else -> swipe(w * 0.25f, h / 2, w * 0.75f, h / 2)
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ───────────── apps and web ─────────────

    /** Finds a launchable app by label or package name (uses the <queries> launcher declaration, no extra permission). */
    fun findLaunchablePackage(name: String): Pair<String, String>? {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
        val q = name.trim()
        var best: Pair<String, String>? = null
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            if (pkg.equals(q, true) || label.equals(q, true)) return pkg to label
            if (best == null && (label.contains(q, true) || q.contains(label, true))) best = pkg to label
        }
        return best
    }

    fun launchPackage(pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openUrl(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    // ───────────── screenshot + OCR (Android 11+, on-device recognition) ─────────────

    /** Returns recognised text lines with their centre coordinates, or null when unavailable (older Android, protected screen). */
    suspend fun ocrScreen(): List<Pair<Rect, String>>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bmp = takeShot() ?: return null
        return recognize(bmp)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeShot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    var soft: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Throwable) {
                    }
                    try { screenshot.hardwareBuffer.close() } catch (_: Throwable) { }
                    if (cont.isActive) cont.resume(soft)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun recognize(bmp: Bitmap): List<Pair<Rect, String>>? = suspendCancellableCoroutine { cont ->
        try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val lines = ArrayList<Pair<Rect, String>>()
                    for (block in result.textBlocks) for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        lines.add(box to line.text)
                    }
                    recognizer.close()
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (cont.isActive) cont.resume(null)
                }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }
}

/**
 * Floating progress panel (status, step counter, pause/resume, STOP) and the confirmation card for sensitive actions.
 * Uses TYPE_ACCESSIBILITY_OVERLAY, which an accessibility service may show without any extra permission.
 */
class AssistantOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var pauseView: TextView? = null
    private var confirm: LinearLayout? = null
    private var confirmTimeout: Runnable? = null

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView = TextView(service).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = rounded(color, 18)
        setOnClickListener { onClick() }
    }

    private fun overlayParams(gravity: Int, yDp: Int): WindowManager.LayoutParams {
        val width = service.resources.displayMetrics.widthPixels - dp(24)
        return WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            y = dp(yDp)
        }
    }

    fun showPanel(personaName: String) {
        hidePanel()
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(0xF01C1C1E.toInt(), 20)
        }
        titleView = TextView(service).apply { setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); text = "智能助手 · $personaName" }
        statusView = TextView(service).apply { setTextColor(0xFFD1D1D6.toInt()); textSize = 13f; setPadding(0, dp(4), 0, dp(8)); text = "准备中…" }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        pauseView = button("暂停", 0xFF48484A.toInt()) { AssistantController.togglePause() }
        val stop = button("停止", 0xFFFF453A.toInt()) { AssistantController.stop("你已停止任务。") }
        row.addView(pauseView)
        row.addView(View(service), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(stop)
        root.addView(titleView)
        root.addView(statusView)
        root.addView(row)
        val params = overlayParams(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 8)
        try {
            wm.addView(root, params)
            panel = root
            panelParams = params
        } catch (_: Exception) {
            panel = null
        }
    }

    fun update(status: String, step: Int, max: Int, paused: Boolean) {
        statusView?.text = "步骤 $step/$max · $status"
        pauseView?.text = if (paused) "继续" else "暂停"
    }

    /** While a coordinate gesture is injected the panel must not swallow the touch. */
    fun setTouchable(touchable: Boolean) {
        val p = panel ?: return
        val params = panelParams ?: return
        params.flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(p, params) } catch (_: Exception) { }
    }

    fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        panelParams = null
    }

    fun askConfirm(message: String, onResult: (Boolean) -> Unit) {
        dismissConfirm()
        var answered = false
        fun answer(allowed: Boolean) {
            if (answered) return
            answered = true
            dismissConfirm()
            onResult(allowed)
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(0xF5FFFFFF.toInt(), 24)
        }
        card.addView(TextView(service).apply { text = "需要你的确认"; setTextColor(0xFF111111.toInt()); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        card.addView(TextView(service).apply { text = message; setTextColor(0xFF333333.toInt()); textSize = 14f; setPadding(0, dp(8), 0, dp(6)) })
        card.addView(TextView(service).apply { text = "60 秒内未选择将自动拒绝。"; setTextColor(0xFF8E8E93.toInt()); textSize = 12f; setPadding(0, 0, 0, dp(10)) })
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(button("拒绝", 0xFF8E8E93.toInt()) { answer(false) })
        row.addView(View(service), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(button("允许这一次", 0xFF0A84FF.toInt()) { answer(true) })
        card.addView(row)
        try {
            wm.addView(card, overlayParams(Gravity.CENTER, 0))
            confirm = card
            val timeout = Runnable { answer(false) }
            confirmTimeout = timeout
            handler.postDelayed(timeout, 60_000L)
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun dismissConfirm() {
        confirmTimeout?.let { handler.removeCallbacks(it) }
        confirmTimeout = null
        confirm?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        confirm = null
    }

    fun hideAll() {
        dismissConfirm()
        hidePanel()
    }
}
