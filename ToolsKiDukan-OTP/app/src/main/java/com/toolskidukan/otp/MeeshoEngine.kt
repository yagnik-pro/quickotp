package com.toolskidukan.otp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Headless scraping engine. Instead of the desktop app's Chrome+Playwright, this drives
 * Android's built-in WebView (which *is* Chrome). One reusable off-screen WebView is used
 * sequentially, one account at a time, so accounts never share cookies.
 *
 * There is NO private API here: it opens the exact same supplier.meesho.com Returns page you
 * would see yourself and reads the OTP widget off it. Lines marked SELECTOR are the ones to
 * tweak if Meesho changes its layout.
 */
object MeeshoEngine {
    const val BASE = "https://supplier.meesho.com"
    val LOGIN_URL = "$BASE/panel/v3/new/root/login"
    fun returnsUrl(slug: String) = "$BASE/panel/v3/new/fulfillment/$slug/returns/overview"
    private fun homeUrl(slug: String) = "$BASE/panel/v3/new/growth/$slug/home"

    private var web: WebView? = null

    fun isLoginUrl(url: String): Boolean =
        Regex("/login|/signin|/auth", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
        Regex("/root/?$").containsMatchIn(url) ||
        url.trimEnd('/') == BASE

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun ensureWebView(ctx: Context): WebView = withContext(Dispatchers.Main) {
        web ?: WebView(ctx.applicationContext).also { w ->
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = false          // faster: we only need text
                userAgentString = userAgentString.replace("; wv", "")
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            web = w
        }
    }

    // ---------------- cookie isolation (one account at a time) ----------------
    private suspend fun clearCookies() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            CookieManager.getInstance().removeAllCookies { cont.resume(Unit) }
        }
        CookieManager.getInstance().flush()
    }

    private fun applyCookieString(cookies: String) {
        val cm = CookieManager.getInstance()
        cookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            cm.setCookie(BASE, pair)
            cm.setCookie("https://.meesho.com", pair)
        }
        cm.flush()
    }

    private fun dumpCookies(): String = CookieManager.getInstance().getCookie(BASE) ?: ""

    // ---------------- page load + JS helpers ----------------
    private suspend fun load(url: String, timeoutMs: Long = 45000): String = withContext(Dispatchers.Main) {
        val w = web!!
        val finalUrl = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                w.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = false
                    override fun onPageFinished(v: WebView?, u: String?) {
                        if (cont.isActive) cont.resume(u ?: url)
                    }
                }
                w.loadUrl(url)
            }
        } ?: (w.url ?: url)
        finalUrl
    }

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String> { cont ->
            web!!.evaluateJavascript(js) { value ->
                // evaluateJavascript returns a JSON-encoded string; unwrap it
                val v = when {
                    value == null || value == "null" -> ""
                    value.startsWith("\"") && value.endsWith("\"") ->
                        JSONArray("[$value]").getString(0)
                    else -> value
                }
                if (cont.isActive) cont.resume(v)
            }
        }
    }

    // The OTP parser, ported 1:1 from the desktop meesho.js regex.
    private val PARSE_JS = """
    (function(){
      function parse(text){
        var out=[]; var re=/([A-Za-z][A-Za-z ]{1,30}?)\s+OTP:\s*(\d{3,8})\s*([^\n]*)?\n[\s\S]*?Total Handover Count\s*:\s*(\d+)/g; var m;
        while((m=re.exec(text||""))){
          var carrier=m[1].trim().replace(/\s+/g," ").replace(/^Xpress Bees${'$'}/i,"Xpressbees");
          if(out.some(function(o){return o.carrier===carrier&&o.otp===m[2];})) continue;
          out.push({carrier:carrier,otp:m[2],time:(m[3]||"").trim(),count:parseInt(m[4],10)||0});
        }
        return out;
      }
      var bodyText=(document.body?document.body.innerText:"")||"";
      var otps=parse(bodyText);
      var store="";
      var gm=bodyText.match(/Welcome back,\s*([^\n]+)/i); if(gm) store=gm[1].trim();
      return JSON.stringify({otps:otps, storeName:store, url:location.href, loginPage: /\/login|\/signin|\/auth/i.test(location.href)});
    })();
    """

    // Best-effort: open the "More OTPs" drawer so the full list is in the DOM.
    private val MORE_JS = """
    (function(){
      var els=[].slice.call(document.querySelectorAll('button,a,[role=button],div,span'));
      var t=els.filter(function(e){return /More OTPs/i.test((e.innerText||e.textContent||"")) && (e.innerText||"").length<40;})[0];
      if(t){ t.click(); return "clicked"; } return "none";
    })();
    """

    fun slugFromUrl(url: String): String {
        val m = Regex("/panel/v3/new/(?!root\\b)[^/]+/([a-z0-9]{3,12})/", RegexOption.IGNORE_CASE).find(url)
        return m?.groupValues?.get(1) ?: ""
    }

    /**
     * Fetch OTPs for one account using its saved session (no login). Returns a JSONObject:
     *   { ok, needsLogin, otps:[], storeName, slug, error }
     */
    suspend fun fetch(ctx: Context, account: JSONObject): JSONObject {
        ensureWebView(ctx)
        val out = JSONObject().put("ok", false).put("needsLogin", false)
            .put("otps", JSONArray()).put("storeName", "").put("slug", account.optString("slug"))
        val session = account.optString("session")
        if (session.isBlank()) return out.put("needsLogin", true).put("error", "Not logged in")

        clearCookies(); applyCookieString(session)

        var slug = account.optString("slug")
        val target = if (slug.isNotBlank()) returnsUrl(slug) else "$BASE/panel/v3/new/root/home"
        var url = load(target)
        delay(1200)

        if (isLoginUrl(url)) return out.put("needsLogin", true).put("error", "Session expired")

        // discover slug if unknown
        if (slug.isBlank()) {
            slug = slugFromUrl(url)
            if (slug.isBlank()) { slug = eval("(function(){var a=document.querySelector('a[href*=\"/returns\"]');return a?a.getAttribute('href'):'';})()").let { slugFromUrl(it) } }
            if (slug.isNotBlank()) { url = load(returnsUrl(slug)); delay(1000) }
            out.put("slug", slug)
        }

        // open More OTPs, then parse
        eval(MORE_JS); delay(800)
        val json = try { JSONObject(eval(PARSE_JS)) } catch (e: Exception) { JSONObject() }
        if (json.optBoolean("loginPage")) return out.put("needsLogin", true).put("error", "Session expired")

        // persist any refreshed cookies back to the account session
        val fresh = dumpCookies(); if (fresh.isNotBlank()) account.put("session", fresh)

        return out.put("ok", true)
            .put("otps", json.optJSONArray("otps") ?: JSONArray())
            .put("storeName", json.optString("storeName"))
            .put("slug", if (slug.isNotBlank()) slug else account.optString("slug"))
    }
}
