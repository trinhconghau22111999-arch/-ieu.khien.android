package Com.hau.name

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val log = intent.getStringExtra("crash_log") ?: "Không có thông tin lỗi"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        root.addView(TextView(this).apply {
            text = "⚠️ App bị crash"
            textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; setPadding(0,0,0,8)
        })
        root.addView(TextView(this).apply {
            text = "Copy log bên dưới gửi cho nhà phát triển để sửa"
            textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(0,0,0,20)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(TextView(this).apply {
            text = log; textSize = 11f
            setTextColor(Color.parseColor("#00FF88"))
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(16,16,16,16)
            typeface = Typeface.MONOSPACE
        })
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "📋 Copy log"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("crash", log))
                Toast.makeText(context, "Đã copy!", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "🔄 Mở lại app"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
            setOnClickListener {
                CrashHandler.clearLastCrashLog(context)
                startActivity(packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            }
        })
        setContentView(root)
    }
}
