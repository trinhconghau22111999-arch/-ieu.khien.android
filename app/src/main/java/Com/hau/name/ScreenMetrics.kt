package Com.hau.name

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Nguồn DUY NHẤT để lấy kích thước màn hình vật lý thật (bao gồm cả nav bar/status bar).
 * RemoteHostService (capture) và InputInjectionService (dispatch gesture) đều dùng chung
 * để tránh lệch tọa độ.
 */
object ScreenMetrics {
    fun realSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        return metrics.widthPixels to metrics.heightPixels
    }
}
