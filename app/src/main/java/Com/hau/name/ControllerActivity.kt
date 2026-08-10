package Com.hau.name

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import kotlin.math.roundToInt

/**
 * Máy A (máy tính bảng — điều khiển).
 *
 * THAY ĐỔI SO VỚI BẢN GỐC:
 * 1. Lưu mã gần nhất vào SharedPreferences.
 *    Khi mở app: nếu có mã cũ thì hiện nút "Kết nối lại: XXXXXX" — 1 bấm vào luôn.
 * 2. Sau khi kết nối: nhận audio track qua WebRTC — tự phát ra loa (không cần
 *    làm gì thêm, WebRTC route tự động).
 * 3. Touch handler tính tọa độ chuẩn dựa trên videoRect (trừ viền đen letterbox)
 *    để tránh lệch khi màn hình bảng khác tỉ lệ điện thoại.
 * 4. Chuyển landscape tự động khi kết nối, trở về portrait khi ngắt.
 */
class ControllerActivity : AppCompatActivity() {

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var layoutCodeEntry: LinearLayout
    private lateinit var editPairingCode: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnLastServer: Button       // NÚT MỚI: kết nối lại mã cũ
    private lateinit var textLastCode: TextView      // Hiện mã cũ trong nút
    private lateinit var remoteViewContainer: View
    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var btnDisconnect: Button
    private lateinit var btnToggleKeyboard: Button
    private lateinit var layoutKeyboardBar: LinearLayout
    private lateinit var editRemoteText: EditText
    private lateinit var btnKeyTab: Button
    private lateinit var btnKeyBackspace: Button
    private lateinit var btnKeyEsc: Button
    private lateinit var btnKeyEnter: Button

    // ── WebRTC ───────────────────────────────────────────────────────────────
    private val eglBase: EglBase = EglBase.create()
    private var peerConnectionManager: PeerConnectionManager? = null
    private var signalingClient: SignalingClient? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences
    private var isConnected = false

