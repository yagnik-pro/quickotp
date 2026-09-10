package com.toolskidukan.otp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var ui: WebView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pendingLogin: CompletableDeferred<Intent?>? = null
    private var pendingOtpAccount: String? = null
    private lateinit var loginLauncher: ActivityResultLauncher<Intent>
    private lateinit var notifLauncher: ActivityResultLauncher<String>

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = if (res.resultCode == Activity.RESULT_OK) res.data else null
            pendingLogin?.complete(data); pendingLogin = null
        }
        notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        ui = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Native")
        }
        setContentView(ui)
        ui.loadUrl("file:///android_asset/ui.html")

        askPermissions()
        maybeStartBackground()
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // battery optimisation exemption -> lets the background refresh survive
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) { }
    }

    private fun maybeStartBackground() {
        if (Store.settings(this).optBoolean("background", true) && Store.accounts(this).length() > 0)
            ScrapeService.start(this)
    }

    // ------------- JS bridge -------------
    inner class Bridge {
        @JavascriptInterface
        fun request(reqId: String, method: String, body: String) {
            scope.launch {
                val res = try { handle(method, JSONObject(if (body.isBlank()) "{}" else body)) }
                          catch (e: Exception) { JSONObject().put("__error", e.message ?: "error") }
                resolve(reqId, res)
            }
        }
    }

    private fun resolve(reqId: String, res: JSONObject) {
        val err = res.optString("__error", "")
        val ok = if (err.isEmpty()) "true" else "false"
        val payload = JSONObject.quote(res.toString())
        ui.evaluateJavascript("window.__nativeResolve('$reqId', $ok, $payload);", null)
    }

    private suspend fun handle(method: String, body: JSONObject): JSONObject = when (method) {
        "otps"                 -> view()
        "refresh"              -> { refresh(body.optString("accountId", "").ifBlank { null }); view() }
        "accounts.add"         -> addAccount(body)
        "accounts.relogin"     -> reloginPublic(idsFrom(body))
        "accounts.manualLogin" -> reloginPublic(listOf(body.getString("id")))
        "accounts.otp"         -> submitOtp(body)
        "accounts.otpCancel"   -> { MeeshoEngine.cancelOtp(); pendingOtpAccount = null; view() }
        "accounts.delete"      -> { Store.deleteAccount(this, body.getString("id")); JSONObject().put("ok", true) }
        "settings.set"         -> saveSettings(body)
        else                   -> JSONObject().put("__error", "unknown method: $method")
    }

    private fun idsFrom(body: JSONObject): List<String>? {
        val arr = body.optJSONArray("ids") ?: return null
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ------------- operations -------------
    private suspend fun addAccount(body: JSONObject): JSONObject {
        val email = body.optString("email").trim()
        val password = body.optString("password")
        if (email.isBlank() || password.isBlank()) return JSONObject().put("__error", "Email and password are required")
        if (Store.emailExists(this, email)) return JSONObject().put("__error", "This email is already added")

        val nameGiven = body.optString("name").trim()
        val slug = Regex("fulfillment/([^/]+)/returns").find(body.optString("returnsUrl"))?.groupValues?.get(1) ?: ""
        val acc = JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("email", email); put("password", password)
            put("name", if (nameGiven.isNotBlank()) nameGiven else "Loading store name…")
            put("autoName", nameGiven.isBlank())
            put("slug", slug); put("status", "new")
        }
        Store.upsertAccount(this, acc)
        val r = hiddenLogin(acc)         // Meesho page is never shown
        if (Store.accounts(this).length() > 0 && Store.settings(this).optBoolean("background", true)) ScrapeService.start(this)
        return view().put("login", r)
    }

    private suspend fun reloginPublic(ids: List<String>?): JSONObject {
        val all = Store.accounts(this)
        val targets = (0 until all.length()).map { all.getJSONObject(it) }
            .filter { ids == null || ids.contains(it.optString("id")) }
        var last = JSONObject().put("ok", true)
        for (acc in targets) last = hiddenLogin(acc)
        return view().put("login", last)
    }

    /**
     * Logs in without ever showing the Meesho page. If Meesho demands an SMS-OTP we park the
     * hidden page and tell the UI to ask for the 6 digits in our own box.
     * Returns { ok, needsOtp, accountId, error }.
     */
    private suspend fun hiddenLogin(acc: JSONObject): JSONObject {
        val id = acc.optString("id")
        Store.patchAccount(this, id, mapOf("status" to "logging_in", "lastError" to null))
        val r = try {
            MeeshoEngine.lock.withLock {
                MeeshoEngine.silentLogin(this, acc.optString("email"), acc.optString("password"))
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "Login failed")
        }

        if (r.optBoolean("needsOtp")) {
            pendingOtpAccount = id
            Store.patchAccount(this, id, mapOf("status" to "needs_otp", "lastError" to "Enter the OTP sent by Meesho"))
            return JSONObject().put("ok", false).put("needsOtp", true).put("accountId", id)
        }
        return applyLoginResult(id, r)
    }

    private suspend fun submitOtp(body: JSONObject): JSONObject {
        val id = body.optString("id").ifBlank { pendingOtpAccount ?: "" }
        val code = body.optString("code").trim()
        if (id.isBlank()) return view().put("login", JSONObject().put("ok", false).put("error", "No login is waiting"))
        if (code.isBlank()) return view().put("login", JSONObject().put("ok", false).put("error", "Enter the OTP"))
        val r = try {
            MeeshoEngine.lock.withLock { MeeshoEngine.submitOtp(this, code) }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "OTP failed")
        }
        if (r.optBoolean("needsOtp") && !r.optBoolean("ok")) {
            return view().put("login", JSONObject().put("ok", false).put("needsOtp", true)
                .put("accountId", id).put("error", r.optString("error")))
        }
        pendingOtpAccount = null
        return view().put("login", applyLoginResult(id, r))
    }

    /** Saves a successful hidden login (session, slug, real store name) and pulls OTPs once. */
    private suspend fun applyLoginResult(id: String, r: JSONObject): JSONObject {
        if (!r.optBoolean("ok") || r.optString("session").isBlank()) {
            val msg = r.optString("error").ifBlank { "Login failed" }
            Store.patchAccount(this, id, mapOf("status" to "needs_login", "lastError" to msg))
            return JSONObject().put("ok", false).put("error", msg).put("accountId", id)
        }
        val acc = Store.findAccount(this, id)
        val changes = mutableMapOf<String, Any?>(
            "session" to r.optString("session"), "status" to "ok",
            "lastLogin" to System.currentTimeMillis(), "lastError" to null
        )
        if (r.optString("slug").isNotBlank()) changes["slug"] = r.optString("slug")
        val sn = r.optString("storeName")
        if (sn.isNotBlank()) {
            changes["storeName"] = sn
            if (acc == null || acc.optBoolean("autoName", true)) changes["name"] = sn
        }
        Store.patchAccount(this, id, changes)
        refreshOne(id, allowRelogin = false)   // just logged in; don't loop
        return JSONObject().put("ok", true).put("accountId", id).put("storeName", sn)
    }

    private suspend fun refresh(accountId: String?) {
        val all = Store.accounts(this)
        val targets = (0 until all.length()).map { all.getJSONObject(it).optString("id") }
            .filter { accountId == null || it == accountId }
        for (id in targets) refreshOne(id)
    }

    private suspend fun refreshOne(id: String, allowRelogin: Boolean = true) {
        val acc = Store.findAccount(this, id) ?: return
        if (MeeshoEngine.awaitingOtp) return          // a hidden login is parked on the OTP screen
        try {
            val r = MeeshoEngine.lock.withLock { MeeshoEngine.fetch(this, acc) }
            when {
                r.optBoolean("ok") -> {
                    Store.putCache(this, id, r.optJSONArray("otps") ?: JSONArray(), null)
                    val changes = mutableMapOf<String, Any?>("status" to "ok", "lastError" to null, "session" to acc.optString("session"))
                    if (r.optString("slug").isNotBlank()) changes["slug"] = r.optString("slug")
                    val sn = r.optString("storeName")
                    if (sn.isNotBlank()) { changes["storeName"] = sn; if (acc.optBoolean("autoName", true)) changes["name"] = sn }
                    Store.patchAccount(this, id, changes)
                }
                r.optBoolean("needsLogin") -> {
                    // session died -> log back in silently with the saved password, no user action
                    if (allowRelogin && acc.optString("password").isNotBlank()) {
                        val lr = hiddenLogin(acc)
                        if (!lr.optBoolean("ok") && !lr.optBoolean("needsOtp"))
                            Store.patchAccount(this, id, mapOf("status" to "needs_login", "lastError" to lr.optString("error")))
                    } else {
                        Store.patchAccount(this, id, mapOf("status" to "needs_login", "lastError" to r.optString("error")))
                    }
                }
                else -> Store.patchAccount(this, id, mapOf("status" to "error", "lastError" to r.optString("error")))
            }
        } catch (e: Exception) {
            Store.patchAccount(this, id, mapOf("status" to "error", "lastError" to (e.message ?: "error")))
        }
    }

    private fun saveSettings(body: JSONObject): JSONObject {
        val s = Store.settings(this)
        if (body.has("intervalMin")) s.put("intervalMin", body.optInt("intervalMin", 10))
        if (body.has("background")) s.put("background", body.optBoolean("background", true))
        Store.saveSettings(this, s)
        if (s.optBoolean("background", true) && Store.accounts(this).length() > 0) ScrapeService.start(this) else ScrapeService.stop(this)
        return JSONObject().put("ok", true).put("settings", s)
    }

    // ------------- view builder (matches the desktop /api/otps shape) -------------
    private fun view(): JSONObject {
        val accounts = Store.accounts(this)
        val cache = Store.cache(this)
        val byAccount = JSONArray()
        val carrierMap = LinkedHashMap<String, JSONObject>()
        var totalReturns = 0

        for (i in 0 until accounts.length()) {
            val a = accounts.getJSONObject(i)
            val id = a.optString("id")
            val c = cache.optJSONObject(id)
            val otps = c?.optJSONArray("otps") ?: JSONArray()
            val pub = JSONObject().apply {
                put("id", id); put("name", a.optString("name")); put("email", a.optString("email"))
                put("supplierId", a.optString("supplierId")); put("slug", a.optString("slug"))
                put("status", a.optString("status", "new")); put("hasSession", a.optString("session").isNotBlank())
                put("lastLogin", a.opt("lastLogin") ?: JSONObject.NULL); put("lastError", a.opt("lastError") ?: JSONObject.NULL)
                put("otps", otps); put("fetchedAt", c?.opt("fetchedAt") ?: JSONObject.NULL); put("error", c?.opt("error") ?: JSONObject.NULL)
            }
            byAccount.put(pub)
            for (j in 0 until otps.length()) {
                val o = otps.getJSONObject(j)
                val carrier = o.optString("carrier")
                val entry = carrierMap.getOrPut(carrier) { JSONObject().put("carrier", carrier).put("totalReturns", 0).put("entries", JSONArray()) }
                val cnt = o.optInt("count")
                entry.put("totalReturns", entry.optInt("totalReturns") + cnt); totalReturns += cnt
                entry.getJSONArray("entries").put(JSONObject().apply {
                    put("accountId", id); put("accountName", a.optString("name")); put("email", a.optString("email"))
                    put("otp", o.optString("otp")); put("count", cnt); put("time", o.optString("time"))
                })
            }
        }
        val byCarrier = JSONArray()
        carrierMap.values.sortedByDescending { it.optInt("totalReturns") }.forEach { byCarrier.put(it) }
        return JSONObject().apply {
            put("byAccount", byAccount); put("byCarrier", byCarrier); put("totalReturns", totalReturns)
            put("settings", Store.settings(this@MainActivity))
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
