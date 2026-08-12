package Com.hau.name.webrtc

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

private const val TAG = "SignalingClient"

/**
 * Wrap Firebase Realtime Database làm signaling server cho WebRTC.
 *
 * Schema Firebase:
 *   rooms/{roomCode}/
 *     status        : "waiting" | "connected" | "ended"
 *     offer         : SDP string (Máy B ghi)
 *     answer        : SDP string (Máy A ghi)
 *     ice_host/     : { sdpMid, sdpMLineIndex, candidate } (Máy B ghi)
 *     ice_client/   : { sdpMid, sdpMLineIndex, candidate } (Máy A ghi)
 *
 * THAY ĐỔI:
 * - Thêm startListening() để Máy A bắt đầu lắng nghe offer sau khi init.
 * - Thêm clearForReconnect() để xóa offer/answer cũ khi reconnect.
 * - Thêm setWaiting() để Máy B reset trạng thái phòng sau reconnect.
 * - markEnded() / release() tách bạch.
 */
class SignalingClient(
    private val roomCode: String,
    private val isHost: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onRemoteDisconnected()
    }

    private val db = FirebaseDatabase.getInstance("https://checkinonline-785d5-default-rtdb.asia-southeast1.firebasedatabase.app").reference.child("rooms").child(roomCode)
    private val listeners = mutableListOf<Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()
    private var released = false

    init {
        if (isHost) {
            // Máy B: lắng nghe answer và ICE từ Máy A ngay khi tạo
            listenForAnswer()
            listenForIce(fromHost = false)
            listenForStatus()
        }
        // Máy A: gọi startListening() sau khi PeerConnection đã init
    }

    /** Máy A gọi sau init để bắt đầu nhận offer */
    fun startListening() {
        if (isHost) return
        listenForOffer()
        listenForIce(fromHost = true)
        listenForStatus()
    }

    // ── Ghi ──────────────────────────────────────────────────────────────────

    fun sendOffer(sdp: String) {
        db.child("offer").setValue(sdp)
        db.child("status").setValue("waiting")
    }

    fun sendAnswer(sdp: String) {
        db.child("answer").setValue(sdp)
        db.child("status").setValue("connected")
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val key = if (isHost) "ice_host" else "ice_client"
        db.child(key).push().setValue(
            mapOf("sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex, "candidate" to candidate)
        )
    }

    fun markEnded() {
        if (!released) db.child("status").setValue("ended")
    }

    /** Reset phòng để chờ kết nối lại (Máy B gọi sau reconnect) */
    fun setWaiting() {
        db.child("answer").removeValue()
        db.child("ice_client").removeValue()
        db.child("status").setValue("waiting")
    }

    /** Xóa dữ liệu offer/answer/ICE cũ trước khi tạo offer mới */
    fun clearForReconnect() {
        db.child("offer").removeValue()
        db.child("answer").removeValue()
        db.child("ice_host").removeValue()
        db.child("ice_client").removeValue()
    }

    fun release() {
        released = true
        listeners.forEach { (ref, l) -> ref.removeEventListener(l) }
        listeners.clear()
    }

    // ── Lắng nghe ────────────────────────────────────────────────────────────

    private fun listenForOffer() {
        val ref = db.child("offer")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val sdp = snap.getValue(String::class.java) ?: return
                Log.d(TAG, "Nhận offer")
                listener.onOfferReceived(sdp)
            }
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "offer listen error: $e") }
        }
        ref.addValueEventListener(l)
        listeners.add(ref to l)
    }

    private fun listenForAnswer() {
        val ref = db.child("answer")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val sdp = snap.getValue(String::class.java) ?: return
                Log.d(TAG, "Nhận answer")
                listener.onAnswerReceived(sdp)
            }
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "answer listen error: $e") }
        }
        ref.addValueEventListener(l)
        listeners.add(ref to l)
    }

    private fun listenForIce(fromHost: Boolean) {
        val key = if (fromHost) "ice_host" else "ice_client"
        val ref = db.child(key)
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                for (child in snap.children) {
                    val mid = child.child("sdpMid").getValue(String::class.java) ?: continue
                    val idx = child.child("sdpMLineIndex").getValue(Int::class.java) ?: continue
                    val cand = child.child("candidate").getValue(String::class.java) ?: continue
                    listener.onIceCandidateReceived(mid, idx, cand)
                }
            }
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "ice listen error: $e") }
        }
        ref.addValueEventListener(l)
        listeners.add(ref to l)
    }

    private fun listenForStatus() {
        val ref = db.child("status")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (snap.getValue(String::class.java) == "ended") {
                    Log.d(TAG, "Phòng kết thúc")
                    listener.onRemoteDisconnected()
                }
            }
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "status listen error: $e") }
        }
        ref.addValueEventListener(l)
        listeners.add(ref to l)
    }
}
