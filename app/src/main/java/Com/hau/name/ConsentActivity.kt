package Com.hau.name

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Máy B (điện thoại — bị điều khiển).
 *
 * THAY ĐỔI SO VỚI BẢN GỐC:
 * 1. Mã 6 số được TẠO 1 LẦN DUY NHẤT khi lần đầu mở app, lưu vĩnh viễn vào SharedPreferences.
 *    Người dùng có thể bấm "Tạo mã mới" để đổi — mã cũ trên Firebase sẽ bị đóng lại.
 * 2. Màn hình hiển thị mã ngay khi vào (không cần tick checkbox mỗi lần) nếu đã có mã cũ.
 * 3. Foreground service luôn chạy ngầm sau khi cấp quyền lần đầu — thông báo persistent
 *    với nút "Tạo mã mới" và "Ngắt kết nối" ngay trên thanh thông báo.
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var checkboxConsent: CheckBox
    private lateinit var btnGenerateCode: Button
    private lateinit var btnNewCode: Button
    private lateinit var layoutPairingCode: View
    private lateinit var textPairingCode: TextView
    private lateinit var btnEndSession: Button
    private lateinit var bannerAccessibility: View
    private lateinit var btnOpenAccessibility: Button
    private lateinit var layoutConsent: View

    private lateinit var prefs: SharedPreferences

    // Mã hiện tại đang hiển thị (có thể lấy từ prefs hoặc mới tạo)
    private var roomCode: String? = null
    // Đang chờ kết quả MediaProjection để bắt đầu service
    private var pendingNewCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)

        prefs = getSharedPreferences("remote_assist", MODE_PRIVATE)

        checkboxConsent = findViewById(R.id.checkbox_consent)
        btnGenerateCode = findViewById(R.id.btn_generate_code)
        btnNewCode = findViewById(R.id.btn_new_code)
        layoutPairingCode = findViewById(R.id.layout_pairing_code)
        textPairingCode = findViewById(R.id.text_pairing_code)
        btnEndSession = findViewById(R.id.btn_end_session)
        bannerAccessibility = findViewById(R.id.banner_accessibility)
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
        layoutConsent = findViewById(R.id.layout_consent_section)

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Lần đầu: cần tick đồng ý
        checkboxConsent.setOnCheckedChangeListener { _, isChecked ->
            btnGenerateCode.isEnabled = isChecked
        }
        btnGenerateCode.isEnabled = false

        // Lần đầu bấm "Tạo mã & Bắt đầu"
        btnGenerateCode.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Vui lòng bật Dịch vụ Hỗ trợ trước", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            requestNotificationPermissionIfNeeded()
            // Tạo mã mới và lưu vào prefs ngay
            val newCode = generateAndSaveCode()
            pendingNewCode = newCode
            requestScreenCapturePermission()
        }

        // Nút "Tạo mã mới" (hiện khi đã có mã cũ)
        btnNewCode.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Vui lòng bật Dịch vụ Hỗ trợ trước", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            // Đóng phòng cũ trên Firebase
            roomCode?.let { old ->
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("rooms").child(old).child("status").setValue("ended")
            }
            // Dừng service cũ
            stopService(Intent(this, RemoteHostService::class.java))

            val newCode = generateAndSaveCode()
            pendingNewCode = newCode
            requestScreenCapturePermission()
        }

        btnEndSession.setOnClickListener { endSession() }

        // Khôi phục mã cũ nếu đã có
        val savedCode = prefs.getString("room_code_persistent", null)
        if (savedCode != null) {
            roomCode = savedCode
            showCodeUI(savedCode)
            // Nếu service chưa chạy (vd. máy khởi động lại) thì ẩn layout code,
            // yêu cầu người dùng bấm "Bắt đầu lại" để xin quyền màn hình lại
            // (Android bắt buộc xin quyền mới mỗi lần reboot)
            if (!RemoteHostService.isRunning) {
                showResumeUI(savedCode)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bannerAccessibility.visibility =
            if (isAccessibilityServiceEnabled()) View.GONE else View.VISIBLE
    }

    /** Hiển thị UI khi đã có mã (ẩn form consent, hiện mã + nút mới) */
    private fun showCodeUI(code: String) {
        layoutConsent.visibility = View.GONE
        textPairingCode.text = code
        layoutPairingCode.visibility = View.VISIBLE
        btnNewCode.visibility = View.VISIBLE
    }

    /** Hiện nút "Bắt đầu lại" khi có mã cũ nhưng service chưa chạy (sau reboot) */
    private fun showResumeUI(code: String) {
        layoutConsent.visibility = View.GONE
        textPairingCode.text = code
        layoutPairingCode.visibility = View.VISIBLE
        btnNewCode.visibility = View.VISIBLE
        // Dùng lại btn_generate_code làm nút "Bắt đầu lại" với mã cũ
        btnGenerateCode.isEnabled = true
        btnGenerateCode.text = "Bắt đầu lại với mã này"
        btnGenerateCode.visibility = View.VISIBLE
        btnGenerateCode.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Vui lòng bật Dịch vụ Hỗ trợ trước", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            pendingNewCode = code
            requestScreenCapturePermission()
        }
    }

    /** Tạo mã 6 số ngẫu nhiên, lưu vĩnh viễn vào prefs */
    private fun generateAndSaveCode(): String {
        val code = (100000..999999).random().toString()
        prefs.edit().putString("room_code_persistent", code).apply()
        return code
    }

    private fun requestScreenCapturePermission() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    @Deprecated("Legacy")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            val code = pendingNewCode ?: return
            pendingNewCode = null
            if (resultCode == RESULT_OK && data != null) {
                startRemoteService(code, data)
                roomCode = code
                showCodeUI(code)
            } else {
                Toast.makeText(this, "Đã từ chối quyền chia sẻ màn hình", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRemoteService(code: String, projectionData: Intent) {
        // Lưu active_room_code cho InputInjectionService
        prefs.edit().putString("active_room_code", code).apply()

        val serviceIntent = Intent(this, RemoteHostService::class.java).apply {
            putExtra(RemoteHostService.EXTRA_ROOM_CODE, code)
            putExtra(RemoteHostService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // Ghi Firebase song song
        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("rooms").child(code)
            .setValue(mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis()))
            .addOnFailureListener { e ->
                Toast.makeText(this, "Lỗi Firebase: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun endSession() {
        roomCode?.let { code ->
            com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("rooms").child(code).child("status").setValue("ended")
        }
        prefs.edit().remove("active_room_code").apply()
        // KHÔNG xóa room_code_persistent — mã vẫn hiển thị để dùng lại sau
        stopService(Intent(this, RemoteHostService::class.java))
        // Quay về màn hình hiển thị mã (service đã dừng, cần bắt đầu lại)
        roomCode?.let { showResumeUI(it) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${InputInjectionService::class.java.canonicalName}"
        val enabled = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) { return false }
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled ?: "")
        while (splitter.hasNext()) {
            if (splitter.next().equals(service, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }
}
