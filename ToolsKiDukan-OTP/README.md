# Tools ki Dukan · Return-OTP (Android)

Meesho seller ke **return-handover OTP** (Delhivery, Xpressbees, Shadowfax, Valmo)
ek j phone par, badha accounts sathe — **koi server nahi, koi PC nahi**. Phone no
built-in browser (WebView) j supplier.meesho.com kholi, tamara Returns page parthi
OTP vaanche chhe. Ek var login karo pachi session save thai jay ane aap background
ma OTP refresh karto rahe chhe.

> Aa aap tamara **potana** accounts mate chhe. Meesho panel automate karvu Meesho na
> Terms virudh hoi shake — tamari jawabdari par. Badho data (password, session, OTP)
> **fakt aa phone par** j rahe chhe.

---

## APK kaise banave (PC ki zarurat nahi) — GitHub se

1. github.com par free account banao → **New repository** (naam kuch bhi, e.g. `tkd-otp`).
2. Is folder ki saari files us repo me daal do:
   - Easy way: repo page par **Add file → Upload files** → yeh poora folder drag-drop → **Commit**.
3. Repo me upar **Actions** tab kholo → agar poochhe to Actions **enable** karo.
4. Left me **Build APK** → right me **Run workflow** → **Run**. (Push par bhi apne aap chalta hai.)
5. 3–5 min me green tick aa jayega. Us run ko kholo → niche **Artifacts** me
   **ToolsKiDukan-OTP-apk** → download karo → zip khol ke `app-debug.apk` milega.
6. Phone me woh APK kholo → "Unknown sources / is source se install allow karo" → **Install**.

## Ya Android Studio se (agar PC hai)
- Android Studio me yeh folder **Open** karo → sync hone do → **Build → Build APK(s)**.
- APK yahan banega: `app/build/outputs/apk/debug/app-debug.apk`.

---

## App kaise use kare
1. **Accounts** tab → email/mobile + password + (optional) store name → **Login**.
   - Meesho ka login page ek baar khulega → **SMS-OTP / captcha** wahin daalo.
   - Login hote hi session save + OTP aa jayenge. (Yeh pehli baar har account ke liye.)
2. **OTP** tab → Account-wise / Courier-wise. OTP par tap = copy. **Refresh all** se sab update.
3. **Settings** tab → auto-refresh interval + **Keep running in background** on karo.
   - Agar Android app ko band kar deta hai: phone Settings me is app ke liye
     **Battery optimisation → Don't optimize / Unrestricted** karo.

---

## Agar OTP na aaye / layout badle
Meesho apna page badalta rehta hai. Do jagah "SELECTOR" comments hain jinhe tweak karna
pad sakta hai:
- `app/src/main/java/com/toolskidukan/otp/MeeshoEngine.kt` → `PARSE_JS` / `MORE_JS`
- `app/src/main/java/com/toolskidukan/otp/LoginActivity.kt` → prefill selectors

OTP parse ka rule desktop app jaisa hi hai: `"<Courier> OTP: 1234 … Total Handover Count : n"`.

## Note / limits (honest)
- Koi "private API" nahi — OTP sirf Returns page par hota hai, isliye app ko woh page
  load karna hi padta hai. Turant dikhne ke liye last OTP cache hota hai.
- Session kitni der chalega yeh **Meesho decide** karta hai; expire hone par app
  dobara login maangega (Relogin).
- Multi-account ek-ek karke refresh hote hain (taaki cookies mix na ho).
