
package com.alzubair.omri

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private const val CHANNEL_ID = "omri_reminders"
private const val PREFS = "omri_settings"
private const val REMINDERS = "scheduled_reminders"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(false)
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تذكيرات تطبيق عُمري",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات أعياد الميلاد والتذكيرات"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}

class AndroidBridge(private val activity: MainActivity) {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JavascriptInterface
    fun saveData(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun getData(key: String): String {
        return prefs.getString(key, "") ?: ""
    }

    @JavascriptInterface
    fun deleteData(key: String) {
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun showNotification(title: String, message: String) {
        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    activity,
                    "اسمح للتطبيق بإرسال الإشعارات من إعدادات الهاتف",
                    Toast.LENGTH_LONG
                ).show()
                return@runOnUiThread
            }

            val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(activity)
                .notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    // atMillis هو وقت الإشعار بتوقيت الجهاز بالميلي ثانية
    // repeat: "none" أو "daily" أو "yearly"
    @JavascriptInterface
    fun scheduleNotification(
        id: String,
        title: String,
        message: String,
        atMillis: Long,
        repeat: String
    ) {
        ReminderScheduler.schedule(
            activity, id, title, message, atMillis, repeat
        )
    }

    @JavascriptInterface
    fun cancelNotification(id: String) {
        ReminderScheduler.cancel(activity, id)
    }

    @JavascriptInterface
    fun openWhatsApp(number: String, message: String) {
        activity.runOnUiThread {
            try {
                val cleanNumber = number.filter { it.isDigit() }
                val url = "https://wa.me/$cleanNumber?text=" +
                    Uri.encode(message)
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                )
            } catch (e: Exception) {
                Toast.makeText(
                    activity, "تعذر فتح واتساب", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun closeApp() {
        activity.runOnUiThread {
            activity.finish()
        }
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            } else {
                Toast.makeText(
                    activity, "الإشعارات مفعلة", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

object ReminderScheduler {

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun schedule(
        context: Context,
        id: String,
        title: String,
        message: String,
        atMillis: Long,
        repeat: String
    ) {
        if (atMillis <= System.currentTimeMillis()) return

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("title", title)
            putExtra("message", message)
            putExtra("repeat", repeat)
        }

        val pending = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            atMillis,
            pending
        )

        saveReminder(context, id, title, message, atMillis, repeat)
    }

    fun cancel(context: Context, id: String) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending)

        val prefs = getPrefs(context)
        val old = JSONArray(prefs.getString(REMINDERS, "[]") ?: "[]")
        val updated = JSONArray()

        for (i in 0 until old.length()) {
            val item = old.getJSONObject(i)
            if (item.optString("id") != id) updated.put(item)
        }

        prefs.edit().putString(REMINDERS, updated.toString()).apply()
    }

    private fun saveReminder(
        context: Context,
        id: String,
        title: String,
        message: String,
        atMillis: Long,
        repeat: String
    ) {
        val prefs = getPrefs(context)
        val old = JSONArray(prefs.getString(REMINDERS, "[]") ?: "[]")
        val updated = JSONArray()

        for (i in 0 until old.length()) {
            val item = old.getJSONObject(i)
            if (item.optString("id") != id) updated.put(item)
        }

        updated.put(JSONObject().apply {
            put("id", id)
            put("title", title)
            put("message", message)
            put("time", atMillis)
            put("repeat", repeat)
        })

        prefs.edit().putString(REMINDERS, updated.toString()).apply()
    }

    fun restoreAll(context: Context) {
        val prefs = getPrefs(context)
        val data = JSONArray(prefs.getString(REMINDERS, "[]") ?: "[]")
        val now = System.currentTimeMillis()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            var time = item.optLong("time")
            val repeat = item.optString("repeat")

            if (repeat == "daily") {
                while (time <= now) time += 24L * 60 * 60 * 1000
            } else if (repeat == "yearly") {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = time
                }
                while (time <= now) {
                    calendar.add(Calendar.YEAR, 1)
                    time = calendar.timeInMillis
                }
            }

            if (time > now) {
                schedule(
                    context,
                    item.optString("id"),
                    item.optString("title"),
                    item.optString("message"),
                    time,
                    repeat
                )
            }
        }
    }

    fun rescheduleNext(
        context: Context,
        id: String,
        title: String,
        message: String,
        repeat: String
    ) {
        if (repeat == "none") {
            cancel(context, id)
            return
        }

        val next = Calendar.getInstance()

        if (repeat == "daily") {
            next.add(Calendar.DAY_OF_YEAR, 1)
        } else if (repeat == "yearly") {
            next.add(Calendar.YEAR, 1)
        } else {
            return
        }

        schedule(
            context,
            id,
            title,
            message,
            next.timeInMillis,
            repeat
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: "تذكير عُمري"
        val message = intent.getStringExtra("message") ?: ""
        val repeat = intent.getStringExtra("repeat") ?: "none"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(id.hashCode(), notification)
        }

        ReminderScheduler.rescheduleNext(
            context, id, title, message, repeat
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.restoreAll(context)
        }
    }
}
