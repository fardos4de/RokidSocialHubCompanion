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
    const val CLIENT_ID = "RokidSocialHubGlass"

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
            btService = GlassSdk.getClassicBluetoothService()
            messageService = GlassSdk.getGlassMessageService()
            btService?.setClassicBTListener(btListener)
            messageService?.setMessageListener(messageListener)
            connected = btService?.isConnect ?: false
            updateState(if (connected) "Phone connected" else "Waiting for phone")
            if (connected) send("{\"v\":2,\"type\":\"sync_request\"}")
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext

        if (GlassSdk.isReady()) {
            setupReadyServices()
            return
        }

        GlassSdk.bindSecurityService(appContext, object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                updateStatusOnly("Glass3 service connected")
                GlassSdk.registerClient(CLIENT_ID, clientCallback)
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
        updateState(if (connected) "Phone connected" else "Waiting for phone")
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
        listeners.forEach { listener -> runCatching { listener.onTransportStateChanged(connected, value) } }
    }

    private fun updateState(value: String) = updateStatusOnly(value)
}
