package Com.hau.name

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val log = buildLog(thread, throwable)
            saveLog(log)
            Log.e("CRASH", log)

            // Mở CrashActivity lần sau
            val intent = android.content.Intent(context, CrashActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                         android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("crash_log", log)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CRASH", "CrashHandler error", e)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun buildLog(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
        return "=== CRASH $time ===\n" +
               "Thread: ${thread.name}\n" +
               "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
               "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
               "Error: ${throwable.javaClass.simpleName}: ${throwable.message}\n\n" +
               "=== STACK TRACE ===\n$sw"
    }

    private fun saveLog(log: String) {
        try {
            context.getSharedPreferences("remote_assist", Context.MODE_PRIVATE)
                .edit()
                .putString("last_crash_log", log)
                .putLong("last_crash_time", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    companion object {
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }

        fun getLastCrashLog(context: Context): Pair<String, Long>? {
            val prefs = context.getSharedPreferences("remote_assist", Context.MODE_PRIVATE)
            val log = prefs.getString("last_crash_log", null) ?: return null
            val time = prefs.getLong("last_crash_time", 0)
            return log to time
        }

        fun clearLastCrashLog(context: Context) {
            context.getSharedPreferences("remote_assist", Context.MODE_PRIVATE)
                .edit().remove("last_crash_log").remove("last_crash_time").apply()
        }
    }
}
