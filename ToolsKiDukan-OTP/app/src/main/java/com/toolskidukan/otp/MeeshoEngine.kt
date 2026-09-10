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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Headless engine. Drives Android's built-in WebView (which *is* Chrome) off-screen.
 *
 * Login is HIDDEN: the user types email + password inside our own orange screen, and this
 * class types those into the real Meesho login page without ever showing it. If Meesho
 * demands an SMS-OTP, we still don't show their page - we report needsOtp, the app asks
 * for the digits in its own box, and we type those in too.
 *
 * Lines marked SELECTOR are the ones to tweak if Meesho changes its layout.
 */
object MeeshoEngine {
    const val BASE = "https://supplier.meesho.com"
    val LOGIN_URL = "$BASE/panel/v3/new/root/login"
    fun returnsUrl(slug: String) = "$BASE/panel/v3/new/fulfillment/$slug/returns/overview"
    private fun homeUrl(slug: String) = "$BASE/panel/v3/new/growth/$slug/home"

    private var web: WebView? = null

    /** Only one operation may drive the single WebView at a time. */
    val lock = Mutex()

    /** True while a hidden login is parked, waiting for the user to type the SMS-OTP. */
    @Volatile
    var awaitingOtp: Boolean = false
        private set

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
        // also drop any per-origin storage left behind by the previous account
        try {
            web?.clearCache(false)
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) { }
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

