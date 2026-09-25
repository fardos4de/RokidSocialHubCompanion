package com.rokidsocialhub.glass

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent cache so the last synchronized chats remain readable without the phone. */
class GlassCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("social_hub_glass", Context.MODE_PRIVATE)

    fun conversations(): JSONArray = parseArray(prefs.getString("conversations", null))

    fun saveConversations(packet: JSONObject) {
        prefs.edit().putString("conversations", packet.optJSONArray("items")?.toString() ?: "[]").apply()
    }

    fun messages(conversationId: String): JSONObject? {
        val raw = prefs.getString("messages:$conversationId", null) ?: return null
        return try { JSONObject(raw) } catch (_: Throwable) { null }
    }

    fun saveMessages(packet: JSONObject) {
        val id = packet.optString("id")
        if (id.isBlank()) return
        prefs.edit().putString("messages:$id", packet.toString()).apply()
    }

    fun enqueue(command: String) {
        val items = queuedCommands()
        items.put(command)
        prefs.edit().putString("outbox", items.toString()).apply()
    }

    fun queuedCommands(): JSONArray = parseArray(prefs.getString("outbox", null))

    fun replaceQueue(items: JSONArray) {
        prefs.edit().putString("outbox", items.toString()).apply()
    }

    private fun parseArray(raw: String?): JSONArray {
        if (raw.isNullOrBlank()) return JSONArray()
        return try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
    }
}
