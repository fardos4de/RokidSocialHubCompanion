package com.rokidsocialhub.companion

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.data.EngineParam
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.phone.sdk.base.data.NetServiceType
import com.rokid.security.phone.sdk.base.data.UserAuthInfo
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Official Rokid phone-side transport for Social Hub.
 * Cloud services are disabled: the SDK is used only for the local Glass3 classic-Bluetooth channel.
 */
object RokidTransport {
    private const val TAG = "RokidTransport"
    const val GLASS_CLIENT_ID = "RokidSocialHubGlass"

    interface Listener {
        fun onRokidStatusChanged(status: String)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var appContext: Context? = null
    @Volatile private var sdkReady = false
    @Volatile private var connected = false
    @Volatile private var scanning = false
    @Volatile private var connecting = false
    @Volatile private var initialized = false
    @Volatile private var status = "SDK starting"

    private val messageListener = object : IMessageListener {
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            Log.d(TAG, "RX from glass client=$clientId: $msg")
            handleIncoming(msg)
        }
    }

    private val btListener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) {
            val context = appContext ?: return
            if (!hasBluetoothPermissions(context)) return
            val name = try { device.name ?: "" } catch (_: SecurityException) { "" }
            if (name.contains("Glass3", ignoreCase = true) && device.type != BluetoothDevice.DEVICE_TYPE_LE && !connected && !connecting) {
                connect(device)
            }
        }

        override fun onScanFinished() {
            scanning = false
            if (!connected && !connecting) updateStatus("Glass3 not found — tap connect to scan again")
        }

        override fun onConnect(success: Boolean) {
            connecting = false
            connected = success
            updateStatus(if (success) "Glass3 connected" else "Glass3 connection failed")
            if (success) sendFullSync()
        }

        override fun onConnectionRejected(reason: String, code: Int) {
            connecting = false
            connected = false
            updateStatus("Connection rejected ($code): $reason")
        }
    }

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        updateStatus("Initializing Rokid SDK")

        try {
            val clientIds = arrayListOf(GLASS_CLIENT_ID)
            val auth = UserAuthInfo(appId = "", secret = "")
            val disabledNetworkServices: List<NetServiceType> = arrayListOf(NetServiceType.ALL)
            val param = EngineParam(
                clientIds = clientIds,
                userAuthInfo = auth,
                banServiceList = disabledNetworkServices,
                envType = EnvType.Companion.PUBLIC
            )
            PSecuritySDK.getMobileEngineService().initSDK(param) { result ->
                sdkReady = result.isSuccess
                if (result.isSuccess) {
                    PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(btListener)
                    PSecuritySDK.getMessageService()?.addMessageListener(messageListener)
                    updateStatus("SDK ready — Glass3 disconnected")
                    if (hasBluetoothPermissions(context)) startScan()
                } else {
                    updateStatus("Rokid SDK initialization failed")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SDK initialization failed", t)
            updateStatus("Rokid SDK error: ${t.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun startScan() {
        val context = appContext ?: return
        if (!sdkReady) {
            updateStatus("Rokid SDK is not ready yet")
            return
        }
        if (!hasBluetoothPermissions(context)) {
            updateStatus("Bluetooth scan permission required")
            return
        }
        try {
            scanning = true
            updateStatus("Scanning for Glass3…")
            PSecuritySDK.getClassicBlueToothClientService()?.startScan(15_000L)
        } catch (t: Throwable) {
            scanning = false
            Log.e(TAG, "BT scan failed", t)
            updateStatus("Bluetooth scan failed: ${t.javaClass.simpleName}")
        }
    }

    private fun connect(device: BluetoothDevice) {
        try {
            scanning = false
            connecting = true
            val name = try { device.name ?: "Glass3" } catch (_: SecurityException) { "Glass3" }
            updateStatus("Connecting to $name…")
            PSecuritySDK.getClassicBlueToothClientService()?.stopScan()
            PSecuritySDK.getClassicBlueToothClientService()?.connectToServer(device) { success ->
                connecting = false
                connected = success
                updateStatus(if (success) "$name connected" else "Could not connect to $name")
                if (success) sendFullSync()
            }
        } catch (t: Throwable) {
            connecting = false
            Log.e(TAG, "BT connect failed", t)
            updateStatus("Connection error: ${t.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun pushRecentConversations() {
        if (!connected) return
        val context = appContext ?: return
        send(SocialProtocol.conversations(SocialDb.get(context).recentConversations(5)))
    }

    private fun sendFullSync() {
        val context = appContext ?: return
        send(SocialProtocol.hello(NetworkState.get(context).isOnline, SocialDb.get(context).outboxCount()))
        pushRecentConversations()
    }

    private fun handleIncoming(raw: String) {
        val context = appContext ?: return
        try {
            val json = JSONObject(raw)
            if (json.optInt("v", 0) > SocialProtocol.VERSION) return
            when (json.optString("type")) {
                "sync_request", "ping" -> sendFullSync()
                "messages_request" -> {
                    val conversationId = json.optString("id")
                    val conversation = SocialDb.get(context).conversationById(conversationId)
                    if (conversation != null) {
                        send(SocialProtocol.messages(conversation, SocialDb.get(context).recentMessages(conversationId, 20)))
                    }
                }
                "reply_text" -> {
                    val conversationId = json.optString("id")
                    val text = json.optString("text")
                    val conversation = SocialDb.get(context).conversationById(conversationId)
                    val result = if (conversation == null) "NO_CONVERSATION" else ReplyDispatcher.sendOrQueue(context, conversation, text)
                    send(SocialProtocol.replyResult(conversationId, result))
                    if (conversation != null) {
                        send(SocialProtocol.messages(conversation, SocialDb.get(context).recentMessages(conversationId, 20)))
                        pushRecentConversations()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid glass payload: $raw", t)
        }
    }

    private fun send(payload: String) {
        if (!connected) return
        try {
            PSecuritySDK.getMessageService()?.sendTextMessageByClassicBT(payload, GLASS_CLIENT_ID)
        } catch (t: Throwable) {
            connected = false
            Log.e(TAG, "Send failed", t)
            updateStatus("Glass3 disconnected")
        }
    }

    @JvmStatic
    fun getStatus(): String = status

    @JvmStatic
    fun isConnected(): Boolean = connected

    @JvmStatic
    fun addListener(listener: Listener) { listeners.add(listener) }

    @JvmStatic
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun updateStatus(value: String) {
        status = value
        listeners.forEach { listener ->
            try { listener.onRokidStatusChanged(value) } catch (_: Throwable) { }
        }
    }

    private fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
