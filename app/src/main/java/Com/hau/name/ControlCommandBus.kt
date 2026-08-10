package Com.hau.name

/**
 * Bus nội bộ kết nối RemoteHostService ↔ InputInjectionService trong cùng process.
 *
 * THAY ĐỔI SO VỚI BẢN GỐC:
 * Thêm replyListener: InputInjectionService gửi ngược dữ liệu về RemoteHostService
 * (ví dụ: kích thước màn hình khi Máy A yêu cầu "request_screen_size").
 * RemoteHostService đăng ký replyListener và forward qua DataChannel về Máy A.
 */
object ControlCommandBus {

    // Máy B nhận lệnh từ Máy A (RemoteHostService → InputInjectionService)
    @Volatile private var listener: ((String) -> Unit)? = null

    // InputInjectionService gửi ngược về RemoteHostService → DataChannel → Máy A
    @Volatile private var replyListener: ((String) -> Unit)? = null

    fun subscribe(onCommand: (String) -> Unit) { listener = onCommand }
    fun unsubscribe() { listener = null }
    fun publish(json: String) { listener?.invoke(json) }

    fun subscribeReply(onReply: (String) -> Unit) { replyListener = onReply }
    fun unsubscribeReply() { replyListener = null }
    fun publishReply(json: String) { replyListener?.invoke(json) }
}
