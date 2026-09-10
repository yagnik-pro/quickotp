package com.toolskidukan.otp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device storage. Everything lives in the app-private files dir, which other
 * apps cannot read on a non-rooted phone. No server, no cloud.
 *
 *   accounts.json  -> list of accounts (email, password, session cookies, slug, status)
 *   cache.json     -> last OTPs per account so the UI can show them instantly
 *   settings.json  -> auto-refresh interval + background toggle
 */
object Store {
    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    private fun read(ctx: Context, name: String, fallback: String): String =
        try { file(ctx, name).takeIf { it.exists() }?.readText() ?: fallback } catch (e: Exception) { fallback }

    private fun write(ctx: Context, name: String, text: String) =
        try { file(ctx, name).writeText(text) } catch (e: Exception) { }

    // ---------------- accounts ----------------
    fun accounts(ctx: Context): JSONArray = try { JSONArray(read(ctx, "accounts.json", "[]")) } catch (e: Exception) { JSONArray() }

    fun saveAccounts(ctx: Context, arr: JSONArray) = write(ctx, "accounts.json", arr.toString())

    fun findAccount(ctx: Context, id: String): JSONObject? {
        val a = accounts(ctx)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    fun upsertAccount(ctx: Context, obj: JSONObject) {
        val arr = accounts(ctx)
        val id = obj.optString("id")
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id") == id) { arr.put(i, obj); saveAccounts(ctx, arr); return }
        }
        arr.put(obj); saveAccounts(ctx, arr)
    }

    fun patchAccount(ctx: Context, id: String, changes: Map<String, Any?>) {
        val obj = findAccount(ctx, id) ?: return
        for ((k, v) in changes) if (v == null) obj.remove(k) else obj.put(k, v)
        upsertAccount(ctx, obj)
    }

    fun deleteAccount(ctx: Context, id: String) {
        val arr = accounts(ctx); val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") != id) out.put(arr.getJSONObject(i))
        saveAccounts(ctx, out)
        val c = cache(ctx); c.remove(id); write(ctx, "cache.json", c.toString())
    }

    fun emailExists(ctx: Context, email: String): Boolean {
        val arr = accounts(ctx)
        for (i in 0 until arr.length())
            if (arr.getJSONObject(i).optString("email").equals(email, true)) return true
        return false
    }

    // ---------------- cache ----------------
    fun cache(ctx: Context): JSONObject = try { JSONObject(read(ctx, "cache.json", "{}")) } catch (e: Exception) { JSONObject() }

    fun putCache(ctx: Context, id: String, otps: JSONArray, error: String?) {
        val c = cache(ctx)
        c.put(id, JSONObject().apply {
            put("otps", otps); put("fetchedAt", System.currentTimeMillis()); put("error", error ?: JSONObject.NULL)
        })
        write(ctx, "cache.json", c.toString())
    }

    // ---------------- settings ----------------
    fun settings(ctx: Context): JSONObject {
        val s = try { JSONObject(read(ctx, "settings.json", "{}")) } catch (e: Exception) { JSONObject() }
        if (!s.has("intervalMin")) s.put("intervalMin", 10)
        if (!s.has("background")) s.put("background", true)
        return s
    }

    fun saveSettings(ctx: Context, s: JSONObject) = write(ctx, "settings.json", s.toString())
}
