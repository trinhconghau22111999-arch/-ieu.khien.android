package Com.hau.name

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

private const val TAG = "RemoteHostService"

/**
 * Foreground Service trên Máy B (điện thoại).
 *
 * THAY ĐỔI SO VỚI BẢN GỐC:
 * 1. Thông báo PERSISTENT với 2 nút hành động:
 *    - "Tạo mã mới": gửi broadcast về ConsentActivity để đổi mã
 *    - "Ngắt kết nối": dừng service
 * 2. Capture audio hệ thống qua AudioPlaybackCaptureConfiguration
 *    (Android 10+) và stream qua WebRTC audio track.
 * 3. isRunning companion để ConsentActivity biết service có đang chạy không.
 */
class RemoteHostService : Service() {

    private var signalingClient: SignalingClient? = null
    private var peerConnectionManager: PeerConnectionManager? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val eglBase: EglBase = EglBase.create()
    private var roomCode: String? = null
    private var mediaProjection: MediaProjection? = null
    private var isCleanedUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Giữ CPU chạy full khi màn hình tắt — tránh throttle encode
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "RemoteAssist:WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 giờ max

        // Giữ WiFi HIGH PERFORMANCE — tắt power saving mode của WiFi chip
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wm.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteAssist:WifiLock")
        wifiLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanup(); stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEW_CODE -> {
                val ui = Intent(this, ConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    action = ACTION_NEW_CODE
                }
                startActivity(ui)
                return START_NOT_STICKY
            }
        }

        roomCode = intent?.getStringExtra(EXTRA_ROOM_CODE)
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}")
            // Fallback không có type
            try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
        }

        if (roomCode != null && projectionData != null) {
            initWebRTC(roomCode!!, projectionData)
        } else {
            Log.e(TAG, "Thiếu roomCode hoặc projectionData")
            stopSelf()
        }
        // START_NOT_STICKY: không tự restart vì eglBase không thể tái sử dụng sau release
        return START_NOT_STICKY
    }

    private fun initWebRTC(code: String, projectionData: Intent) {
        val sigClient = SignalingClient(roomCode = code, isHost = true, listener = buildHostListener())
        signalingClient = sigClient

        // Nhận reply từ InputInjectionService (screen_size, orientation) → gửi về Máy A
        ControlCommandBus.subscribeReply { json ->
            peerConnectionManager?.sendControlCommand(json)
        }

        val pcm = PeerConnectionManager(
            context = this,
            eglBase = eglBase,
            isHost = true,
            signalingClient = sigClient,
            remoteSink = null,
            onConnected = {
                Log.d(TAG, "WebRTC connected!")
                // Gửi orientation lại sau khi DataChannel mở — đảm bảo Máy A nhận được
                val (w, h) = ScreenMetrics.realSize(this)
                sendOrientationToClient(w, h)
            },
            onDisconnected = {
                Log.d(TAG, "Máy A ngắt kết nối — chờ kết nối lại với mã $code")
                prepareForReconnect(code)
            },
            onControlMessage = { json -> ControlCommandBus.publish(json) }
        )
        peerConnectionManager = pcm
        pcm.init()

        // ── VIDEO ──────────────────────────────────────────────────────────────
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = pcm.factory.createVideoSource(true)

        screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                cleanup(); stopSelf()
            }
        })
        screenCapturer!!.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)

        val (rawW, rawH) = ScreenMetrics.realSize(this)
        val (capW, capH) = scaledCaptureSize(rawW, rawH)
        screenCapturer!!.startCapture(capW, capH, CAPTURE_FPS)

        // Nâng priority thread encode lên cao nhất
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

        // Gửi orientation ban đầu
        sendOrientationToClient(rawW, rawH)

        // ── AUDIO ──────────────────────────────────────────────────────────────
        audioSource = pcm.factory.createAudioSource(org.webrtc.MediaConstraints().apply {
            mandatory.add(org.webrtc.MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(org.webrtc.MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(org.webrtc.MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        })
        audioTrack = pcm.factory.createAudioTrack("audio_track", audioSource)

        pcm.addVideoTrackAndOffer(videoSource!!, audioTrack)

        // Khởi động capture audio hệ thống (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startSystemAudioCapture(projectionData)
        }
    }

    /**
     * Capture audio phát ra từ toàn bộ ứng dụng (không phải mic) bằng
     * AudioPlaybackCaptureConfiguration. PCM được đẩy vào custom AudioDeviceModule
     * mà PeerConnectionManager đã đăng ký — từ đó WebRTC sẽ đóng gói thành Opus
     * và gửi qua peer connection.
     *
     * Yêu cầu: Android 10+ (API 29), app phải có quyền RECORD_AUDIO.
     */
    private fun startSystemAudioCapture(projectionData: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            // Tạo MediaProjection MỚI từ projectionData cho audio capture
            // (không tái dùng cái đã dùng cho ScreenCapturer vì đã consumed)
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            // Dùng intent clone để tránh consumed
            val mp = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK,
                projectionData.clone() as android.content.Intent)
            mediaProjection = mp

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 4)
                .build()

            // Đẩy PCM vào AudioDeviceModule của WebRTC thông qua bus nội bộ
            SystemAudioBus.startCapture(audioRecord)

        } catch (e: Exception) {
            Log.e(TAG, "Không thể bắt đầu capture audio hệ thống: ${e.message}")
            // Không crash — video vẫn stream bình thường, chỉ mất audio
        }
    }

    private fun buildHostListener() = object : SignalingClient.Listener {
        override fun onOfferReceived(sdp: String) {}
        override fun onAnswerReceived(sdp: String) {
            peerConnectionManager?.handleAnswer(sdp)
        }
        override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
        }
        override fun onRemoteDisconnected() {
            Log.d(TAG, "status=ended — kết thúc phiên")
            cleanup(); stopSelf()
        }
    }

    private fun prepareForReconnect(code: String) {
        val vs = videoSource ?: run { cleanup(); stopSelf(); return }
        val at = audioTrack
        signalingClient?.clearForReconnect()
        signalingClient?.release()
        val newSig = SignalingClient(roomCode = code, isHost = true, listener = buildHostListener())
        signalingClient = newSig
        // reinitForReconnect xóa PeerConnection cũ trước
        peerConnectionManager?.reinitForReconnect(newSig)
        // Delay nhỏ để đảm bảo PC cũ đã dispose hoàn toàn
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            peerConnectionManager?.addVideoTrackAndOffer(vs, at)
            newSig.setWaiting()
        }, 500)
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): android.app.Notification {
        val channelId = "remote_assist_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(
                channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW))
        }

        // Nút "Ngắt kết nối"
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RemoteHostService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Nút "Tạo mã mới"
        val newCodeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RemoteHostService::class.java).apply { action = ACTION_NEW_CODE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Tap vào thông báo → mở ConsentActivity
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, ConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val code = roomCode ?: prefs().getString("room_code_persistent", "------") ?: "------"

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Assist đang chạy  •  $code")
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)          // PERSISTENT — không vuốt tắt được
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_add, "Mã mới", newCodeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ngắt", stopIntent)
            .build()
    }

    private fun prefs() = getSharedPreferences("remote_assist", MODE_PRIVATE)

    // ── Gửi orientation về Máy A ─────────────────────────────────────────────
    private fun sendOrientationToClient(w: Int, h: Int) {
        val isLandscape = w > h
        val reply = org.json.JSONObject().apply {
            put("type", "screen_orientation")
            put("landscape", isLandscape)
            put("w", w)
            put("h", h)
        }
        ControlCommandBus.publishReply(reply.toString())
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true
        isRunning = false

        // Release wake/wifi locks
        try { wakeLock?.release(); wakeLock = null } catch (_: Exception) {}
        try { wifiLock?.release(); wifiLock = null } catch (_: Exception) {}

        // Unsubscribe bus để tránh memory leak
        ControlCommandBus.unsubscribe()
        ControlCommandBus.unsubscribeReply()

        SystemAudioBus.stopCapture()
        screenCapturer?.stopCapture()
        screenCapturer?.dispose(); screenCapturer = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        videoSource?.dispose(); videoSource = null
        surfaceTextureHelper?.dispose(); surfaceTextureHelper = null
        mediaProjection?.stop(); mediaProjection = null
        signalingClient?.markEnded()
        signalingClient?.release(); signalingClient = null
        peerConnectionManager?.release(); peerConnectionManager = null
        eglBase.release()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun scaledCaptureSize(rawW: Int, rawH: Int): Pair<Int, Int> {
        val long = maxOf(rawW, rawH)
        if (long <= CAPTURE_MAX_DIM) return (rawW and 1.inv()) to (rawH and 1.inv())
        val s = CAPTURE_MAX_DIM.toFloat() / long
        return ((rawW * s).toInt() and 1.inv()) to ((rawH * s).toInt() and 1.inv())
    }

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val ACTION_STOP = "action_stop_sharing"
        const val ACTION_NEW_CODE = "action_new_code"

        private const val NOTIF_ID = 42
        // 720px + 20fps: hình nét hơn 600px, FPS thấp hơn giúp encode nhanh → ít lag
        // Bitrate cao (8Mbps) đảm bảo mỗi frame đều sắc nét dù FPS thấp hơn
        private const val CAPTURE_MAX_DIM = 720
        private const val CAPTURE_FPS = 15
        const val AUDIO_SAMPLE_RATE = 44100

        /** ConsentActivity dùng để biết có cần xin lại quyền màn hình không */
        @Volatile var isRunning = false
    }
}
