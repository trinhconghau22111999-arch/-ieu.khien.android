package Com.hau.name

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Màn hình đầu tiên: chọn vai trò.
 * - "Cho phép máy khác điều khiển" → ConsentActivity (Máy B)
 * - "Điều khiển một máy khác"       → ControllerActivity (Máy A)
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Kiểm tra crash lần trước — hiện dialog ngay khi mở app
        CrashHandler.getLastCrashLog(this)?.let { (log, time) ->
            val timeStr = java.text.SimpleDateFormat("dd/MM HH:mm:ss",
                java.util.Locale.getDefault()).format(java.util.Date(time))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠️ App bị crash lần trước ($timeStr)")
                .setMessage(log.take(800) + if (log.length > 800) "
...(xem thêm)" else "")
                .setPositiveButton("📋 Copy log") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                    android.widget.Toast.makeText(this, "Đã copy!", android.widget.Toast.LENGTH_SHORT).show()
                    CrashHandler.clearLastCrashLog(this)
                }
                .setNegativeButton("Bỏ qua") { _, _ ->
                    CrashHandler.clearLastCrashLog(this)
                }
                .setCancelable(false)
                .show()
        }

        findViewById<android.widget.Button>(R.id.btn_role_controlled).setOnClickListener {
            startActivity(Intent(this, ConsentActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btn_role_controller).setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }
    }
}
