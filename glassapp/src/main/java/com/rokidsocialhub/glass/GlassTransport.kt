package com.rokidsocialhub.glass

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.system.server.IClientCallback
import com.rokid.security.system.server.bluetooth.IBTService
import com.rokid.security.system.server.bluetooth.listener.IClassicBTListener
import com.rokid.security.system.server.message.IMessageServer
import com.rokid.security.system.server.message.listener.IMessageListener
import java.util.concurrent.CopyOnWriteArraySet

/** Thin wrapper around Rokid's official Glass3 local classic-Bluetooth services. */
object GlassTransport {
    private const val TAG = "SocialGlassTransport"

    // Match Rokid's documented/sample routing id during transport validation.
    const val CLIENT_ID = "GlassSample"

    interface Listener {
        fun onTransportStateChanged(connected: Boolean, status: String)
        fun onPayload(payload: String)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var btService: IBTService? = null
    private var messageService: IMessageServer? = null
    @Volatile private var initialized = false
    @Volatile private var connected = false
    @Volatile private var status = "Starting Glass3 service"

    private val messageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            Log.d(TAG, "RX: $msg")
            listeners.forEach { listener -> runCatching { listener.onPayload(msg) } }
        }
        override fun onAudioStream(buffer: ByteArray) { }
        override fun onStreamDataReceived(tag: String, data: ByteArray) { }
    }

    private val btListener = object : IClassicBTListener.Stub() {
        override fun onClientConnected(device: BluetoothDevice) {
            connected = true
            updateState("Phone connected")
            send("{\"v\":2,\"type\":\"sync_request\"}")
        }

        override fun onClientDisconnected(device: BluetoothDevice) {
            connected = false
            updateState("Phone disconnected — cached chats available")
        }

        override fun onConnectionRejected(device: BluetoothDevice) {
            connected = false
            updateState("Phone connection rejected")
        }
    }

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            setupReadyServices()
            updateState(if (connected) "Phone connected" else "Glass app ready — waiting for phone")
            if (connected) send("{\"v\":2,\"type\":\"sync_request\"}")
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext

        if (GlassSdk.isReady()) {
            // GlassSdk may already be alive in the system process. We still must register
            // THIS app/client id so phone messages are routed into Social Hub.
            try {
                updateStatusOnly("Glass3 SDK ready — registering Social Hub")
                GlassSdk.registerClient(CLIENT_ID, clientCallback)
                setupReadyServices()
                updateState(if (connected) "Phone connected" else "Glass app ready — waiting for phone")
            } catch (t: Throwable) {
                Log.e(TAG, "registerClient on ready SDK failed", t)
                initialized = false
                updateState("Glass client registration failed: ${t.javaClass.simpleName}")
            }
            return
        }

        GlassSdk.bindSecurityService(appContext, object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                updateStatusOnly("Glass3 service connected — registering Social Hub")
                try {
                    GlassSdk.registerClient(CLIENT_ID, clientCallback)
                } catch (t: Throwable) {
                    Log.e(TAG, "registerClient failed", t)
                    initialized = false
                    updateState("Glass client registration failed: ${t.javaClass.simpleName}")
                }
            }

            override fun onServiceDisconnected() {
                connected = false
                updateState("Glass3 service disconnected")
            }

            override fun onBindingDied() {
                connected = false
                btService = null
                messageService = null
                initialized = false
                updateState("Glass3 service restarted — reopen Social Hub")
            }
        })
    }

    private fun setupReadyServices() {
        btService = GlassSdk.getClassicBluetoothService()
        messageService = GlassSdk.getGlassMessageService()
        btService?.setClassicBTListener(btListener)
        messageService?.setMessageListener(messageListener)
        connected = btService?.isConnect ?: false
        Log.d(TAG, "services ready, btConnected=$connected")
    }

    fun send(payload: String): Boolean {
        if (!connected || messageService == null) return false
        return try {
            messageService?.sendTextMessageByClassicBT(payload)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "send failed", t)
            connected = false
            updateState("Phone disconnected — command queued")
            false
        }
    }

    fun isConnected(): Boolean = connected
    fun status(): String = status

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onTransportStateChanged(connected, status)
    }

    fun removeListener(listener: Listener) { listeners.remove(listener) }

    private fun updateStatusOnly(value: String) {
        status = value
        Log.d(TAG, value)
        listeners.forEach { listener -> runCatching { listener.onTransportStateChanged(connected, value) } }
    }

    private fun updateState(value: String) = updateStatusOnly(value)
}
