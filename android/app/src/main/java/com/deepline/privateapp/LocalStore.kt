package com.deepline.privateapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LocalStore {
    private const val PREFS = "deepline_private"
    private const val KEY_DATA = "data"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun load(c: Context): JSONObject = try { JSONObject(prefs(c).getString(KEY_DATA, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    fun save(c: Context, data: JSONObject) { prefs(c).edit().putString(KEY_DATA, data.toString()).apply() }
    fun init(c: Context): JSONObject {
        val d = load(c)
        if (!d.has("personas")) d.put("personas", JSONArray())
        if (!d.has("posts")) d.put("posts", JSONArray())
        if (!d.has("groups")) d.put("groups", JSONArray())
        if (!d.has("theme")) d.put("theme", "#0A84FF")
        if (!d.has("myName")) d.put("myName", "我")
        if (!d.has("myBio")) d.put("myBio", "")
        save(c, d)
        return d
    }
}
