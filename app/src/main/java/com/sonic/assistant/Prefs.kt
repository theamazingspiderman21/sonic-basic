package com.sonic.assistant

import android.content.Context
import org.json.JSONObject

/** Keys + long-term memory (your personalisation layer). App-private storage. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sonic", Context.MODE_PRIVATE)

    var anthropicKey: String
        get() = sp.getString("anthropic_key", "") ?: ""
        set(v) = sp.edit().putString("anthropic_key", v.trim()).apply()

    var porcupineKey: String
        get() = sp.getString("porcupine_key", "") ?: ""
        set(v) = sp.edit().putString("porcupine_key", v.trim()).apply()

    var mapsKey: String
        get() = sp.getString("maps_key", "") ?: ""
        set(v) = sp.edit().putString("maps_key", v.trim()).apply()

    /** Everyday model: fast, handles tool calls. */
    var brainModel: String
        get() = sp.getString("brain_model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        set(v) = sp.edit().putString("brain_model", v).apply()

    // ---- memory ----
    private fun mem(): JSONObject = JSONObject(sp.getString("memory", "{}") ?: "{}")

    fun remember(key: String, value: String) {
        val m = mem(); m.put(key, value); sp.edit().putString("memory", m.toString()).apply()
    }

    fun forget(key: String) {
        val m = mem(); m.remove(key); sp.edit().putString("memory", m.toString()).apply()
    }

    fun recall(key: String): String? = mem().optString(key).ifBlank { null }

    fun memoryDump(): String {
        val m = mem()
        if (m.length() == 0) return "(nothing remembered yet)"
        return m.keys().asSequence().joinToString("\n") { "- $it: ${m.getString(it)}" }
    }
}
