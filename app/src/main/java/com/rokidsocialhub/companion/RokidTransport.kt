package com.rokidsocialhub.companion

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import java.util.LinkedHashSet
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Official Rokid phone-side transport for Social Hub.
 * Cloud services are disabled: the SDK is used only for the local Glass3 classic-Bluetooth channel.
 */
object RokidTransport {
    private const val TAG = "RokidTransport"

    // Use Rokid's own sample client id for the transport-validation milestone.
    // The glasses app registers the exact same id.
    const val GLASS_CLIENT_ID = "GlassSample"

    interface Listener {
        fun onRokidStatusChanged(status: String)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val seenDevices = LinkedHashSet<String>()

    @Volatile private var appContext: Context? = null
    @Volatile private var sdkReady = false
    @Volatile private var sdkInitializing = false
    @Volatile private var listenersAttached = false
    @Volatile private var connected = false
    @Volatile private var scanning = false
    @Volatile private var connecting = false
    @Volatile private var status = "SDK not initialized"

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

            val name = safeName(device)
            val displayName = if (name.isBlank()) "unnamed" else name
            val descriptor = "$displayName [type=${device.type}]"
            synchronized(seenDevices) {
                if (seenDevices.size < 12) seenDevices.add(descriptor)
            }
            Log.d(TAG, "BT found: $descriptor")

            if (isRokidCandidate(device, name) && !connected && !connecting) {
                updateStatus("Found $displayName — connecting…")
                connect(device)
            } else if (!connected && !connecting) {
                updateStatus("Scanning… saw ${seenDevices.size} Bluetooth device(s)")
            }
        }

        override fun onScanFinished() {
            scanning = false
            if (!connected && !connecting) {
                val seen = synchronized(seenDevices) { seenDevices.joinToString(", ").ifBlank { "none" } }
                updateStatus("No Rokid connection. Scan saw: $seen")
            }
        }

        override fun onConnect(success: Boolean) {
            connecting = false
            connected = success
            updateStatus(if (success) "Glass3 connected" else "Glass3 connection callback reported failure")
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
        if (sdkReady) {
            attachSdkListeners()
            return
        }
        if (sdkInitializing) return

        sdkInitializing = true
        updateStatus("Initializing Rokid SDK…")

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
                sdkInitializing = false
                sdkReady = result.isSuccess
                if (result.isSuccess) {
                    attachSdkListeners()
                    updateStatus("SDK ready — Glass3 disconnected")
                    if (hasBluetoothPermissions(context)) ensureConnected()
                } else {
                    // Important: leave retry possible after runtime permissions have been granted.
                    updateStatus("Rokid SDK initialization failed — tap Connect to retry")
                }
            }
        } catch (t: Throwable) {
            sdkInitializing = false
            sdkReady = false
            Log.e(TAG, "SDK initialization failed", t)
            updateStatus("Rokid SDK error: ${t.javaClass.simpleName} — tap Connect to retry")
        }
    }

    private fun attachSdkListeners() {
        if (listenersAttached) return
        try {
            PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(btListener)
            PSecuritySDK.getMessageService()?.addMessageListener(messageListener)
            listenersAttached = true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to attach Rokid listeners", t)
            listenersAttached = false
            updateStatus("Rokid listener setup failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Entry point used by the UI after Bluetooth permissions are granted.
     * Prefer an already-paired Rokid/Glass device, then fall back to discovery.
     */
    @JvmStatic
    fun ensureConnected() {
        val context = appContext ?: return
        if (!hasBluetoothPermissions(context)) {
            updateStatus("Bluetooth permission required")
            return
        }
        if (!sdkReady) {
            initialize(context)
            return
        }

        try {
            val service = PSecuritySDK.getClassicBlueToothClientService()
            if (service?.isConnected() == true) {
                connected = true
                connecting = false
                scanning = false
                updateStatus("Glass3 already connected")
                sendFullSync()
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not query current Rokid BT state", t)
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            updateStatus("Bluetooth is not supported on this phone")
            return
        }
        if (!adapter.isEnabled) {
            updateStatus("Bluetooth is OFF — enable it and tap Connect again")
            return
        }

        try {
            val pairedCandidates = adapter.bondedDevices
                .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .filter { isRokidCandidate(it, safeName(it)) }
            if (pairedCandidates.isNotEmpty()) {
                val target = pairedCandidates.first()
                updateStatus("Using paired ${safeName(target).ifBlank { "Rokid glasses" }} — connecting…")
                connect(target)
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to inspect paired devices", t)
        }

        startScan()
    }

    @JvmStatic
    fun startScan() {
        val context = appContext ?: return
        if (!sdkReady) {
            initialize(context)
            return
        }
        if (!hasBluetoothPermissions(context)) {
            updateStatus("Bluetooth scan permission required")
            return
        }
        try {
            seenDevices.clear()
            scanning = true
            updateStatus("Scanning for Rokid/Glass Bluetooth device…")
            PSecuritySDK.getClassicBlueToothClientService()?.startScan(15_000L)
        } catch (t: Throwable) {
            scanning = false
            Log.e(TAG, "BT scan failed", t)
            updateStatus("Bluetooth scan failed: ${t.javaClass.simpleName}")
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (connected || connecting) return
        try {
            scanning = false
            connecting = true
            val name = safeName(device).ifBlank { "Rokid glasses" }
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
            connected = false
            Log.e(TAG, "BT connect failed", t)
            updateStatus("Connection error: ${t.javaClass.simpleName}")
        }
    }

    private fun isRokidCandidate(device: BluetoothDevice, name: String): Boolean {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return false
        if (name.isBlank()) return false
        val lower = name.lowercase()
        return lower.contains("glass3") || lower.contains("rokid") || lower.contains("glass")
    }

    private fun safeName(device: BluetoothDevice): String {
        return try { device.name ?: "" } catch (_: SecurityException) { "" }
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

    @JvmStatic fun getStatus(): String = status
    @JvmStatic fun isConnected(): Boolean = connected
    @JvmStatic fun addListener(listener: Listener) { listeners.add(listener) }
    @JvmStatic fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun updateStatus(value: String) {
        status = value
        Log.d(TAG, value)
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
