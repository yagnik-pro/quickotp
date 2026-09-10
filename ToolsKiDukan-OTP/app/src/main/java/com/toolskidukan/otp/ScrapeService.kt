package com.toolskidukan.otp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Foreground service = "background app open" so sessions stay warm and OTPs keep refreshing.
 * It loops every N minutes (from Settings) and refreshes all accounts through the engine.
 */
class ScrapeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        if (loop == null || loop?.isActive != true) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        loop = scope.launch {
            while (isActive) {
                val intervalMin = Store.settings(this@ScrapeService).optInt("intervalMin", 10)
                if (intervalMin <= 0) { delay(60_000); continue }
                refreshAll()
                delay(intervalMin.toLong() * 60_000L)
            }
        }
    }

    private suspend fun refreshAll() {
        if (MeeshoEngine.awaitingOtp) return       // user is mid-login; don't disturb the page
        val accounts = Store.accounts(this)
        for (i in 0 until accounts.length()) {
            val acc = accounts.getJSONObject(i)
            try {
                var r = MeeshoEngine.lock.withLock { MeeshoEngine.fetch(this, acc) }
                // session died -> silently log back in with the saved password
                if (r.optBoolean("needsLogin") && acc.optString("password").isNotBlank()) {
                    val lr = MeeshoEngine.lock.withLock {
                        MeeshoEngine.silentLogin(this, acc.optString("email"), acc.optString("password"))
                    }
                    if (lr.optBoolean("ok") && lr.optString("session").isNotBlank()) {
                        val ch = mutableMapOf<String, Any?>("session" to lr.optString("session"),
                            "status" to "ok", "lastLogin" to System.currentTimeMillis(), "lastError" to null)
                        if (lr.optString("slug").isNotBlank()) ch["slug"] = lr.optString("slug")
                        val ln = lr.optString("storeName")
                        if (ln.isNotBlank()) { ch["storeName"] = ln; if (acc.optBoolean("autoName", true)) ch["name"] = ln }
                        Store.patchAccount(this, acc.optString("id"), ch)
                        val fresh = Store.findAccount(this, acc.optString("id"))
                        if (fresh != null) r = MeeshoEngine.lock.withLock { MeeshoEngine.fetch(this, fresh) }
                    } else if (lr.optBoolean("needsOtp")) {
                        MeeshoEngine.cancelOtp()   // can't ask the user from the background
                        Store.patchAccount(this, acc.optString("id"),
                            mapOf("status" to "needs_otp", "lastError" to "Open the app and enter the OTP"))
                        continue
                    }
                }
                if (r.optBoolean("ok")) {
                    Store.putCache(this, acc.optString("id"), r.optJSONArray("otps") ?: JSONArray(), null)
                    val changes = mutableMapOf<String, Any?>("status" to "ok", "lastError" to null, "session" to acc.optString("session"))
                    if (r.optString("slug").isNotBlank()) changes["slug"] = r.optString("slug")
                    val sn = r.optString("storeName")
                    if (sn.isNotBlank()) { changes["storeName"] = sn; if (acc.optBoolean("autoName", true)) changes["name"] = sn }
                    Store.patchAccount(this, acc.optString("id"), changes)
                } else if (r.optBoolean("needsLogin")) {
                    Store.patchAccount(this, acc.optString("id"), mapOf("status" to "needs_login", "lastError" to r.optString("error")))
                } else {
                    Store.patchAccount(this, acc.optString("id"), mapOf("status" to "error", "lastError" to r.optString("error")))
                }
            } catch (e: Exception) {
                Store.patchAccount(this, acc.optString("id"), mapOf("status" to "error", "lastError" to (e.message ?: "error")))
            }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.svc_channel), NotificationManager.IMPORTANCE_MIN)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.svc_title))
            .setContentText(getString(R.string.svc_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() { loop?.cancel(); scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "otp_bg"
        private const val NOTIF_ID = 42
        fun start(ctx: Context) {
            val i = Intent(ctx, ScrapeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScrapeService::class.java)) }
    }
}