    private suspend fun currentUrl(): String = withContext(Dispatchers.Main) { web?.url ?: "" }

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
            val w = web
            if (w == null) { cont.resume(""); return@suspendCancellableCoroutine }
            w.evaluateJavascript(js) { value ->
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

    private fun jsStr(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\""

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
      return JSON.stringify({otps:otps, url:location.href, loginPage: /\/login|\/signin|\/auth/i.test(location.href)});
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

    /**
     * Finds the real STORE name shown by Meesho (not the email prefix).
     * Tries: the "Welcome back, X" greeting, store-name elements in the header, and finally
     * any storeName / shopName / supplierName field cached in localStorage by the panel.
     */
    private val STORE_JS = """
    (function(){
      function clean(s){ return (s||"").replace(/\s+/g," ").trim(); }
      var bad=/^(home|dashboard|meesho|supplier|panel|welcome|orders|returns|menu|profile|account|logout|settings|hi|hello|seller)${'$'}/i;
      function good(c){
        return c && c.length>=2 && c.length<=60 && !bad.test(c) && c.indexOf("@")<0 && !/^\d+${'$'}/.test(c);
      }
      var cands=[];
      var bt=(document.body?document.body.innerText:"")||"";
      // 1) greeting text  (SELECTOR)
      var m=bt.match(/Welcome back,\s*([^\n]{2,60})/i); if(m) cands.push(clean(m[1]));
      m=bt.match(/Hi,?\s+([A-Za-z0-9][^\n]{1,50})/); if(m) cands.push(clean(m[1]));
      // 2) header / profile elements  (SELECTOR)
      ['[class*="storeName" i]','[class*="store-name" i]','[data-testid*="store" i]',
       '[class*="supplierName" i]','[class*="shopName" i]','header [class*="name" i]']
        .forEach(function(sel){ try{ var e=document.querySelector(sel);
          if(e) cands.push(clean(e.innerText||e.textContent)); }catch(x){} });
      // 3) whatever the panel cached in localStorage
      try{
        for(var i=0;i<localStorage.length;i++){
          var v=localStorage.getItem(localStorage.key(i))||"";
          if(v.length<3||v.length>200000||v.indexOf("{")<0) continue;
          try{
            var o=JSON.parse(v);
            (function walk(n,d){
              if(!n||d>5||typeof n!=="object") return;
              for(var k in n){ var val=n[k];
                if(typeof val==="string"){
                  if(/^(store_?name|shop_?name|supplier_?name|business_?name|display_?name)${'$'}/i.test(k)) cands.push(clean(val));
                } else if(typeof val==="object") walk(val,d+1);
              }
            })(o,0);
          }catch(e){}
        }
      }catch(e){}
      for(var i=0;i<cands.length;i++) if(good(cands[i])) return cands[i];
      return "";
    })();
    """

    // ---------------- hidden login ----------------

    private fun fillJs(email: String, password: String) = """
    (function(){
      function set(el,val){
        if(!el) return false;
        try{
          var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
          if(d && d.set) d.set.call(el,val); else el.value=val;
        }catch(e){ el.value=val; }
        el.dispatchEvent(new Event('input',{bubbles:true}));
        el.dispatchEvent(new Event('change',{bubbles:true}));
        return true;
      }
      // SELECTOR: login fields
      var p=document.querySelector('input[type=password]');
      var e=document.querySelector('input[name=emailOrPhone]')
          ||document.querySelector('input[type=email]')
          ||document.querySelector('input[name*=email i],input[name*=phone i],input[id*=email i]')
          ||document.querySelector('input[type=text]');
      var a=set(e,${jsStr(email)});
      var b=set(p,${jsStr(password)});
      return JSON.stringify({e:a,p:b});
    })();
    """

    private val SUBMIT_JS = """
    (function(){
      var all=[].slice.call(document.querySelectorAll('button,input[type=submit],[role=button]'));
      var b=all.filter(function(x){
        var t=(x.innerText||x.value||"").trim();
        return /^(log ?in|sign ?in|continue|submit|proceed|next)${'$'}/i.test(t) && !x.disabled;
      })[0];
      if(!b) b=all.filter(function(x){
        return /log ?in|sign ?in|continue/i.test((x.innerText||x.value||"")) && !x.disabled;
      })[0];
      if(b){ b.click(); return "clicked"; }
      var p=document.querySelector('input[type=password]');
      if(p && p.form){ p.form.submit(); return "form"; }
      return "none";
    })();
    """

    /** Reports what the hidden login page is currently asking for. */
    private val STATE_JS = """
    (function(){
      var bt=(document.body?document.body.innerText:"")||"";
      var inputs=[].slice.call(document.querySelectorAll('input'));
      var otpEls=inputs.filter(function(i){
        var n=(i.name||"")+" "+(i.id||"")+" "+(i.placeholder||"")+" "+(i.getAttribute('aria-label')||"");
        if(/otp|verification|one.?time/i.test(n)) return true;
        return (i.maxLength===1||i.maxLength===4||i.maxLength===6) && /^(text|tel|number)${'$'}/i.test(i.type||"text") && i.type!=="password";
      });
      var otpText=/enter (the )?otp|otp sent|verification code|one.time password|verify your|enter code/i.test(bt);
      var err="";
      var em=bt.match(/(invalid[^\n]{0,70}|incorrect[^\n]{0,70}|not registered[^\n]{0,50}|does not exist[^\n]{0,50}|wrong [^\n]{0,50}|too many attempts[^\n]{0,50})/i);
      if(em) err=em[1].trim();
      var captcha=!!document.querySelector('iframe[src*="recaptcha"],iframe[src*="hcaptcha"],.g-recaptcha,#captcha');
      return JSON.stringify({
        url:location.href,
        hasPassword:!!document.querySelector('input[type=password]'),
        otp:(otpEls.length>0||otpText), otpCount:otpEls.length,
        error:err, captcha:captcha
      });
    })();
    """

    private fun otpJs(code: String) = """
    (function(){
      function set(el,val){
        try{
          var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
          if(d && d.set) d.set.call(el,val); else el.value=val;
        }catch(e){ el.value=val; }
        el.dispatchEvent(new Event('input',{bubbles:true}));
        el.dispatchEvent(new Event('change',{bubbles:true}));
      }
      var code=${jsStr(code)};
      var inputs=[].slice.call(document.querySelectorAll('input')).filter(function(i){
        var n=(i.name||"")+" "+(i.id||"")+" "+(i.placeholder||"")+" "+(i.getAttribute('aria-label')||"");
        if(i.type==="password") return false;
        if(/otp|verification|one.?time/i.test(n)) return true;
        return (i.maxLength===1||i.maxLength===4||i.maxLength===6) && /^(text|tel|number)${'$'}/i.test(i.type||"text");
      });
      if(!inputs.length) return "no-field";
      if(inputs.length===1){ inputs[0].focus(); set(inputs[0],code); }
      else {
        for(var i=0;i<inputs.length && i<code.length;i++){ inputs[i].focus(); set(inputs[i],code.charAt(i)); }
      }
      setTimeout(function(){
        var all=[].slice.call(document.querySelectorAll('button,input[type=submit],[role=button]'));
        var b=all.filter(function(x){
          return /verify|submit|continue|log ?in|confirm|proceed/i.test((x.innerText||x.value||"")) && !x.disabled;
        })[0];
        if(b) b.click();
      }, 400);
      return "filled";
    })();
    """

    fun slugFromUrl(url: String): String {
        val m = Regex("/panel/v3/new/(?!root\\b)[^/]+/([a-z0-9]{3,12})/", RegexOption.IGNORE_CASE).find(url)
        return m?.groupValues?.get(1) ?: ""
    }

    private suspend fun findSlug(): String {
        val u = currentUrl()
        var slug = slugFromUrl(u)
        if (slug.isBlank()) {
            val href = eval("(function(){var a=document.querySelector('a[href*=\"/returns\"],a[href*=\"/fulfillment/\"]');return a?a.getAttribute('href'):'';})()")
            slug = slugFromUrl(href)
        }
        return slug
    }

    private suspend fun readStoreName(slug: String): String {
        var name = eval(STORE_JS)
        if (name.isBlank() && slug.isNotBlank()) {
            load(homeUrl(slug)); delay(1500)
            name = eval(STORE_JS)
        }
        return name
    }

    /** Called once the hidden page has left the login screen. Captures session + store name. */
    private suspend fun captureSession(): JSONObject {
        delay(1500)
        val slug = findSlug()
        val store = readStoreName(slug)
        CookieManager.getInstance().flush()
        return JSONObject()
            .put("ok", true).put("needsOtp", false)
            .put("session", dumpCookies())
            .put("slug", slug)
            .put("storeName", store)
    }

    /**
     * Logs in WITHOUT showing Meesho's page.
     * Returns { ok, needsOtp, captcha, session, slug, storeName, error }.
     * If needsOtp is true the hidden page is parked on the OTP screen - call submitOtp() next.
     */
    suspend fun silentLogin(ctx: Context, email: String, password: String): JSONObject {
        ensureWebView(ctx)
        val out = JSONObject().put("ok", false).put("needsOtp", false).put("captcha", false)
        awaitingOtp = false

        clearCookies()
        load(LOGIN_URL)
        delay(2000)

        if (!isLoginUrl(currentUrl())) return captureSession()

        val filled = try { JSONObject(eval(fillJs(email, password))) } catch (e: Exception) { JSONObject() }
        if (!filled.optBoolean("p")) {
            delay(1500)   // slow React mount: try once more
            try { JSONObject(eval(fillJs(email, password))) } catch (e: Exception) { }
        }
        delay(700)
        eval(SUBMIT_JS)

        val deadline = System.currentTimeMillis() + 75000
        while (System.currentTimeMillis() < deadline) {
            delay(1500)
            if (!isLoginUrl(currentUrl())) return captureSession()
            val st = try { JSONObject(eval(STATE_JS)) } catch (e: Exception) { JSONObject() }
            if (st.optBoolean("otp")) {
                awaitingOtp = true
                return out.put("needsOtp", true).put("captcha", st.optBoolean("captcha"))
            }
            val err = st.optString("error")
            if (err.isNotBlank()) return out.put("error", err)
            if (st.optBoolean("captcha")) return out.put("captcha", true).put("error", "Meesho asked for a captcha")
        }
        return out.put("error", "Login timed out - check email/password or network")
    }

    /** Types the SMS-OTP into the still-hidden login page and finishes the login. */
    suspend fun submitOtp(ctx: Context, code: String): JSONObject {
        ensureWebView(ctx)
        val out = JSONObject().put("ok", false).put("needsOtp", true)
        if (!awaitingOtp) return out.put("needsOtp", false).put("error", "No login is waiting for an OTP")

        val r = eval(otpJs(code))
        if (r == "no-field") return out.put("error", "OTP box not found on the page")

        val deadline = System.currentTimeMillis() + 45000
        while (System.currentTimeMillis() < deadline) {
            delay(1500)
            if (!isLoginUrl(currentUrl())) { awaitingOtp = false; return captureSession() }
            val st = try { JSONObject(eval(STATE_JS)) } catch (e: Exception) { JSONObject() }
            val err = st.optString("error")
            if (err.isNotBlank()) return out.put("error", err)
        }
        return out.put("error", "OTP not accepted - try again")
    }

    /** Drops a parked OTP login (user cancelled). */
    fun cancelOtp() { awaitingOtp = false }

    // ---------------- OTP scraping ----------------

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
            slug = findSlug()
            if (slug.isNotBlank()) { url = load(returnsUrl(slug)); delay(1000) }
            out.put("slug", slug)
        }

        // open More OTPs, then parse
        eval(MORE_JS); delay(800)
        val json = try { JSONObject(eval(PARSE_JS)) } catch (e: Exception) { JSONObject() }
        if (json.optBoolean("loginPage")) return out.put("needsLogin", true).put("error", "Session expired")

        // real store name straight from the panel
        val store = if (account.optString("storeName").isBlank()) readStoreName(slug) else eval(STORE_JS)

        // persist any refreshed cookies back to the account session
        val fresh = dumpCookies(); if (fresh.isNotBlank()) account.put("session", fresh)

        return out.put("ok", true)
            .put("otps", json.optJSONArray("otps") ?: JSONArray())
            .put("storeName", store)
            .put("slug", if (slug.isNotBlank()) slug else account.optString("slug"))
    }
}
