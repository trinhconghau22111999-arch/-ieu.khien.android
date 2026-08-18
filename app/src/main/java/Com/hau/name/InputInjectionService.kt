package Com.hau.name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

private const val TAG = "InputInjectionService"

class InputInjectionService : AccessibilityService() {

    private var roomCode: String? = null
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // ── Fix gõ chữ: chỉ dùng SET_TEXT, không dùng clipboard paste ──────────
    // Vấn đề cũ: paste → TextWatcher ở Máy A gửi lại → vòng lặp vô tận → 8 ký tự
    // Fix: dùng ACTION_SET_TEXT trực tiếp, không qua clipboard
    private var currentFieldText = StringBuilder()

    // ── Fix vuốt: dùng 1 stroke liên tục thay vì nhiều stroke rời ───────────
    // Vấn đề cũ: mỗi touch_move dispatch 1 GestureDescription mới → Android
    // coi là gesture mới → ACTION_DOWN mới → không phải move
    // Fix: tích lũy path từ down→move→up rồi dispatch 1 lần duy nhất khi UP
    private data class PointerState(
        val startX: Float, val startY: Float,
        val path: Path,
        val startTime: Long,
        var lastX: Float, var lastY: Float,
        var pathLength: Int = 1
    )
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
        Log.d(TAG, "Service connected, roomCode=$roomCode")
    }

    private fun handleCommand(json: String) {
        // Nếu roomCode null, thử đọc lại từ prefs (có thể chưa set khi service connect)
        if (roomCode == null) {
            roomCode = prefs.getString("active_room_code", null)
            if (roomCode == null) return
        }
        val obj = try { JSONObject(json) } catch (e: Exception) { return }

        when (obj.optString("type")) {

            // ── TAP: chạm nhấn nhanh ─────────────────────────────────────
            "touch_down" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val path = Path().apply { moveTo(px, py) }
                activePointers[ptr] = PointerState(px, py, path, System.currentTimeMillis(), px, py)
            }

            // ── MOVE: tích lũy vào path ───────────────────────────────────
            // KHÔNG dispatch ở đây — chờ đến UP mới dispatch toàn bộ
            "touch_move" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val state = activePointers[ptr] ?: run {
                    val path = Path().apply { moveTo(px, py) }
                    activePointers[ptr] = PointerState(px, py, path, System.currentTimeMillis(), px, py)
                    return
                }
                state.path.lineTo(px, py)
                state.lastX = px
                state.lastY = py
                state.pathLength++
            }

            // ── UP: dispatch gesture hoàn chỉnh ──────────────────────────
            "touch_up" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val state = activePointers.remove(ptr) ?: run {
                    // Không có down trước → tap đơn
                    performTap(px, py)
                    return
                }
                state.path.lineTo(px, py)

                val elapsed = System.currentTimeMillis() - state.startTime
                val dx = px - state.startX
                val dy = py - state.startY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                if (dist < 20f && state.pathLength <= 3) {
                    // Ít di chuyển → TAP
                    performTap(state.startX, state.startY)
                } else {
                    // Có di chuyển → SWIPE (1 stroke liên tục từ down đến up)
                    val duration = elapsed.coerceIn(100, 2000)
                    val stroke = GestureDescription.StrokeDescription(
                        state.path, 0, duration)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    dispatchGesture(gesture, null, null)
                }
            }

            // ── Kích thước + orientation ──────────────────────────────────
            "request_screen_size" -> sendScreenInfo()

            // ── Text input: FIX gõ nhiều ký tự ───────────────────────────
            // Dùng ACTION_SET_TEXT thay vì clipboard paste
            // → không trigger TextWatcher → không vòng lặp
            "text_input" -> {
                val newChar = obj.optString("text", "")
                if (newChar.isEmpty()) return
                currentFieldText.append(newChar)
                val node = getFocusedInput() ?: run {
                    // Không có input focus → thử paste vào clipboard làm fallback
                    currentFieldText.clear()
                    currentFieldText.append(newChar)
                    return
                }
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        currentFieldText.toString()
                    )
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!ok) {
                    // Fallback clipboard nếu SET_TEXT không được
                    val clip = android.content.ClipData.newPlainText("t", newChar)
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(clip)
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }

            // ── Key events ────────────────────────────────────────────────
            "key_event" -> {
                when (obj.optString("key")) {
                    "backspace" -> {
                        if (currentFieldText.isNotEmpty()) {
                            currentFieldText.deleteCharAt(currentFieldText.length - 1)
                        }
                        val node = getFocusedInput()
                        if (node != null && currentFieldText.isNotEmpty()) {
                            val args = android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    currentFieldText.toString()
                                )
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        } else {
                            // Field trống → SET_TEXT ""
                            node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                                android.os.Bundle().apply {
                                    putCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                                })
                        }
                    }
                    "enter"   -> {
                        currentFieldText.clear()
                        getFocusedInput()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            ?: performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    "tab"     -> getFocusedInput()?.performAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    "escape"  -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "back"    -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home"    -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun toPixels(obj: JSONObject): Pair<Float, Float>? {
        if (!obj.has("x") || !obj.has("y")) return null
        val (w, h) = ScreenMetrics.realSize(this)
        return (obj.optDouble("x").toFloat() * w).coerceIn(0f, w - 1f) to
               (obj.optDouble("y").toFloat() * h).coerceIn(0f, h - 1f)
    }

    private fun performTap(px: Float, py: Float) {
        val path = Path().apply { moveTo(px, py); lineTo(px + 1f, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun getFocusedInput(): AccessibilityNodeInfo? = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (e: Exception) { null }

    private fun sendScreenInfo() {
        val (w, h) = ScreenMetrics.realSize(this)
        ControlCommandBus.publishReply(JSONObject().apply {
            put("type", "screen_orientation")
            put("landscape", w > h)
            put("w", w); put("h", h)
        }.toString())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ sendScreenInfo() }, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reset text tracker khi focus đổi sang field khác
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            currentFieldText.clear()
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(orientationUpdateRunnable)
        ControlCommandBus.unsubscribe()
        if (::prefs.isInitialized)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }
}
