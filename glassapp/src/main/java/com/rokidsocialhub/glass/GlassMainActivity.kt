package com.rokidsocialhub.glass

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 2 milestone UI for Glass3.
 * LEFT/RIGHT navigates, ENTER selects, BACK goes up one level.
 */
class GlassMainActivity : Activity(), GlassTransport.Listener {
    private enum class Mode { LIST, CHAT, REPLY }

    private lateinit var cache: GlassCache
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var content: LinearLayout
    private lateinit var footer: TextView

    private var mode = Mode.LIST
    private var selectedConversation = 0
    private var selectedReply = 0
    private var activeConversationId: String? = null
    private var activeTitle: String = ""
    private var phoneOnline: Boolean? = null
    private var phoneQueued = 0
    private val replyOptions = arrayOf("OK", "I'll reply soon.", "Call me when you can.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cache = GlassCache(this)
        buildUi()
        GlassTransport.addListener(this)
        GlassTransport.initialize(this)
        render()
    }

    override fun onDestroy() {
        GlassTransport.removeListener(this)
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(44), dp(22), dp(20))
            setBackgroundColor(Color.BLACK)
        }

        val title = text("SOCIAL HUB", 25f, Color.rgb(85,255,136), true)
        root.addView(title)

        statusView = text("", 13f, Color.LTGRAY, false).apply { setPadding(0, dp(3), 0, dp(10)) }
        root.addView(statusView)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = text("", 12f, Color.GRAY, false).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        root.addView(footer)
        setContentView(root)
    }

    override fun onTransportStateChanged(connected: Boolean, status: String) {
        runOnUiThread {
            if (connected) flushGlassOutbox()
            renderStatus()
            if (connected && mode == Mode.LIST) requestSync()
        }
    }

    override fun onPayload(payload: String) {
        runOnUiThread {
            try {
                val packet = JSONObject(payload)
                if (packet.optInt("v", 0) > 2) return@runOnUiThread
                when (packet.optString("type")) {
                    "hello" -> {
                        phoneOnline = packet.optBoolean("online")
                        phoneQueued = packet.optInt("queued", 0)
                    }
                    "conversations" -> {
                        cache.saveConversations(packet)
                        selectedConversation = selectedConversation.coerceAtMost((cache.conversations().length() - 1).coerceAtLeast(0))
                    }
                    "messages" -> {
                        cache.saveMessages(packet)
                        if (packet.optString("id") == activeConversationId) activeTitle = packet.optString("title", activeTitle)
                    }
                    "reply_result" -> {
                        val result = packet.optString("result", "UNKNOWN")
                        showTransient("Reply: ${humanResult(result)}")
                    }
                }
            } catch (_: Throwable) { }
            render()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navigate(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { navigate(-1); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { select(); true }
            KeyEvent.KEYCODE_BACK -> { goBack(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun navigate(delta: Int) {
        when (mode) {
            Mode.LIST -> {
                val count = cache.conversations().length()
                if (count > 0) selectedConversation = wrap(selectedConversation + delta, count)
            }
            Mode.CHAT -> scrollChat(delta)
            Mode.REPLY -> selectedReply = wrap(selectedReply + delta, replyOptions.size)
        }
        render()
    }

    private fun select() {
        when (mode) {
            Mode.LIST -> openSelectedConversation()
            Mode.CHAT -> { mode = Mode.REPLY; selectedReply = 0; render() }
            Mode.REPLY -> sendReply(replyOptions[selectedReply])
        }
    }

    private fun goBack() {
        when (mode) {
            Mode.LIST -> finish()
            Mode.CHAT -> { mode = Mode.LIST; activeConversationId = null; render() }
            Mode.REPLY -> { mode = Mode.CHAT; render() }
        }
    }

    private fun render() {
        renderStatus()
        when (mode) {
            Mode.LIST -> renderConversationList()
            Mode.CHAT -> renderChat()
            Mode.REPLY -> renderReplyMenu()
        }
    }

    private fun renderStatus() {
        val phone = if (GlassTransport.isConnected()) "PHONE ✓" else "PHONE —"
        val net = when (phoneOnline) { true -> "NET ✓"; false -> "NET —"; null -> "NET ?" }
        val queued = cache.queuedCommands().length() + phoneQueued
        statusView.text = "$phone   $net   QUEUED $queued\n${GlassTransport.status()}"
    }

    private fun renderConversationList() {
        content.removeAllViews()
        val items = cache.conversations()
        if (items.length() == 0) {
            content.addView(text("No synchronized chats yet.\n\nKeep the phone nearby and open Social Hub on the phone once.", 17f, Color.WHITE, false))
        } else {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val selected = i == selectedConversation
                val source = item.optString("source")
                val title = item.optString("title")
                val preview = trim(item.optString("preview"), 80)
                val row = text("$source · $title\n$preview", if (selected) 19f else 17f, if (selected) Color.rgb(85,255,136) else Color.WHITE, selected).apply {
                    setPadding(dp(8), dp(9), dp(8), dp(9))
                    isClickable = true
                    setOnClickListener { selectedConversation = i; openSelectedConversation() }
                }
                content.addView(row)
            }
        }
        footer.text = "LEFT / RIGHT select   •   ENTER open"
    }

    private fun openSelectedConversation() {
        val items = cache.conversations()
        val item = items.optJSONObject(selectedConversation) ?: return
        activeConversationId = item.optString("id")
        activeTitle = item.optString("title")
        mode = Mode.CHAT
        requestMessages(activeConversationId ?: return)
        render()
    }

    private fun renderChat() {
        content.removeAllViews()
        content.addView(text("← $activeTitle", 21f, Color.rgb(85,255,136), true).apply { setPadding(0,0,0,dp(8)) })
        val packet = activeConversationId?.let { cache.messages(it) }
        val items = packet?.optJSONArray("items") ?: JSONArray()
        val body = StringBuilder()
        if (items.length() == 0) {
            body.append("No cached messages yet.\nWaiting for phone sync…")
        } else {
            for (i in 0 until items.length()) {
                val message = items.optJSONObject(i) ?: continue
                val who = if (message.optBoolean("outgoing")) "You" else message.optString("sender", activeTitle)
                body.append(who).append(": ").append(message.optString("body")).append("\n\n")
            }
        }
        val scroll = ScrollView(this).apply { tag = "chatScroll" }
        scroll.addView(text(body.toString(), 17f, Color.WHITE, false))
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        footer.text = "LEFT / RIGHT scroll   •   ENTER reply   •   BACK chats"
    }

    private fun scrollChat(direction: Int) {
        val scroll = content.findViewWithTag<ScrollView>("chatScroll") ?: return
        scroll.smoothScrollBy(0, direction * dp(150))
    }

    private fun renderReplyMenu() {
        content.removeAllViews()
        content.addView(text("Reply to $activeTitle", 21f, Color.rgb(85,255,136), true).apply { setPadding(0,0,0,dp(12)) })
        replyOptions.forEachIndexed { index, value ->
            val selected = index == selectedReply
            content.addView(text(if (selected) "> $value" else "  $value", 18f, if (selected) Color.rgb(85,255,136) else Color.WHITE, selected).apply {
                setPadding(dp(6), dp(12), dp(6), dp(12))
                isClickable = true
                setOnClickListener { selectedReply = index; sendReply(value) }
            })
        }
        footer.text = "LEFT / RIGHT choose   •   ENTER send   •   BACK cancel"
    }

    private fun sendReply(text: String) {
        val id = activeConversationId ?: return
        val packet = JSONObject().put("v", 2).put("type", "reply_text").put("id", id).put("text", text).toString()
        if (!GlassTransport.send(packet)) {
            cache.enqueue(packet)
            showTransient("Phone unavailable — reply queued on glasses")
        } else {
            showTransient("Reply sent to phone")
        }
        mode = Mode.CHAT
        render()
    }

    private fun requestSync() = sendOrQueue(JSONObject().put("v",2).put("type","sync_request").toString(), queueIfOffline = false)

    private fun requestMessages(id: String) = sendOrQueue(JSONObject().put("v",2).put("type","messages_request").put("id",id).toString(), queueIfOffline = false)

    private fun sendOrQueue(packet: String, queueIfOffline: Boolean) {
        if (!GlassTransport.send(packet) && queueIfOffline) cache.enqueue(packet)
    }

    private fun flushGlassOutbox() {
        val queued = cache.queuedCommands()
        if (queued.length() == 0) return
        val remaining = JSONArray()
        for (i in 0 until queued.length()) {
            val command = queued.optString(i)
            if (command.isBlank() || !GlassTransport.send(command)) remaining.put(command)
        }
        cache.replaceQueue(remaining)
    }

    private fun humanResult(result: String): String = when (result) {
        "SENT" -> "sent"
        "QUEUED_OFFLINE" -> "queued on phone — waiting for internet"
        "NO_ACTIVE_REPLY_ACTION" -> "phone has no active reply action"
        "REPLY_ACTION_EXPIRED" -> "WhatsApp/Telegram reply action expired"
        "NO_CONVERSATION" -> "conversation not found"
        else -> result.lowercase().replace('_', ' ')
    }

    private fun showTransient(message: String) {
        statusView.text = message
        statusView.postDelayed({ renderStatus() }, 2200)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.START
    }

    private fun trim(value: String, max: Int): String {
        val clean = value.replace('\n', ' ').trim()
        return if (clean.length <= max) clean else clean.substring(0, max - 1) + "…"
    }

    private fun wrap(value: Int, count: Int): Int = if (count <= 0) 0 else ((value % count) + count) % count
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
