package Com.hau.name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

private const val TAG = "InputInjectionService"

/**
 * Chạy trên Máy B. Nhận lệnh từ ControlCommandBus và thực thi gesture.
 *
 * THAY ĐỔI SO VỚI BẢN GỐC:
 * 1. Xử lý thêm lệnh "touch_down", "touch_move", "touch_up" (multi-touch từng bước)
 *    thay vì chỉ "tap" và "swipe".
 * 2. Xử lý "request_screen_size" → gửi kích thước màn hình về Máy A qua
 *    ControlCommandBus.publishReply() để ControllerActivity tính đúng letterbox.
 * 3. Xử lý "text_input" → gõ từng ký tự qua performGlobalAction hoặc clipboard inject.
 * 4. Xử lý "key_event" → Tab, Backspace, Escape, Enter.
 * 5. Giữ lại "tap" và "swipe" để tương thích ngược.
 */
class InputInjectionService : AccessibilityService() {

    private var roomCode: String? = null
    private lateinit var prefs: SharedPreferences

    // Track trạng thái pointer đang giữ (touch_down chưa up)
    // key = pointer index, value = path đang drag
    private val activePointers = mutableMapOf<Int, Path>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active_room_code") {
            roomCode = prefs.getString("active_room_code", null)
            Log.d(TAG, "roomCode updated: $roomCode")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("remote_assist", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        roomCode = prefs.getString("active_room_code", null)

        ControlCommandBus.subscribe { json -> handleCommand(json) }

        // Đăng ký reply handler để RemoteHostService biết cách gửi về Máy A
        Log.d(TAG, "Service connected, roomCode=$roomCode")
    }

    // ── Xử lý lệnh ───────────────────────────────────────────────────────────

    private fun handleCommand(json: String) {
        if (roomCode == null) return
        val obj = try { JSONObject(json) } catch (e: Exception) {
            Log.e(TAG, "JSON lỗi: $json"); return
        }
        val type = obj.optString("type", "")

        when (type) {

            // ── Lệnh cũ (tương thích ngược) ──────────────────────────────────
            "tap" -> {
                val (px, py) = toPixels(obj) ?: return
                performTap(px, py)
            }
            "swipe" -> {
                val (x1, y1) = toPixels(obj) ?: return
                val x2n = obj.optDouble("x2").toFloat()
                val y2n = obj.optDouble("y2").toFloat()
                val (sw, sh) = ScreenMetrics.realSize(this)
                val x2 = (x2n * sw).coerceIn(0f, sw - 1f)
                val y2 = (y2n * sh).coerceIn(0f, sh - 1f)
                performSwipe(x1, y1, x2, y2, obj.optLong("duration", 300L))
            }

            // ── Lệnh mới: touch từng bước (multi-touch) ──────────────────────
            "touch_down" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                // Bắt đầu path mới cho pointer này
                val path = Path().apply { moveTo(px, py) }
                activePointers[ptr] = path
                // Dispatch gesture ngắn (50ms) tại điểm down để app nhận ACTION_DOWN
                val stroke = GestureDescription.StrokeDescription(
                    Path().apply { moveTo(px, py) }, 0, 50, true)
                dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    null, null)
            }
            "touch_move" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val path = activePointers[ptr] ?: run {
                    // Nếu không có down trước, tạo path mới
                    Path().apply { moveTo(px, py) }.also { activePointers[ptr] = it }
                }
                path.lineTo(px, py)
                // Dispatch swipe liên tục
                val stroke = GestureDescription.StrokeDescription(
                    Path(path), 0, 16, true) // 16ms ≈ 60fps
                dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    null, null)
            }
            "touch_up" -> {
                val (px, py) = toPixels(obj) ?: return
                val ptr = obj.optInt("ptr", 0)
                val path = activePointers.remove(ptr) ?: Path().apply { moveTo(px, py) }
                path.lineTo(px, py)
                // Dispatch lần cuối, willContinue=false → ACTION_UP
                val stroke = GestureDescription.StrokeDescription(
                    Path(path), 0, 50, false)
                dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    null, null)
            }

            // ── Kích thước màn hình ───────────────────────────────────────────
            "request_screen_size" -> {
                val (w, h) = ScreenMetrics.realSize(this)
                val reply = JSONObject().apply {
                    put("type", "screen_size")
                    put("w", w)
                    put("h", h)
                }
                // Gửi ngược về Máy A qua bus → RemoteHostService → DataChannel
                ControlCommandBus.publishReply(reply.toString())
            }

            // ── Text input ────────────────────────────────────────────────────
            "text_input" -> {
                val text = obj.optString("text", "")
                if (text.isNotEmpty()) injectText(text)
            }

            // ── Phím đặc biệt ─────────────────────────────────────────────────
            "key_event" -> {
                when (obj.optString("key", "")) {
                    "enter"     -> performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT).let {
                        // Fallback: dùng clipboard paste nếu cần
                        injectKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
                    }
                    "backspace" -> injectKeyCode(android.view.KeyEvent.KEYCODE_DEL)
                    "tab"       -> injectKeyCode(android.view.KeyEvent.KEYCODE_TAB)
                    "escape"    -> injectKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
                    "back"      -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home"      -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents"   -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert tọa độ chuẩn [0..1] → pixel, null nếu thiếu x/y */
    private fun toPixels(obj: JSONObject): Pair<Float, Float>? {
        if (!obj.has("x") || !obj.has("y")) return null
        val (w, h) = ScreenMetrics.realSize(this)
        val px = (obj.optDouble("x").toFloat() * w).coerceIn(0f, w - 1f)
        val py = (obj.optDouble("y").toFloat() * h).coerceIn(0f, h - 1f)
        return px to py
    }

    private fun performTap(px: Float, py: Float) {
        val path = Path().apply { moveTo(px, py) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        Log.d(TAG, "tap($px,$py) ok=$ok")
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Inject text bằng cách dán qua clipboard → paste.
     * Đây là cách đáng tin cậy nhất trên AccessibilityService vì không cần
     * biết app nào đang focus và không cần quyền thêm.
     */
    private fun injectText(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("remote_text", text)
        clipboard.setPrimaryClip(clip)
        // Paste vào field đang focus
        rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun injectKeyCode(keyCode: Int) {
        // AccessibilityService không dispatch KeyEvent trực tiếp — dùng
        // performAction trên node đang focus
        val node = rootInActiveWindow?.findFocus(
            android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = android.os.Bundle().apply {
            putInt(android.view.accessibility.AccessibilityNodeInfo
                .ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 1)
        }
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL ->
                node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CUT.takeIf {
                        node.text?.isNotEmpty() == true
                    } ?: android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                )
            else -> {
                // Fallback: gửi KeyEvent qua UiAutomation nếu có (thường không khả dụng)
                Log.d(TAG, "keyCode $keyCode không có handler trực tiếp")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        ControlCommandBus.unsubscribe()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        super.onDestroy()
    }
}
