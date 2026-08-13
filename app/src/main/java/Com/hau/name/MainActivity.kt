package Com.hau.name

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hiện crash log lần trước nếu có
        CrashHandler.getLastCrashLog(this)?.let { (log, time) ->
            val timeStr = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
                .format(Date(time))
            val preview = if (log.length > 600) log.take(600) + "\n...(còn nữa)" else log
            AlertDialog.Builder(this)
                .setTitle("App bị crash lần trước ($timeStr)")
                .setMessage(preview)
                .setPositiveButton("Copy log") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", log))
                    Toast.makeText(this, "Da copy!", Toast.LENGTH_SHORT).show()
                    CrashHandler.clearLastCrashLog(this)
                }
                .setNegativeButton("Bo qua") { _, _ ->
                    CrashHandler.clearLastCrashLog(this)
                }
                .setCancelable(false)
                .show()
        }

        findViewById<Button>(R.id.btn_role_controlled).setOnClickListener {
            startActivity(Intent(this, ConsentActivity::class.java))
        }
        findViewById<Button>(R.id.btn_role_controller).setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }
    }
}
