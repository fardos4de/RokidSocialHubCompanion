package com.rokidsocialhub.companion

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent diagnostic logger for Rokid connection troubleshooting.
 * It intentionally does not log WhatsApp/Telegram message bodies.
 */
object DiagnosticLog {
    private const val FILE_NAME = "rokid-social-hub-diagnostic.txt"
    private val lock = Any()
    @Volatile private var appContext: Context? = null

    @JvmStatic
    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        val file = fileOrNull() ?: return
        if (!file.exists()) startNewSession()
    }

    @JvmStatic
    fun startNewSession() {
        val context = appContext ?: return
        synchronized(lock) {
            val file = fileOrNull() ?: return
            file.parentFile?.mkdirs()
            val header = buildString {
                appendLine("Rokid Social Hub diagnostic log")
                appendLine("Session started: ${now()}")
                appendLine("App package: ${context.packageName}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Bluetooth permissions: ${permissionSummary(context)}")
                appendLine("Bluetooth adapter: ${bluetoothSummary(context)}")
                appendLine("NOTE: chat/message contents are intentionally not logged")
                appendLine("------------------------------------------------------------")
            }
            file.writeText(header)
        }
    }

    @JvmStatic
    fun log(tag: String, message: String) {
        append(tag, message, null)
    }

    @JvmStatic
    fun logError(tag: String, message: String, throwable: Throwable?) {
        append(tag, message, throwable)
    }

    private fun append(tag: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            val file = fileOrNull() ?: return
            file.parentFile?.mkdirs()
            val line = buildString {
                append("[")
                append(now())
                append("] [")
                append(Thread.currentThread().name)
                append("] [")
                append(tag)
                append("] ")
                appendLine(message.replace('\n', ' '))
                if (throwable != null) {
                    appendLine("${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
                    throwable.stackTrace.take(20).forEach { appendLine("    at $it") }
                    throwable.cause?.let { cause ->
                        appendLine("Caused by: ${cause.javaClass.name}: ${cause.message.orEmpty()}")
                        cause.stackTrace.take(10).forEach { appendLine("    at $it") }
                    }
                }
            }
            runCatching { file.appendText(line) }
        }
    }

    @JvmStatic
    fun getText(): String = synchronized(lock) {
        runCatching { fileOrNull()?.readText().orEmpty() }.getOrDefault("")
    }

    @JvmStatic
    fun getSuggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "rokid-social-hub-diagnostic-$stamp.txt"
    }

    @JvmStatic
    fun logEnvironmentSnapshot(context: Context, reason: String) {
        initialize(context)
        log("Environment", "Snapshot reason=$reason")
        log("Environment", "permissions=${permissionSummary(context)}")
        log("Environment", "bluetooth=${bluetoothSummary(context)}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                log("Environment", "pairedDevices=unavailable (BLUETOOTH_CONNECT not granted)")
                return
            }
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            if (adapter == null) {
                log("Environment", "pairedDevices=adapter unavailable")
                return
            }
            val devices = adapter.bondedDevices
            log("Environment", "pairedDeviceCount=${devices.size}")
            devices.forEachIndexed { index, device ->
                val name = runCatching { device.name ?: "" }.getOrDefault("")
                val address = runCatching { device.address ?: "" }.getOrDefault("")
                log("Environment", "paired[$index] name='$name' address=$address type=${device.type} bondState=${device.bondState}")
            }
        } catch (t: Throwable) {
            logError("Environment", "Unable to enumerate paired devices", t)
        }
    }

    private fun fileOrNull(): File? {
        val context = appContext ?: return null
        return File(context.filesDir, "diagnostics/$FILE_NAME")
    }

    private fun permissionSummary(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            "BLUETOOTH_SCAN=$scan, BLUETOOTH_CONNECT=$connect"
        } else {
            val location = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            "ACCESS_FINE_LOCATION=$location"
        }
    }

    private fun bluetoothSummary(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return "permission unavailable"
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: return "adapter unavailable"
            "enabled=${adapter.isEnabled}, state=${adapter.state}, bonded=${adapter.bondedDevices.size}"
        } catch (t: Throwable) {
            "error=${t.javaClass.simpleName}:${t.message.orEmpty()}"
        }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
