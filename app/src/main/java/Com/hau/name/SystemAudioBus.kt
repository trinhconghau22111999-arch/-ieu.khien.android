package Com.hau.name

import android.media.AudioRecord
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SystemAudioBus"

/**
 * Bus singleton: nhận PCM từ AudioRecord (capture âm thanh hệ thống)
 * và đẩy vào bất kỳ consumer nào đăng ký — thường là WebRTC CustomAudioDevice.
 *
 * Luồng:
 *   AudioPlaybackCaptureConfiguration
 *        → AudioRecord  (trong RemoteHostService)
 *        → SystemAudioBus.startCapture()
 *        → listener (PeerConnectionManager đăng ký qua setAudioConsumer)
 *        → WebRTC audio pipeline → Opus encode → DataChannel/RTP → Máy A
 */
object SystemAudioBus {

    /** Consumer nhận raw PCM stereo 16-bit 44100Hz */
    fun interface AudioConsumer {
        fun onPcmData(data: ShortArray, frames: Int)
    }

    @Volatile private var consumer: AudioConsumer? = null
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    /** Đăng ký consumer (PeerConnectionManager gọi khi init) */
    fun setAudioConsumer(c: AudioConsumer?) { consumer = c }

    /**
     * Bắt đầu đọc PCM từ [record] trên một thread riêng.
     * [record] đã được configure với AudioPlaybackCaptureConfiguration.
     */
    fun startCapture(record: AudioRecord) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Capture đã đang chạy, bỏ qua")
            record.release()
            return
        }
        audioRecord = record
        record.startRecording()

        captureThread = Thread({
            val buf = ShortArray(FRAMES_PER_READ * CHANNELS)
            Log.d(TAG, "Audio capture thread started")
            while (running.get()) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    consumer?.onPcmData(buf, read / CHANNELS)
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
            }
            Log.d(TAG, "Audio capture thread ended")
        }, "SystemAudioCapture").also { it.isDaemon = true; it.start() }
    }

    fun stopCapture() {
        if (!running.getAndSet(false)) return
        captureThread?.interrupt()
        captureThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        consumer = null
    }

    private const val FRAMES_PER_READ = 1024   // ~23ms @ 44100Hz
    private const val CHANNELS = 2             // stereo
}
