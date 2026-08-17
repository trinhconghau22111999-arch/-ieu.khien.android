package Com.hau.name

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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

class ControllerActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var layoutCodeEntry: LinearLayout
    private lateinit var editPairingCode: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnLastServer: View
    private lateinit var textLastCode: TextView
    private lateinit var remoteViewContainer: View
    private lateinit var surfaceView: SurfaceViewRenderer
        private lateinit var btnToggleKeyboard: Button
    private lateinit var layoutKeyboardBar: LinearLayout
    private lateinit var editRemoteText: EditText
    private lateinit var btnKeyTab: Button
    private lateinit var btnKeyBackspace: Button
    private lateinit var btnKeyEsc: Button
    private lateinit var btnKeyEnter: Button

    // ── WebRTC ────────────────────────────────────────────────────────────────
    private val eglBase: EglBase = EglBase.create()
    private var peerConnectionManager: PeerConnectionManager? = null
    private var signalingClient: SignalingClient? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences
    private var isConnected = false
    private var videoRect = VideoRect(0f, 0f, 0f, 0f)
    private var remoteW = 0; private var remoteH = 0

    // Fix lỗi gõ phím: throttle text input
    private var lastSentText = ""

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

    private fun bindViews() {
        layoutCodeEntry     = findViewById(R.id.layout_code_entry)
        editPairingCode     = findViewById(R.id.edit_pairing_code)
        btnConnect          = findViewById(R.id.btn_connect)
        btnLastServer       = findViewById(R.id.btn_last_server)
        textLastCode        = findViewById(R.id.text_last_code)
        remoteViewContainer = findViewById(R.id.remote_view_container)
        surfaceView         = findViewById(R.id.surface_view)
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
        // SCALE_ASPECT_FIT: giữ đúng tỉ lệ gốc của Máy B, viền đen phần dư
        // Đây là cách duy nhất để tọa độ cảm ứng khớp hoàn toàn với hình
        surfaceView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    }

    // ── Fix 2: Ẩn status bar + navigation bar (toàn màn hình thật sự) ────────
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = vuốt từ cạnh mới hiện, tự ẩn sau 3s
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ── Kết nối lại mã cũ ────────────────────────────────────────────────────
    private fun setupLastServerButton() {
        val lastCode = prefs.getString("last_room_code", null)
        if (lastCode != null) {
            btnLastServer.visibility = View.VISIBLE
            textLastCode.text = lastCode
            btnLastServer.setOnClickListener { connectTo(lastCode) }
        } else {
            btnLastServer.visibility = View.GONE
        }
    }

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

    private fun connectTo(code: String) {
        if (isConnected) return
        prefs.edit().putString("last_room_code", code).apply()

        // Tắt battery optimization để Firebase không bị kill trên máy yếu
        requestIgnoreBatteryOptimization()

        // Hiện màn hình remote ngay với text "Đang kết nối..." trên surfaceView
        layoutCodeEntry.visibility = View.GONE
        remoteViewContainer.visibility = View.VISIBLE
        btnToggleKeyboard.visibility = View.GONE

        val sig = SignalingClient(
            roomCode = code, isHost = false,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) { peerConnectionManager?.handleOffer(sdp) }
                override fun onAnswerReceived(sdp: String) {}
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() = runOnUiThread { disconnect() }
            }
        )
        signalingClient = sig

        val pcm = PeerConnectionManager(
            context = this, eglBase = eglBase, isHost = false,
            signalingClient = sig, remoteSink = surfaceView,
            onConnected = { runOnUiThread { onWebRtcConnected() } },
            onDisconnected = { runOnUiThread { disconnect() } },
            onControlMessage = { json -> handleIncomingMessage(json) }
        )
        peerConnectionManager = pcm
        pcm.init()
        sig.startListening()
    }

    private fun onWebRtcConnected() {
        isConnected = true
        layoutCodeEntry.visibility = View.GONE
        remoteViewContainer.visibility = View.VISIBLE

        // Giữ WiFi HIGH PERF khi đang điều khiển
        val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        wifiLock = wm.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteAssist:ControllerWifi")
        wifiLock?.acquire()

        // Ẩn hoàn toàn nút Ngắt — dùng nút bàn phím thay thế
        btnToggleKeyboard.visibility = View.GONE

        // Ẩn status bar
        hideSystemUI()

        // Chờ surfaceView có kích thước thật rồi mới setup touch
        surfaceView.post {
            setupTouchHandler()
        }

        sendCommand(JSONObject().apply { put("type", "request_screen_size") })
    }

    // ── Touch: hiện/ẩn nút khi chạm góc ─────────────────────────────────────
    private val hideControlsRunnable = Runnable {
        btnToggleKeyboard.visibility = View.GONE
    }
    private val handler = Handler(Looper.getMainLooper())

    private fun showControlsTemporarily() {
        btnToggleKeyboard.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000) // ẩn sau 3 giây
    }

    // ── Touch handler ─────────────────────────────────────────────────────────
    private fun setupTouchHandler() {
        surfaceView.setOnTouchListener { _, event ->
            if (!isConnected) return@setOnTouchListener false

            // Chạm góc trên phải (10% màn hình) → hiện nút điều khiển 3 giây
            if (event.action == MotionEvent.ACTION_DOWN) {
                val isTopRight = event.x > surfaceView.width * 0.8f
                        && event.y < surfaceView.height * 0.15f
                if (isTopRight) {
                    showControlsTemporarily()
                    return@setOnTouchListener true
                }
            }

            updateVideoRect()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val (nx, ny) = normalize(event.x, event.y)
                    sendCommand(JSONObject().apply {
                        put("type", "touch_down"); put("x", nx); put("y", ny)
                        put("ptr", event.actionIndex)
                    })
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gửi TẤT CẢ historical points → vuốt mượt, không bị giật
                    for (i in 0 until event.pointerCount) {
                        val histCount = event.getHistorySize()
                        for (h in 0 until histCount) {
                            val (nx, ny) = normalize(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                            sendCommand(JSONObject().apply {
                                put("type", "touch_move"); put("x", nx); put("y", ny); put("ptr", i)
                            })
                        }
                        // Điểm hiện tại
                        val (nx, ny) = normalize(event.getX(i), event.getY(i))
                        sendCommand(JSONObject().apply {
                            put("type", "touch_move"); put("x", nx); put("y", ny); put("ptr", i)
                        })
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val (nx, ny) = normalize(event.x, event.y)
                    sendCommand(JSONObject().apply {
                        put("type", "touch_up"); put("x", nx); put("y", ny)
                        put("ptr", event.actionIndex)
                    })
                }
            }
            true
        }
    }

    private fun updateVideoRect() {
        val vw = surfaceView.width.toFloat()
        val vh = surfaceView.height.toFloat()
        if (remoteW == 0 || remoteH == 0 || vw == 0f || vh == 0f) {
            videoRect = VideoRect(0f, 0f, vw, vh)
            return
        }
        // Tính chính xác vùng video thật trên SurfaceView
        // SCALE_ASPECT_FIT: scale đều 2 chiều, giữ đúng tỉ lệ, viền đen phần dư
        val scaleX = vw / remoteW.toFloat()
        val scaleY = vh / remoteH.toFloat()
        val scale = minOf(scaleX, scaleY)  // Lấy scale nhỏ hơn để fit hoàn toàn
        val drawW = remoteW * scale
        val drawH = remoteH * scale
        val left = (vw - drawW) / 2f   // Căn giữa ngang
        val top  = (vh - drawH) / 2f   // Căn giữa dọc
        videoRect = VideoRect(left, top, left + drawW, top + drawH)
    }

    private fun normalize(rawX: Float, rawY: Float): Pair<Float, Float> {
        val cx = rawX.coerceIn(videoRect.left, videoRect.right)
        val cy = rawY.coerceIn(videoRect.top, videoRect.bottom)
        return Pair(
            ((cx - videoRect.left) / (videoRect.right - videoRect.left)).coerceIn(0f, 1f),
            ((cy - videoRect.top) / (videoRect.bottom - videoRect.top)).coerceIn(0f, 1f)
        )
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "screen_size", "screen_orientation" -> {
                    remoteW = obj.getInt("w"); remoteH = obj.getInt("h")
                    val isLandscape = obj.optBoolean("landscape", remoteW > remoteH)
                    runOnUiThread {
                        // Xoay màn hình Máy A theo chiều của Máy B
                        requestedOrientation = if (isLandscape)
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        updateVideoRect()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendCommand(json: JSONObject) {
        peerConnectionManager?.sendControlCommand(json.toString())
    }

    // ── Fix 4: Keyboard — chỉ gửi text MỚI thêm vào, không gửi lại toàn bộ ──
    private fun setupKeyboard() {
        // Bấm 1 lần: mở/đóng bàn phím
        btnToggleKeyboard.setOnClickListener {
            val show = layoutKeyboardBar.visibility != View.VISIBLE
            layoutKeyboardBar.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                lastSentText = ""
                editRemoteText.setText("")
            }
        }
        // Bấm giữ 2 giây: ngắt kết nối
        btnToggleKeyboard.setOnLongClickListener {
            android.widget.Toast.makeText(this, "Đã ngắt kết nối", android.widget.Toast.LENGTH_SHORT).show()
            disconnect()
            true
        }

        // Theo dõi từng ký tự thêm vào — chỉ gửi phần MỚI
        editRemoteText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val current = s?.toString() ?: ""
                if (current.length > lastSentText.length) {
                    // Có ký tự mới thêm vào
                    val newChars = current.substring(lastSentText.length)
                    sendCommand(JSONObject().apply {
                        put("type", "text_input"); put("text", newChars)
                    })
                } else if (current.length < lastSentText.length) {
                    // Người dùng bấm backspace trong ô nhập
                    sendCommand(JSONObject().apply { put("type", "key_event"); put("key", "backspace") })
                }
                lastSentText = current
            }
        })

        btnKeyTab.setOnClickListener       { sendKey("tab") }
        btnKeyBackspace.setOnClickListener { sendKey("backspace") }
        btnKeyEsc.setOnClickListener       { sendKey("escape") }
        btnKeyEnter.setOnClickListener     {
            sendKey("enter")
            // Xóa ô nhập sau khi Enter
            lastSentText = ""; editRemoteText.setText("")
        }
    }

    private fun sendKey(key: String) {
        sendCommand(JSONObject().apply { put("type", "key_event"); put("key", key) })
    }

    private fun setupDisconnect() { /* nút ngắt đã xóa, dùng long press bàn phím */ }

    private fun requestIgnoreBatteryOptimization() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    try { startActivity(intent) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun disconnect() {
        isConnected = false
        handler.removeCallbacks(hideControlsRunnable)
        try { wifiLock?.release(); wifiLock = null } catch (_: Exception) {}
        signalingClient?.release(); signalingClient = null
        peerConnectionManager?.release(); peerConnectionManager = null
        remoteViewContainer.visibility = View.GONE
        layoutCodeEntry.visibility = View.VISIBLE
        showSystemUI()
        // Trả về auto orientation khi ngắt
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setupLastServerButton()
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isConnected) hideSystemUI()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideControlsRunnable)
        disconnect()
        surfaceView.release()
        eglBase.release()
        super.onDestroy()
    }

    private data class VideoRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
