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

/** Official Rokid phone-side transport with persistent connection diagnostics. */
object RokidTransport {
    private const val TAG = "RokidTransport"
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

    private fun d(message: String) {
        Log.d(TAG, message)
        DiagnosticLog.log(TAG, message)
    }

    private fun w(message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, message) else Log.w(TAG, message, t)
        DiagnosticLog.logError(TAG, message, t)
    }

    private fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, message) else Log.e(TAG, message, t)
        DiagnosticLog.logError(TAG, message, t)
    }

    private val messageListener = object : IMessageListener {
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            // Do not persist message contents. Only protocol metadata is recorded.
            val type = runCatching { JSONObject(msg).optString("type", "unknown") }.getOrDefault("unparseable")
            d("RX classic-BT payload clientId='$clientId' type='$type' length=${msg.length}")
            handleIncoming(msg)
        }
    }

    private val btListener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) {
            val context = appContext ?: return
            if (!hasBluetoothPermissions(context)) {
                w("onDeviceFound ignored because Bluetooth permissions are missing")
                return
            }

            val name = safeName(device)
            val displayName = if (name.isBlank()) "unnamed" else name
            val address = safeAddress(device)
            val descriptor = "$displayName address=$address type=${device.type} bond=${device.bondState}"
            synchronized(seenDevices) {
                if (seenDevices.size < 30) seenDevices.add(descriptor)
            }
            d("BT scan found: $descriptor")

            if (isRokidCandidate(device, name) && !connected && !connecting) {
                d("Candidate accepted: '$displayName' ($address)")
                updateStatus("Found $displayName — connecting…")
                connect(device)
            } else if (!connected && !connecting) {
                d("Candidate rejected by name/type filter: '$displayName' type=${device.type}")
                updateStatus("Scanning… saw ${seenDevices.size} Bluetooth device(s)")
            }
        }

        override fun onScanFinished() {
            scanning = false
            val seen = synchronized(seenDevices) { seenDevices.joinToString(" | ").ifBlank { "none" } }
            d("BT scan finished. connected=$connected connecting=$connecting seen=[$seen]")
            if (!connected && !connecting) updateStatus("No Rokid connection. Scan saw: $seen")
        }

        override fun onConnect(success: Boolean) {
            connecting = false
            connected = success
            d("IClassicBTClientListener.onConnect success=$success")
            updateStatus(if (success) "Glass3 connected" else "Glass3 connection callback reported failure")
            if (success) sendFullSync()
        }

        override fun onConnectionRejected(reason: String, code: Int) {
            connecting = false
            connected = false
            e("Connection rejected code=$code reason='$reason'")
            updateStatus("Connection rejected ($code): $reason")
        }
    }

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
        DiagnosticLog.initialize(context)
        d("initialize() called sdkReady=$sdkReady sdkInitializing=$sdkInitializing listenersAttached=$listenersAttached")
        d("Bluetooth permissions: ${permissionSummary(context)}")

        if (sdkReady) {
            d("SDK already marked ready; attaching listeners")
            attachSdkListeners()
            return
        }
        if (sdkInitializing) {
            d("SDK initialization already in progress; returning")
            return
        }

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
            d("Calling Rokid initSDK clientIds=$clientIds disabledNetworkServices=ALL env=PUBLIC")
            PSecuritySDK.getMobileEngineService().initSDK(param) { result ->
                sdkInitializing = false
                sdkReady = result.isSuccess
                d("Rokid initSDK callback isSuccess=${result.isSuccess}")
                if (result.isSuccess) {
                    val classicPresent = PSecuritySDK.getClassicBlueToothClientService() != null
                    val messagePresent = PSecuritySDK.getMessageService() != null
                    d("SDK services after init: classicBT=$classicPresent messageService=$messagePresent")
                    attachSdkListeners()
                    updateStatus("SDK ready — Glass3 disconnected")
                    if (hasBluetoothPermissions(context)) {
                        d("Permissions available after SDK init; calling ensureConnected()")
                        ensureConnected()
                    } else {
                        d("SDK ready but Bluetooth permissions are not available yet")
                    }
                } else {
                    updateStatus("Rokid SDK initialization failed — tap Connect to retry")
                }
            }
        } catch (t: Throwable) {
            sdkInitializing = false
            sdkReady = false
            e("SDK initialization threw exception", t)
            updateStatus("Rokid SDK error: ${t.javaClass.simpleName} — tap Connect to retry")
        }
    }

    private fun attachSdkListeners() {
        if (listenersAttached) {
            d("attachSdkListeners(): listeners already attached")
            return
        }
        try {
            val classicService = PSecuritySDK.getClassicBlueToothClientService()
            val messageService = PSecuritySDK.getMessageService()
            d("attachSdkListeners(): classicService=${classicService != null} messageService=${messageService != null}")
            if (classicService == null || messageService == null) {
                listenersAttached = false
                updateStatus("Rokid SDK service unavailable — retry connection")
                return
            }
            classicService.addClientListener(btListener)
            messageService.addMessageListener(messageListener)
            listenersAttached = true
            d("Rokid Bluetooth and message listeners attached")
        } catch (t: Throwable) {
            e("Unable to attach Rokid listeners", t)
            listenersAttached = false
            updateStatus("Rokid listener setup failed: ${t.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun ensureConnected() {
        val context = appContext ?: return
        d("ensureConnected() invoked")
        DiagnosticLog.logEnvironmentSnapshot(context, "ensureConnected")

        if (!hasBluetoothPermissions(context)) {
            d("ensureConnected(): required Bluetooth permissions missing")
            updateStatus("Bluetooth permission required")
            return
        }
        if (!sdkReady) {
            d("ensureConnected(): SDK not ready; initialize again")
            initialize(context)
            return
        }

        try {
            val service = PSecuritySDK.getClassicBlueToothClientService()
            d("Classic BT service present=${service != null}")
            val alreadyConnected = service?.isConnected() == true
            d("Classic BT service isConnected=$alreadyConnected")
            if (alreadyConnected) {
                connected = true
                connecting = false
                scanning = false
                updateStatus("Glass3 already connected")
                sendFullSync()
                return
            }
        } catch (t: Throwable) {
            w("Could not query current Rokid BT state", t)
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            e("Android Bluetooth adapter is unavailable")
            updateStatus("Bluetooth is not supported on this phone")
            return
        }
        if (!adapter.isEnabled) {
            d("Android Bluetooth adapter is OFF")
            updateStatus("Bluetooth is OFF — enable it and tap Connect again")
            return
        }

        try {
            val allBonded = adapter.bondedDevices.toList()
            d("Android bonded device count=${allBonded.size}")
            allBonded.forEachIndexed { index, device ->
                d("Bonded[$index]: name='${safeName(device)}' address=${safeAddress(device)} type=${device.type} bond=${device.bondState}")
            }
            val pairedCandidates = allBonded
                .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .filter { isRokidCandidate(it, safeName(it)) }
            d("Paired Rokid/Glass candidate count=${pairedCandidates.size}")
            if (pairedCandidates.isNotEmpty()) {
                val target = pairedCandidates.first()
                d("Selecting paired candidate '${safeName(target)}' address=${safeAddress(target)}")
                updateStatus("Using paired ${safeName(target).ifBlank { "Rokid glasses" }} — connecting…")
                connect(target)
                return
            }
        } catch (t: Throwable) {
            w("Unable to inspect paired devices", t)
        }

        d("No paired Rokid candidate selected; falling back to SDK scan")
        startScan()
    }

    @JvmStatic
    fun startScan() {
        val context = appContext ?: return
        d("startScan() called sdkReady=$sdkReady permissions=${permissionSummary(context)}")
        if (!sdkReady) {
            initialize(context)
            return
        }
        if (!hasBluetoothPermissions(context)) {
            updateStatus("Bluetooth scan permission required")
            return
        }
        try {
            val service = PSecuritySDK.getClassicBlueToothClientService()
            if (service == null) {
                e("Cannot scan: Rokid Classic BT service is null")
                updateStatus("Rokid Bluetooth service unavailable")
                return
            }
            seenDevices.clear()
            scanning = true
            updateStatus("Scanning for Rokid/Glass Bluetooth device…")
            d("Calling Rokid startScan(15000ms)")
            service.startScan(15_000L)
        } catch (t: Throwable) {
            scanning = false
            e("BT scan threw exception", t)
            updateStatus("Bluetooth scan failed: ${t.javaClass.simpleName}")
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (connected || connecting) {
            d("connect() ignored connected=$connected connecting=$connecting")
            return
        }
        try {
            scanning = false
            connecting = true
            val name = safeName(device).ifBlank { "Rokid glasses" }
            val address = safeAddress(device)
            d("connect() target name='$name' address=$address type=${device.type} bond=${device.bondState}")
            updateStatus("Connecting to $name…")
            val service = PSecuritySDK.getClassicBlueToothClientService()
            if (service == null) {
                connecting = false
                e("connect(): Rokid Classic BT service is null")
                updateStatus("Rokid Bluetooth service unavailable")
                return
            }
            runCatching { service.stopScan() }
                .onFailure { w("stopScan before connect failed", it) }
            d("Calling connectToServer($address)")
            service.connectToServer(device) { success ->
                connecting = false
                connected = success
                d("connectToServer completion callback success=$success target='$name' address=$address")
                updateStatus(if (success) "$name connected" else "Could not connect to $name")
                if (success) sendFullSync()
            }
        } catch (t: Throwable) {
            connecting = false
            connected = false
            e("BT connect threw exception", t)
            updateStatus("Connection error: ${t.javaClass.simpleName}")
        }
    }

    private fun isRokidCandidate(device: BluetoothDevice, name: String): Boolean {
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return false
        if (name.isBlank()) return false
        val lower = name.lowercase()
        return lower.contains("glass3") || lower.contains("rokid") || lower.contains("glass")
    }

    private fun safeName(device: BluetoothDevice): String = try { device.name ?: "" } catch (_: SecurityException) { "" }
    private fun safeAddress(device: BluetoothDevice): String = try { device.address ?: "unknown" } catch (_: SecurityException) { "permission-denied" }

    @JvmStatic
    fun pushRecentConversations() {
        if (!connected) {
            d("pushRecentConversations() skipped because transport is not connected")
            return
        }
        val context = appContext ?: return
        send(SocialProtocol.conversations(SocialDb.get(context).recentConversations(5)))
    }

    private fun sendFullSync() {
        val context = appContext ?: return
        d("sendFullSync()")
        send(SocialProtocol.hello(NetworkState.get(context).isOnline, SocialDb.get(context).outboxCount()))
        pushRecentConversations()
    }

    private fun handleIncoming(raw: String) {
        val context = appContext ?: return
        try {
            val json = JSONObject(raw)
            if (json.optInt("v", 0) > SocialProtocol.VERSION) {
                w("Ignoring payload with newer protocol version=${json.optInt("v", 0)}")
                return
            }
            val type = json.optString("type")
            d("handleIncoming type='$type'")
            when (type) {
                "sync_request", "ping" -> sendFullSync()
                "messages_request" -> {
                    val conversationId = json.optString("id")
                    val conversation = SocialDb.get(context).conversationById(conversationId)
                    if (conversation != null) send(SocialProtocol.messages(conversation, SocialDb.get(context).recentMessages(conversationId, 20)))
                }
                "reply_text" -> {
                    val conversationId = json.optString("id")
                    val text = json.optString("text")
                    val conversation = SocialDb.get(context).conversationById(conversationId)
                    val result = if (conversation == null) "NO_CONVERSATION" else ReplyDispatcher.sendOrQueue(context, conversation, text)
                    d("reply_text processed conversationFound=${conversation != null} result=$result textLength=${text.length}")
                    send(SocialProtocol.replyResult(conversationId, result))
                    if (conversation != null) {
                        send(SocialProtocol.messages(conversation, SocialDb.get(context).recentMessages(conversationId, 20)))
                        pushRecentConversations()
                    }
                }
            }
        } catch (t: Throwable) {
            e("Invalid glass payload (contents omitted from diagnostic log)", t)
        }
    }

    private fun send(payload: String) {
        if (!connected) {
            d("send() skipped because connected=false")
            return
        }
        try {
            val type = runCatching { JSONObject(payload).optString("type", "unknown") }.getOrDefault("unparseable")
            d("TX classic-BT type='$type' length=${payload.length} clientId='$GLASS_CLIENT_ID'")
            val service = PSecuritySDK.getMessageService()
            if (service == null) {
                e("Message service became null while connected")
                connected = false
                updateStatus("Rokid message service unavailable")
                return
            }
            service.sendTextMessageByClassicBT(payload, GLASS_CLIENT_ID)
        } catch (t: Throwable) {
            connected = false
            e("Send failed", t)
            updateStatus("Glass3 disconnected")
        }
    }

    @JvmStatic fun getStatus(): String = status
    @JvmStatic fun isConnected(): Boolean = connected
    @JvmStatic fun addListener(listener: Listener) { listeners.add(listener) }
    @JvmStatic fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun updateStatus(value: String) {
        status = value
        d("STATUS => $value")
        listeners.forEach { listener ->
            try { listener.onRokidStatusChanged(value) } catch (t: Throwable) { w("Status listener threw exception", t) }
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

    private fun permissionSummary(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "scan=${context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED}, connect=${context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED}"
        } else {
            "fineLocation=${context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED}"
        }
    }
}
