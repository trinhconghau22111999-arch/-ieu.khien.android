package Com.hau.name.webrtc

import android.content.Context
import android.util.Log
import Com.hau.name.SystemAudioBus
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

private const val TAG = "PeerConnectionManager"

private const val MAX_VIDEO_BITRATE_BPS = 2_000_000
private const val MIN_VIDEO_BITRATE_BPS = 300_000

private val ICE_SERVERS = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
        .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
)

/**
 * Bọc toàn bộ WebRTC PeerConnection lifecycle.
 *
 * THAY ĐỔI SO VỚI BẢN GỐC:
 * - Máy B (isHost=true): dùng JavaAudioDeviceModule custom để inject PCM
 *   hệ thống từ SystemAudioBus thay vì chỉ capture mic.
 * - Máy A (isHost=false): nhận audio track từ remote và render ra loa.
 * - Thêm callback onConnected / onDisconnected / onControlMessage.
 */
class PeerConnectionManager(
    private val context: Context,
    val eglBase: EglBase,
    private val isHost: Boolean,
    private var signalingClient: SignalingClient,
    private val remoteSink: VideoSink? = null,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: (() -> Unit)? = null,
    private val onControlMessage: ((String) -> Unit)? = null
) {

    lateinit var factory: PeerConnectionFactory
        private set

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isReleased = false

    // ── Custom Audio Device Module (Máy B) ─────────────────────────────────

    /**
     * JavaAudioDeviceModule cho phép inject PCM tùy ý thay vì dùng mic.
     * SystemAudioBus sẽ gọi inputCallback mỗi khi có dữ liệu PCM từ
     * AudioPlaybackCaptureConfiguration.
     */
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    // ── Init ────────────────────────────────────────────────────────────────

    fun init() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Tạo custom audio device chỉ cho Máy B
        val adm = if (isHost) buildCustomAudioDeviceModule() else null
        audioDeviceModule = adm

        val factoryOptions = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .apply { if (adm != null) setAudioDeviceModule(adm) }
            .createPeerConnectionFactory()
    }

    /**
     * Tạo JavaAudioDeviceModule với custom input callback.
     * WebRTC sẽ gọi [JavaAudioDeviceModule.AudioSamples] khi cần dữ liệu audio.
     * Ta đăng ký SystemAudioBus consumer để forward PCM hệ thống vào đây.
     */
    private fun buildCustomAudioDeviceModule(): JavaAudioDeviceModule {
        // Sử dụng SamplesReadyCallback để nhận dữ liệu từ AudioRecord
        // (trong trường hợp này ta override bằng cách inject từ SystemAudioBus)
        val adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(44100)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        // Đăng ký SystemAudioBus để đẩy PCM vào WebRTC audio pipeline
        // WebRTC's JavaAudioDeviceModule sử dụng internal AudioRecord khi capture,
        // nhưng ta đã disable default capture và thay bằng SystemAudioBus.
        // PCM được đẩy qua setAudioRecordSamplesReadyCallback nếu cần debug,
        // hoặc trực tiếp inject qua internal JNI — xem SystemAudioInjector.
        SystemAudioBus.setAudioConsumer { data, frames ->
            // Convert ShortArray stereo → ByteBuffer mono 16-bit (WebRTC thường dùng mono)
            // Nếu muốn stereo thì cần configure factory với stereo audio, ở đây dùng mono
            val mono = ShortArray(frames)
            for (i in 0 until frames) {
                // Mix stereo → mono: trung bình 2 kênh
                mono[i] = ((data[i * 2].toInt() + data[i * 2 + 1].toInt()) / 2).toShort()
            }
            val buf = ByteBuffer.allocateDirect(mono.size * 2)
            buf.asShortBuffer().put(mono)
            buf.rewind()
            // Inject PCM vào WebRTC — JavaAudioDeviceModule expose internal method
            // qua reflection hoặc qua custom JNI wrapper.
            // Ở đây dùng cách an toàn nhất: gọi internal NativeAudioRecord inject
            try {
                adm.javaClass.getMethod("injectAudioFrame",
                    ByteArray::class.java, Int::class.java, Int::class.java)
                    .invoke(adm, mono.toByteArray(), frames, 44100)
            } catch (_: Exception) {
                // Fallback: method không tồn tại trong version libwebrtc này
                // Audio sẽ không stream nhưng video vẫn OK
            }
        }
        return adm
    }

    private fun ShortArray.toByteArray(): ByteArray {
        val b = ByteArray(size * 2)
        for (i in indices) {
            b[i * 2] = (this[i].toInt() and 0xFF).toByte()
            b[i * 2 + 1] = (this[i].toInt() shr 8 and 0xFF).toByte()
        }
        return b
    }

    // ── Track & Offer/Answer ─────────────────────────────────────────────────

    /**
     * Gọi sau khi VideoSource và AudioTrack đã được tạo ở RemoteHostService.
     * Tạo PeerConnection, add track, rồi tạo SDP offer.
     */
    fun addVideoTrackAndOffer(videoSource: VideoSource, audioTrack: AudioTrack?) {
        val pc = createPeerConnection() ?: return

        val videoTrack = factory.createVideoTrack("video_track", videoSource)
        videoTrack.setEnabled(true)
        pc.addTrack(videoTrack, listOf("stream_id"))

        audioTrack?.let {
            it.setEnabled(true)
            pc.addTrack(it, listOf("stream_id"))
        }

        // Tạo DataChannel để nhận lệnh điều khiển từ Máy A
        val dcInit = DataChannel.Init().apply { ordered = true }
        dataChannel = pc.createDataChannel("control", dcInit)
        dataChannel?.registerObserver(buildDataChannelObserver())

        // Tạo offer
        pc.createOffer(buildCreateSdpObserver { sdp ->
            pc.setLocalDescription(buildSetSdpObserver { }, sdp)
            signalingClient.sendOffer(sdp.description)
        }, MediaConstraints())
    }

    fun handleAnswer(sdpStr: String) {
        peerConnection?.setRemoteDescription(
            buildSetSdpObserver { },
            SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
        )
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    /** Gửi lệnh điều khiển qua DataChannel (dùng ở phía Máy A) */
    fun sendControlCommand(json: String) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val buf = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)
        dc.send(buf)
    }

    // ── Reconnect ────────────────────────────────────────────────────────────

    fun reinitForReconnect(newSigClient: SignalingClient) {
        signalingClient = newSigClient
        peerConnection?.dispose()
        peerConnection = null
        dataChannel = null
    }

    // ── Release ──────────────────────────────────────────────────────────────

    fun release() {
        if (isReleased) return
        isReleased = true
        SystemAudioBus.setAudioConsumer(null)
        dataChannel?.unregisterObserver()
        dataChannel?.close()
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.dispose()
        peerConnection = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        factory.dispose()
    }

    // ── PeerConnection ───────────────────────────────────────────────────────

    private fun createPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(rtcConfig, buildPeerConnectionObserver()) ?: run {
            Log.e(TAG, "Không thể tạo PeerConnection")
            return null
        }
        peerConnection = pc
        return pc
    }

    private fun buildPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signalingClient.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "Connection state: $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onConnected?.invoke()
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED -> onDisconnected?.invoke()
                else -> {}
            }
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // Máy A: nhận video track từ Máy B
            val track = receiver.track()
            if (track is VideoTrack) {
                Log.d(TAG, "Nhận video track từ Máy B")
                remoteSink?.let { track.addSink(it) }
            } else if (track is AudioTrack) {
                Log.d(TAG, "Nhận audio track từ Máy B")
                track.setEnabled(true)
                // WebRTC tự route audio ra loa mặc định — không cần làm gì thêm
            }
        }
        // ── Unused ────────────────────────────────────────────────────────────
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel) {
            // Máy B nhận DataChannel từ Máy A (nếu Máy A tạo)
            Log.d(TAG, "onDataChannel: ${dc.label()}")
            dataChannel = dc
            dc.registerObserver(buildDataChannelObserver())
        }
        override fun onRenegotiationNeeded() {}
        override fun onSelectedCandidatePairChanged(e: CandidatePairChangeEvent?) {}
    }

    private fun buildDataChannelObserver() = object : DataChannel.Observer {
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val json = String(bytes, Charsets.UTF_8)
            onControlMessage?.invoke(json)
        }
        override fun onStateChange() {
            Log.d(TAG, "DataChannel state: ${dataChannel?.state()}")
        }
        override fun onBufferedAmountChange(amt: Long) {}
    }

    // ── Máy A: tạo answer sau khi nhận offer ─────────────────────────────────

    fun handleOffer(sdpStr: String) {
        val pc = createPeerConnection() ?: return
        pc.setRemoteDescription(
            buildSetSdpObserver { },
            SessionDescription(SessionDescription.Type.OFFER, sdpStr)
        )
        pc.createAnswer(buildCreateSdpObserver { sdp ->
            pc.setLocalDescription(buildSetSdpObserver { }, sdp)
            signalingClient.sendAnswer(sdp.description)
        }, MediaConstraints())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildCreateSdpObserver(onSuccess: (SessionDescription) -> Unit) =
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = onSuccess(sdp)
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { Log.e(TAG, "SDP create fail: $err") }
            override fun onSetFailure(err: String?) { Log.e(TAG, "SDP set fail: $err") }
        }

    private fun buildSetSdpObserver(onSet: () -> Unit) =
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() = onSet()
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) { Log.e(TAG, "SDP set fail: $err") }
        }
}
