// GD vehicle bridge: steering-wheel key lease on the head unit's OneOS input service.
package com.geely.desktop.vehicle.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.geely.desktop.vehicle.properties.IVehicleSteeringCallback
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import java.io.Closeable

/**
 * A registration is a lease: [register] is idempotent for the same request so the client may poll,
 * and every release path (explicit, binder death, service death) hands the keys back.
 */
internal class SteeringBridge(private val context: Context) : Closeable {
    private data class Request(
        val callback: IVehicleSteeringCallback,
        val keys: IntArray,
        val intercept: Boolean,
    ) {
        fun sameAs(other: Request): Boolean =
            callback.asBinder() === other.callback.asBinder() &&
                intercept == other.intercept &&
                keys.contentEquals(other.keys)
    }

    private val lock = Any()
    private var request: Request? = null
    private var applied: Request? = null
    private var manager: IInputManager? = null
    private var listener: IInputListener.Stub? = null
    private var bound = false
    private var leaseId = 0L
    private var observed = 0L
    private var forwarded = 0L
    private var lastCallback = ""
    private var platform = "UNAVAILABLE"
    private var serviceState = "DISCONNECTED"
    private var failure = ""
    private var activeKeys = intArrayOf()
    private val lastDownAt = mutableMapOf<Int, Long>()
    private var accepted = mutableListOf<Int>()
    private var rejected = mutableListOf<Int>()
    private var requestResult = "UNAVAILABLE"
    private var death: IBinder.DeathRecipient? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val input = IInputManager.Stub.asInterface(binder)
            synchronized(lock) {
                manager = input
                serviceState = "CONNECTED"
                platform = "ONEOS"
            }
            apply()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                manager = null
                listener = null
                applied = null
                platform = "UNAVAILABLE"
                serviceState = "DISCONNECTED"
                failure = "Input service disconnected"
                accepted.clear()
                rejected.clear()
                activeKeys = intArrayOf()
                lastDownAt.clear()
            }
        }
    }

    /** The client polls this; repeated identical requests keep the same lease. */
    fun register(
        callback: IVehicleSteeringCallback,
        keyCodes: IntArray,
        intercept: Boolean,
    ): Bundle {
        val next = Request(callback, keyCodes.distinct().sorted().toIntArray(), intercept)
        synchronized(lock) {
            if (request?.sameAs(next) == true && applied != null) return status()
            releaseLocked()
            request = next
            leaseId += 1
            failure = ""
            requestResult = "PENDING"
            observed = 0
            forwarded = 0
            lastCallback = ""
            watchDeathLocked(callback)
        }
        ensureBound()
        apply()
        return status()
    }

    fun unregister(callback: IVehicleSteeringCallback) {
        synchronized(lock) {
            if (request?.callback?.asBinder() !== callback.asBinder()) return
            releaseLocked()
            request = null
            leaseId += 1
            requestResult = "RELEASED"
        }
    }

    fun status(): Bundle = synchronized(lock) {
        val current = request
        val held = accepted
        val pending = activeKeys.filter { it !in held }.toIntArray()
        Bundle().apply {
            putInt("schema", 1)
            putString("stage", stageLocked())
            putString("platform", platform)
            putBoolean("interceptRequested", current?.intercept ?: false)
            putIntArray("interceptedRawKeys", held.toIntArray())
            putIntArray("interceptedKeys", if (current?.intercept == true) held.toIntArray() else intArrayOf())
            putIntArray("rejectedKeys", rejected.toIntArray())
            putIntArray(
                "unconfirmedKeys",
                if (current?.intercept == true) pending else intArrayOf(),
            )
            putString("requestResult", requestResult)
            putLong("observedEvents", observed)
            putLong("forwardedEvents", forwarded)
            putString("lastCallback", lastCallback)
            putString("service", serviceState)
            putString("failure", failure)
            putLong("leaseId", leaseId)
            putBoolean("ready", applied != null && current != null)
            putString("servicePackage", INPUT_PACKAGE)
        }
    }

    private fun stageLocked(): String = when {
        request == null -> "IDLE"
        manager == null -> if (bound) "BRIDGE_CONNECTING" else "UNAVAILABLE"
        applied == null -> "CONFIGURING"
        rejected.isNotEmpty() -> "PARTIAL_INTERCEPTION"
        else -> "ACTIVE"
    }

    private fun ensureBound() {
        synchronized(lock) {
            if (bound || manager != null) return
            bound = runCatching {
                context.bindService(
                    Intent().setComponent(ComponentName(INPUT_PACKAGE, INPUT_CLASS)),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound) {
                failure = "Input service not installed"
                requestResult = "UNAVAILABLE"
            }
        }
    }

    private fun apply() {
        val current = synchronized(lock) { request } ?: return
        val input = synchronized(lock) { manager } ?: return
        if (synchronized(lock) { applied?.sameAs(current) == true }) return
        // A client without a profile observes passively; the OEM default key set keeps the
        // learning screen alive instead of releasing the lease.
        val keys = if (current.keys.isNotEmpty()) current.keys else LEARNING_KEYS
        val inputListener = synchronized(lock) {
            listener ?: object : IInputListener.Stub() {
                override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) {
                    // The OEM bridge drives media keys from raw press/release (action 0/1); the
                    // gesture callbacks are not guaranteed once the key is intercepted.
                    if (event == ACTION_DOWN || event == ACTION_UP) emit(keyCode, event)
                }

                override fun onShortClick(keyCode: Int, softKeyFunction: Int) {
                    emit(keyCode, ACTION_SINGLE, dedupeAfterDown = true)
                }

                override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) = Unit

                override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) = Unit

                override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) {
                    emit(keyCode, ACTION_LONG)
                }

                override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) {
                    emit(keyCode, ACTION_DOUBLE)
                }
            }.also { listener = it }
        }
        // The head unit keeps one listener per package; dropping the old one first prevents
        // duplicate callbacks when the configured keys change.
        runCatching { input.unregisterListener(inputListener, context.packageName) }
        val registered = runCatching {
            input.registerListener(inputListener, context.packageName, keys)
            true
        }.getOrDefault(false)
        if (!registered) {
            synchronized(lock) {
                failure = "registerListener rejected"
                requestResult = "FAILED"
            }
            return
        }
        val held = mutableListOf<Int>()
        val refused = mutableListOf<Int>()
        if (current.intercept) {
            current.keys.forEach { key ->
                val consumed = runCatching {
                    input.interceptKeyCode(key, context.packageName)
                }.getOrDefault(false)
                if (consumed) held += key else refused += key
            }
        }
        synchronized(lock) {
            applied = current
            activeKeys = keys
            accepted = held
            rejected = refused
            requestResult = when {
                current.keys.isEmpty() -> "LEARNING"
                current.intercept && refused.isEmpty() -> "INTERCEPTED"
                current.intercept -> "PARTIAL"
                else -> "REGISTERED"
            }
            platform = "ONEOS"
        }
        Log.i(TAG, "steering registered keys=${keys.joinToString()} intercept=${current.intercept} result=$requestResult")
    }

    private fun emit(keyCode: Int, action: Int, dedupeAfterDown: Boolean = false) {
        var callback: IVehicleSteeringCallback? = null
        var intercepted = false
        var currentLease = 0L
        var atMs = 0L
        synchronized(lock) {
            val current = request ?: return
            if (keyCode !in activeKeys) return
            if (current.intercept && keyCode !in accepted) return
            val now = SystemClock.elapsedRealtime()
            if (action == ACTION_DOWN) lastDownAt[keyCode] = now
            // While intercepting, a media key produces both a press and a short-click. The press
            // is what the OEM media mapping reacts to, so the click that follows is the duplicate.
            if (dedupeAfterDown && current.intercept && isMediaKey(keyCode)) {
                val down = lastDownAt[keyCode]
                if (down != null && now - down in 0..DOWN_DEDUPE_MS) return
            }
            callback = current.callback
            intercepted = current.intercept && keyCode in accepted
            observed += 1
            forwarded += 1
            lastCallback = "$action:$keyCode"
            currentLease = leaseId
            atMs = now
        }
        val event = Bundle().apply {
            putInt("keyCode", keyCode)
            putInt("rawKeyCode", keyCode)
            putInt("action", action)
            putLong("eventTimeMs", atMs)
            putString("source", "oneos")
            putBoolean("intercepted", intercepted)
            putLong("leaseId", currentLease)
        }
        runCatching { callback?.onKeyEvent(event) }
            .onFailure { Log.w(TAG, "steering callback failed", it) }
    }

    private fun watchDeathLocked(callback: IVehicleSteeringCallback) {
        death?.let { old -> runCatching { callback.asBinder().unlinkToDeath(old, 0) } }
        val recipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                synchronized(lock) {
                    releaseLocked()
                    request = null
                    leaseId += 1
                    requestResult = "CLIENT_DIED"
                }
            }
        }
        runCatching { callback.asBinder().linkToDeath(recipient, 0) }.onSuccess { death = recipient }
    }

    private fun releaseLocked() {
        accepted.clear()
        rejected.clear()
        activeKeys = intArrayOf()
        lastDownAt.clear()
        val current = applied ?: return
        val input = manager
        if (input != null) {
            current.keys.forEach { key -> runCatching { input.releaseKeyCode(key, context.packageName) } }
            listener?.let { runCatching { input.unregisterListener(it, context.packageName) } }
        }
        applied = null
    }

    override fun close() {
        synchronized(lock) {
            releaseLocked()
            request = null
            death = null
        }
        synchronized(lock) {
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
            manager = null
            listener = null
        }
    }

    /** Keys the client maps to a media/siri action; raw press/release would fire that action again. */
    private fun isMediaKey(keyCode: Int): Boolean = keyCode in MEDIA_KEYS

    private companion object {
        const val TAG = "GD-Bridge-Steering"
        const val INPUT_PACKAGE = "com.geely.inputservice"
        const val INPUT_CLASS = "com.geely.inputservice.InputService"

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_SINGLE = 2
        const val ACTION_LONG = 3
        const val ACTION_DOUBLE = 4
        const val DOWN_DEDUPE_MS = 500L

        /** OEM default steering keys: passive registration for the client's learning screen. */
        val LEARNING_KEYS = intArrayOf(
            5, 6, 17, 19, 20, 21, 22, 23,
            300001, 300002, 300003,
            200003, 200004, 200024, 200025, 200082, 200085, 200087, 200088,
            200110, 200164, 200176, 200231, 200400, 200600,
            210001, 210002, 210003, 210004, 210005, 210006, 210007, 210008,
            210009, 210010, 210020,
        )

        val MEDIA_KEYS = setOf(
            200085, 200087, 200088, 200231, 210005, 210006,
            85, 87, 88, 231, 110005, 110006,
        )
    }
}
