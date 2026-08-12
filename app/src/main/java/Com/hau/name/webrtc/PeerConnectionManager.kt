package Com.hau.name.webrtc

import android.content.Context
import android.util.Log
import Com.hau.name.SystemAudioBus
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "PeerConnectionManager"

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
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var isReleased = false

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val adm = if (isHost) buildAudioDeviceModule() else null
        audioDeviceModule = adm

        // Ưu tiên hardware encoder (H264 HW) — nhanh gấp 5-10x software encode
        // enableIntelVp8Encoder=true, enableH264HighProfile=true
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .apply { if (adm != null) setAudioDeviceModule(adm) }
            .createPeerConnectionFactory()
    }

    private fun buildAudioDeviceModule(): JavaAudioDeviceModule {
        val adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(44100)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        SystemAudioBus.setAudioConsumer { data, frames ->
            val mono = ShortArray(frames)
            for (i in 0 until frames) {
                mono[i] = ((data[i * 2].toInt() + data[i * 2 + 1].toInt()) / 2).toShort()
            }
            try {
                adm.javaClass.getMethod("injectAudioFrame",
                    ByteArray::class.java, Int::class.java, Int::class.java)
                    .invoke(adm, shortArrayToBytes(mono), frames, 44100)
            } catch (_: Exception) {}
        }
        return adm
    }

    private fun shortArrayToBytes(arr: ShortArray): ByteArray {
        val b = ByteArray(arr.size * 2)
        for (i in arr.indices) {
            b[i * 2]     = (arr[i].toInt() and 0xFF).toByte()
            b[i * 2 + 1] = (arr[i].toInt() shr 8 and 0xFF).toByte()
        }
        return b
    }

    fun addVideoTrackAndOffer(videoSource: VideoSource, audioTrack: AudioTrack?) {
        val pc = createPeerConnection() ?: return

        val videoTrack = factory.createVideoTrack("video_track", videoSource)
        videoTrack.setEnabled(true)
        pc.addTrack(videoTrack, listOf("stream_id"))

        audioTrack?.let {
            it.setEnabled(true)
            pc.addTrack(it, listOf("stream_id"))
        }

        val dcInit = DataChannel.Init().apply { ordered = true }
        dataChannel = pc.createDataChannel("control", dcInit)
        dataChannel?.registerObserver(buildDCObserver())

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(s: SessionDescription) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                }, sdp)
                signalingClient.sendOffer(sdp.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { Log.e(TAG, "createOffer fail: $e") }
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
    }

    fun handleOffer(sdpStr: String) {
        val pc = createPeerConnection() ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription) {}
            override fun onSetSuccess() {
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(s: SessionDescription) {}
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                        }, sdp)
                        signalingClient.sendAnswer(sdp.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(e: String?) { Log.e(TAG, "createAnswer fail: $e") }
                    override fun onSetFailure(e: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote fail: $e") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdpStr))
    }

    fun handleAnswer(sdpStr: String) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { Log.e(TAG, "setAnswer fail: $e") }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdpStr))
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun sendControlCommand(json: String) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val buf = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)
        dc.send(buf)
    }

    fun reinitForReconnect(newSigClient: SignalingClient) {
        signalingClient = newSigClient
        peerConnection?.dispose()
        peerConnection = null
        dataChannel = null
    }

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

    private fun createPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Tắt CPU throttle — cho phép dùng tối đa CPU để encode nhanh
            enableCpuOveruseDetection = false
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signalingClient.sendIceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp)
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection: $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED    -> onConnected?.invoke()
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED       -> onDisconnected?.invoke()
                    else -> {}
                }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Received video track")
                    remoteSink?.let { track.addSink(it) }
                } else if (track is AudioTrack) {
                    Log.d(TAG, "Received audio track")
                    track.setEnabled(true)
                }
            }
            override fun onDataChannel(dc: DataChannel) {
                dataChannel = dc
                dc.registerObserver(buildDCObserver())
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSelectedCandidatePairChanged(e: CandidatePairChangeEvent?) {}
        }) ?: run { Log.e(TAG, "Cannot create PeerConnection"); return null }
        peerConnection = pc
        return pc
    }

    private fun buildDCObserver() = object : DataChannel.Observer {
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            onControlMessage?.invoke(String(bytes, Charsets.UTF_8))
        }
        override fun onStateChange() { Log.d(TAG, "DC state: ${dataChannel?.state()}") }
        override fun onBufferedAmountChange(amt: Long) {}
    }
}
