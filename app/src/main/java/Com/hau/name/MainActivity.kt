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

        findViewById<android.widget.Button>(R.id.btn_role_controlled).setOnClickListener {
            startActivity(Intent(this, ConsentActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btn_role_controller).setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }
    }
}
