package com.toolskidukan.otp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows the real Meesho login page so the user can type the password and complete the
 * SMS-OTP / captcha (which no app can skip). On success we capture the session cookies and
 * return them; the headless engine then reuses that session with no further login.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var email = ""
    private var password = ""
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        email = intent.getStringExtra("email") ?: ""
        password = intent.getStringExtra("password") ?: ""

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#D84800"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "Log in to Meesho — enter password & OTP"
            setTextColor(Color.WHITE); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = Button(this).apply {
            text = "Done"
            setOnClickListener { captureAndFinish() }
        }
        header.addView(label); header.addView(done)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // fresh login: start from a clean cookie jar for this account
        CookieManager.getInstance().removeAllCookies(null)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && !MeeshoEngine.isLoginUrl(url)) {
                    // reached the panel -> logged in
                    captureAndFinish()
                    return
                }
                // prefill credentials on the login page (SELECTOR)
                val js = """
                (function(){
                  try{
                    var p=document.querySelector('input[type=password]');
                    var e=document.querySelector('input[name=emailOrPhone]')||document.querySelector('input[type=text],input[type=email]');
                    if(e && !e.value){ e.focus(); e.value=${jsStr(email)};
                      e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); }
                    if(p && !p.value){ p.focus(); p.value=${jsStr(password)};
                      p.dispatchEvent(new Event('input',{bubbles:true})); p.dispatchEvent(new Event('change',{bubbles:true})); }
                  }catch(err){}
                })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }

        root.addView(header); root.addView(web)
        setContentView(root)
        web.loadUrl(MeeshoEngine.LOGIN_URL)
    }

    private fun captureAndFinish() {
        if (finished) return
        val url = web.url ?: ""
        if (MeeshoEngine.isLoginUrl(url)) return   // not logged in yet; ignore
        finished = true
        CookieManager.getInstance().flush()
        val session = CookieManager.getInstance().getCookie(MeeshoEngine.BASE) ?: ""
        val slug = MeeshoEngine.slugFromUrl(url)
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("session", session)
            putExtra("slug", slug)
            putExtra("email", email)
        })
        finish()
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED); super.onBackPressed()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun jsStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        fun intent(a: Activity, email: String, password: String) =
            Intent(a, LoginActivity::class.java).putExtra("email", email).putExtra("password", password)
    }
}
