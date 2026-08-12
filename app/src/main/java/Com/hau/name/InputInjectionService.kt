package Com.hau.name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

private const val TAG = "InputInjectionService"

class InputInjectionService : AccessibilityService() {

    private var roomCode: String? = null
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // ── Fix gõ chữ: debounce — chỉ xử lý lệnh text sau 50ms không có lệnh mới
    private var pendingText = StringBuilder()
    private val textFlushRunnable = Runnable { flushPendingText() }
    private val TEXT_DEBOUNCE_MS = 50L

    // ── Fix vuốt: theo dõi pointer đang giữ
    private data class PointerState(val startX: Float, val startY: Float,
                                     var lastX: Float, var lastY: Float,
                                     val startTime: Long)
    private val activePointers = mutableMapOf<Int, PointerState>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active_room_code") roomCode = prefs.getString("active_room_code", null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("remote_assist", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        roomCode = prefs.getString("active_room_code", null)
        ControlCommandBus.subscribe { json -> handleCommand(json) }
        ControlCommandBus.subscribeReply { } // placeholder
        Log.d(TAG, "Service connected, roomCode=$roomCode")
    }

    private fun handleCommand(json: String) {
        if (roomCode == null) return
        val obj = try { JSONObject(json) } catch (e: Exception) { return }

        when (obj.optString("type")) {

            // ── Tap (chạm nhấn) ───────────────────────────────────────────
            "tap" -> {
                val (px, py) = toPixels(obj) ?: return
                performTap(px, py)
            }

            // ── Touch down ────────────────────────────────────────────────
            "touch_down" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                activePointers[ptr] = PointerState(px, py, px, py, System.currentTimeMillis())
                // Dispatch stroke ngắn tại điểm down
                val path = Path().apply { moveTo(px, py); lineTo(px + 0.1f, py) }
                dispatchStroke(path, 0, 60, willContinue = true)
            }

            // ── Touch move: vuốt mượt ─────────────────────────────────────
            "touch_move" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val state = activePointers[ptr] ?: run {
                    // Không có down trước → tạo mới
                    activePointers[ptr] = PointerState(px, py, px, py, System.currentTimeMillis())
                    return
                }
                val path = Path().apply {
                    moveTo(state.lastX, state.lastY)
                    lineTo(px, py)
                }
                state.lastX = px; state.lastY = py
                // Dispatch liên tục với willContinue=true để giữ touch
                dispatchStroke(path, 0, 20, willContinue = true)
            }

            // ── Touch up ──────────────────────────────────────────────────
            "touch_up" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val state = activePointers.remove(ptr)
                val path = Path().apply {
                    moveTo(state?.lastX ?: px, state?.lastY ?: py)
                    lineTo(px, py)
                }
                // willContinue=false → ACTION_UP
                dispatchStroke(path, 0, 60, willContinue = false)
            }

            // ── Swipe cũ (tương thích ngược) ─────────────────────────────
            "swipe" -> {
                val (x1, y1) = toPixels(obj) ?: return
                val (w, h) = ScreenMetrics.realSize(this)
                val x2 = (obj.optDouble("x2").toFloat() * w).coerceIn(0f, w - 1f)
                val y2 = (obj.optDouble("y2").toFloat() * h).coerceIn(0f, h - 1f)
                val duration = obj.optLong("duration", 300L)
                val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                dispatchStroke(path, 0, duration, willContinue = false)
            }

            // ── Kích thước màn hình ───────────────────────────────────────
            "request_screen_size" -> {
                val (w, h) = ScreenMetrics.realSize(this)
                val reply = JSONObject().apply {
                    put("type", "screen_size"); put("w", w); put("h", h)
                }
                ControlCommandBus.publishReply(reply.toString())
            }

            // ── Text input: FIX gõ nhiều ký tự ───────────────────────────
            // Vấn đề cũ: mỗi lần gọi injectText() → paste vào field → field
            // trigger TextWatcher → gửi lại → vòng lặp vô tận.
            // Fix: dùng clipboard một lần, debounce, paste 1 lần duy nhất.
            "text_input" -> {
                val text = obj.optString("text", "")
                if (text.isEmpty()) return
                // Tích lũy ký tự trong 50ms rồi paste 1 lần
                pendingText.append(text)
                handler.removeCallbacks(textFlushRunnable)
                handler.postDelayed(textFlushRunnable, TEXT_DEBOUNCE_MS)
            }

            // ── Key events ────────────────────────────────────────────────
            "key_event" -> {
                when (obj.optString("key")) {
                    "back"      -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home"      -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents"   -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "backspace" -> deleteLastChar()
                    "enter"     -> performActionOnFocus(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    "tab"       -> performActionOnFocus(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    "escape"    -> performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun toPixels(obj: JSONObject): Pair<Float, Float>? {
        if (!obj.has("x") || !obj.has("y")) return null
        val (w, h) = ScreenMetrics.realSize(this)
        val px = (obj.optDouble("x").toFloat() * w).coerceIn(0f, w - 1f)
        val py = (obj.optDouble("y").toFloat() * h).coerceIn(0f, h - 1f)
        return px to py
    }

    private fun performTap(px: Float, py: Float) {
        val path = Path().apply { moveTo(px, py); lineTo(px + 0.1f, py) }
        dispatchStroke(path, 0, 80, willContinue = false)
    }

    private fun dispatchStroke(path: Path, startTime: Long, duration: Long, willContinue: Boolean) {
        val stroke = GestureDescription.StrokeDescription(path, startTime, duration, willContinue)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * FIX gõ chữ: dùng clipboard paste thay vì inject từng ký tự.
     * Paste 1 lần sau debounce → không bị nhân ký tự.
     */
    private fun flushPendingText() {
        val text = pendingText.toString()
        pendingText.clear()
        if (text.isEmpty()) return
        try {
            val clip = android.content.ClipData.newPlainText("t", text)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(clip)
            // Paste vào node đang focus
            val node = rootInActiveWindow
                ?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "flushPendingText error: ${e.message}")
        }
    }

    private fun deleteLastChar() {
        try {
            val node = rootInActiveWindow
                ?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return
            val text = node.text?.toString() ?: return
            if (text.isEmpty()) return
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text.dropLast(1))
            }
            node.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "deleteLastChar error: ${e.message}")
        }
    }

    private fun performActionOnFocus(action: Int) {
        try {
            rootInActiveWindow
                ?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(action)
        } catch (e: Exception) {}
    }

    private fun sendScreenInfo() {
        val (w, h) = ScreenMetrics.realSize(this)
        val reply = JSONObject().apply {
            put("type", "screen_orientation")
            put("landscape", w > h)
            put("w", w)
            put("h", h)
        }
        ControlCommandBus.publishReply(reply.toString())
    }

    // Khi điện thoại xoay → tự động thông báo cho Máy A
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ sendScreenInfo() }, 200) // Chờ layout update xong
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(textFlushRunnable)
        ControlCommandBus.unsubscribe()
        if (::prefs.isInitialized)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }
}