    // Vùng video thực sự trên màn hình (để tính toán tọa độ touch)
    private var videoRect = VideoRect(0f, 0f, 0f, 0f)
    // Kích thước gốc của màn hình Máy B (nhận qua DataChannel sau khi kết nối)
    private var remoteW = 0; private var remoteH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)

        prefs = getSharedPreferences("remote_assist", MODE_PRIVATE)

        bindViews()
        setupSurface()
        setupLastServerButton()
        setupConnectButton()
        setupDisconnect()
        setupKeyboard()
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private fun bindViews() {
        layoutCodeEntry     = findViewById(R.id.layout_code_entry)
        editPairingCode     = findViewById(R.id.edit_pairing_code)
        btnConnect          = findViewById(R.id.btn_connect)
        btnLastServer       = findViewById(R.id.btn_last_server)
        textLastCode        = findViewById(R.id.text_last_code)
        remoteViewContainer = findViewById(R.id.remote_view_container)
        surfaceView         = findViewById(R.id.surface_view)
        btnDisconnect       = findViewById(R.id.btn_disconnect)
        btnToggleKeyboard   = findViewById(R.id.btn_toggle_keyboard)
        layoutKeyboardBar   = findViewById(R.id.layout_keyboard_bar)
        editRemoteText      = findViewById(R.id.edit_remote_text)
        btnKeyTab           = findViewById(R.id.btn_key_tab)
        btnKeyBackspace     = findViewById(R.id.btn_key_backspace)
        btnKeyEsc           = findViewById(R.id.btn_key_esc)
        btnKeyEnter         = findViewById(R.id.btn_key_enter)
    }

    private fun setupSurface() {
        surfaceView.init(eglBase.eglBaseContext, null)
        surfaceView.setEnableHardwareScaler(true)
        surfaceView.setMirror(false)
    }

    // ── Nút kết nối lại mã cũ ────────────────────────────────────────────────

    private fun setupLastServerButton() {
        val lastCode = prefs.getString("last_room_code", null)
        if (lastCode != null) {
            btnLastServer.visibility = View.VISIBLE
            textLastCode.text = lastCode
            btnLastServer.setOnClickListener {
                connectTo(lastCode)
            }
        } else {
            btnLastServer.visibility = View.GONE
        }
    }

    // ── Kết nối bằng mã nhập tay ─────────────────────────────────────────────

    private fun setupConnectButton() {
        btnConnect.setOnClickListener {
            val code = editPairingCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "Mã phải đúng 6 số", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectTo(code)
        }
    }

    // ── Core kết nối ─────────────────────────────────────────────────────────

    private fun connectTo(code: String) {
        if (isConnected) return

        // Lưu mã vào prefs ngay để hiện ở lần sau
        prefs.edit().putString("last_room_code", code).apply()

        val sig = SignalingClient(
            roomCode = code,
            isHost = false,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {
                    peerConnectionManager?.handleOffer(sdp)
                }
                override fun onAnswerReceived(sdp: String) {}
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() = runOnUiThread { disconnect() }
            }
        )
        signalingClient = sig

        val pcm = PeerConnectionManager(
            context = this,
            eglBase = eglBase,
            isHost = false,
            signalingClient = sig,
            remoteSink = surfaceView,
            onConnected = { runOnUiThread { onWebRtcConnected() } },
            onDisconnected = { runOnUiThread { disconnect() } },
            onControlMessage = { json -> handleIncomingMessage(json) }
        )
        peerConnectionManager = pcm
        pcm.init()
        // Máy A không gọi addVideoTrackAndOffer — chỉ chờ offer từ Máy B
        // (SignalingClient sẽ gọi onOfferReceived → pcm.handleOffer)
        sig.startListening()
    }

    // ── Sau khi WebRTC connected ──────────────────────────────────────────────

    private fun onWebRtcConnected() {
        isConnected = true
        layoutCodeEntry.visibility = View.GONE
        remoteViewContainer.visibility = View.VISIBLE
        lockLandscape()
        setupTouchHandler()
        // Yêu cầu Máy B gửi kích thước màn hình
        sendCommand(JSONObject().apply {
            put("type", "request_screen_size")
        })
    }

    // ── Touch → lệnh điều khiển ───────────────────────────────────────────────

    private fun setupTouchHandler() {
        surfaceView.setOnTouchListener { _, event ->
            if (!isConnected) return@setOnTouchListener false
            // Cập nhật videoRect mỗi lần layout thay đổi
            updateVideoRect()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val (nx, ny) = normalize(event.x, event.y)
                    sendCommand(JSONObject().apply {
                        put("type", "touch_down")
                        put("x", nx); put("y", ny)
                        put("ptr", event.actionIndex)
                    })
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gửi tất cả pointer đang di chuyển
                    for (i in 0 until event.pointerCount) {
                        val (nx, ny) = normalize(event.getX(i), event.getY(i))
                        sendCommand(JSONObject().apply {
                            put("type", "touch_move")
                            put("x", nx); put("y", ny)
                            put("ptr", i)
                        })
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val (nx, ny) = normalize(event.x, event.y)
                    sendCommand(JSONObject().apply {
                        put("type", "touch_up")
                        put("x", nx); put("y", ny)
                        put("ptr", event.actionIndex)
                    })
                }
            }
            true
        }
    }

    /**
     * Tính vùng video thực trên SurfaceView (letterbox trừ viền đen).
     * SurfaceViewRenderer tự thêm viền đen khi tỉ lệ khác nhau.
     */
    private fun updateVideoRect() {
        if (remoteW == 0 || remoteH == 0) {
            // Chưa biết kích thước remote → dùng toàn màn hình tạm
            videoRect = VideoRect(0f, 0f, surfaceView.width.toFloat(), surfaceView.height.toFloat())
            return
        }
        val vw = surfaceView.width.toFloat()
        val vh = surfaceView.height.toFloat()
        val scaleX = vw / remoteW
        val scaleY = vh / remoteH
        val scale = minOf(scaleX, scaleY)
        val drawW = remoteW * scale
        val drawH = remoteH * scale
        val left = (vw - drawW) / 2f
        val top = (vh - drawH) / 2f
        videoRect = VideoRect(left, top, left + drawW, top + drawH)
    }

    /**
     * Map tọa độ pixel trên SurfaceView → tọa độ chuẩn [0..1] trên màn hình Máy B.
     * Trừ đi offset letterbox trước khi chia.
     */
    private fun normalize(rawX: Float, rawY: Float): Pair<Float, Float> {
        val clampedX = rawX.coerceIn(videoRect.left, videoRect.right)
        val clampedY = rawY.coerceIn(videoRect.top, videoRect.bottom)
        val nx = ((clampedX - videoRect.left) / (videoRect.right - videoRect.left))
            .coerceIn(0f, 1f)
        val ny = ((clampedY - videoRect.top) / (videoRect.bottom - videoRect.top))
            .coerceIn(0f, 1f)
        return Pair(nx, ny)
    }

    // ── Nhận message từ Máy B ────────────────────────────────────────────────

    private fun handleIncomingMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "screen_size" -> {
                    remoteW = obj.getInt("w")
                    remoteH = obj.getInt("h")
                    runOnUiThread { updateVideoRect() }
                }
            }
        } catch (_: Exception) {}
    }

    // ── Gửi lệnh ─────────────────────────────────────────────────────────────

    private fun sendCommand(json: JSONObject) {
        peerConnectionManager?.sendControlCommand(json.toString())
    }

    // ── Keyboard overlay ──────────────────────────────────────────────────────

    private fun setupKeyboard() {
        btnToggleKeyboard.setOnClickListener {
            layoutKeyboardBar.visibility =
                if (layoutKeyboardBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        editRemoteText.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString()
            if (text.isNotEmpty()) {
                sendCommand(JSONObject().apply {
                    put("type", "text_input"); put("text", text)
                })
                v.setText("")
            }
            true
        }
        btnKeyTab.setOnClickListener { sendKey("tab") }
        btnKeyBackspace.setOnClickListener { sendKey("backspace") }
        btnKeyEsc.setOnClickListener { sendKey("escape") }
        btnKeyEnter.setOnClickListener { sendKey("enter") }
    }

    private fun sendKey(key: String) {
        sendCommand(JSONObject().apply {
            put("type", "key_event"); put("key", key)
        })
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    private fun setupDisconnect() {
        btnDisconnect.setOnClickListener { disconnect() }
    }

    private fun disconnect() {
        isConnected = false
        signalingClient?.release(); signalingClient = null
        peerConnectionManager?.release(); peerConnectionManager = null
        remoteViewContainer.visibility = View.GONE
        layoutCodeEntry.visibility = View.VISIBLE
        unlockOrientation()
        // Cập nhật lại nút last server (mã vừa dùng đã được lưu)
        setupLastServerButton()
    }

    // ── Orientation ───────────────────────────────────────────────────────────

    private fun lockLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun unlockOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        disconnect()
        surfaceView.release()
        eglBase.release()
        super.onDestroy()
    }

    // ── Data class ────────────────────────────────────────────────────────────

    private data class VideoRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
