// GD vehicle bridge: one-shot property reads through the head unit's vendor APIs.
package com.geely.desktop.vehicle.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class Reading(val value: Double?, val state: String)

/**
 * Reads a binding exactly once. Every channel is optional: a head unit that lacks the vendor
 * library simply reports the channel as unavailable instead of fabricating a value.
 */
internal class PropertyReader(private val context: Context) : Closeable {
    private val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val car: Any? by lazy { loadCar() }
    @Volatile private var functionCache: Any? = null
    @Volatile private var functionTriedAt = 0L
    @Volatile private var sensorCache: Any? = null
    @Volatile private var sensorTriedAt = 0L
    private val ecarx by lazy { EcarxPropertyService(context) }

    val connected: Boolean get() = function() != null

    /** Channel availability, surfaced verbatim in the bridge status. */
    val carLoaded: Boolean get() = car != null
    val functionReady: Boolean get() = function() != null
    val sensorReady: Boolean get() = sensor() != null
    val ecarxReady: Boolean get() = ecarx.available

    /** The vendor object names, so a firmware whose API differs can be diagnosed without logs. */
    val carClassName: String get() = car?.javaClass?.name.orEmpty().ifEmpty { "none" }

    /** Zero-arg getters of every vendor manager, so a report can point at lamp and signal APIs. */
    val managerApis: String get() {
        val type = car?.javaClass ?: return "none"
        return type.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .mapNotNull { getter ->
                val manager = runCatching { getter.invoke(car) }.getOrNull() ?: return@mapNotNull null
                if (manager is String || manager is Number || manager is Boolean) return@mapNotNull null
                val calls = manager.javaClass.methods
                    .filter { it.parameterCount == 0 && it.name.startsWith("get") }
                    .map { it.name }.distinct().sorted().joinToString("/")
                getter.name + "=" + calls
            }
            .distinct()
            .joinToString(";")
    }

    val carApi: String get() {
        val type = car?.javaClass ?: return "none"
        return type.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .map { it.name }
            .distinct()
            .sorted()
            .joinToString(",")
    }

    /** The vendor object may appear only after the car service finishes booting; retry on demand. */
    private fun function(): Any? {
        functionCache?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        if (functionTriedAt != 0L && now - functionTriedAt < RETRY_MS) return null
        functionTriedAt = now
        val resolved = resolve(
            car, "getICarFunction", "getCarFunction", "getFunction",
            "getCarFunctionManager", "getICarFunctionManager",
        )
        functionCache = resolved
        return resolved
    }

    private fun sensor(): Any? {
        sensorCache?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        if (sensorTriedAt != 0L && now - sensorTriedAt < RETRY_MS) return null
        sensorTriedAt = now
        val resolved = resolve(car, "getSensorManager", "getISensor", "getSensor")
        sensorCache = resolved
        return resolved
    }

    private fun resolve(target: Any?, vararg names: String): Any? {
        val type = target?.javaClass ?: return null
        names.forEach { name ->
            val value = runCatching {
                type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(target)
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }


    fun read(binding: Binding): Reading {
        if (!connected) return Reading(null, "BRIDGE_UNAVAILABLE")
        return when (binding.chain) {
            "gd_vehicle_bridge" -> Reading(xuiCustomize(binding) ?: xuiFunction(binding), "READ_OK")
                .takeIf { it.value != null } ?: Reading(null, "PROPERTY_UNAVAILABLE")
            "adapt_api" -> Reading(
                xuiSensor(binding) ?: xuiFunction(binding),
                "READ_OK",
            ).takeIf { it.value != null } ?: Reading(null, "PROPERTY_UNAVAILABLE")
            "ecarx_service", "ecarx_property_aidl", "xui_ecarx_proxy" ->
                Reading(ecarx.read(binding), "READ_OK")
                    .takeIf { it.value != null } ?: Reading(null, "PROPERTY_UNAVAILABLE")
            else -> Reading(null, "CHAIN_UNAVAILABLE")
        }
    }

    private fun loadCar(): Any? = runCatching {
        val type = Class.forName("com.ecarx.xui.adaptapi.car.Car")
        val create = type.getMethod("create", Context::class.java)
        create.invoke(null, context)
    }.getOrNull()

    /** Newer vendor builds expose the raw customization functions, e.g. FX11 signals. */
    private fun xuiCustomize(binding: Binding): Double? {
        val call = call(function(), "getCustomizeFunctionValue", binding.propertyId, binding.areaInt)
            ?: call(function(), "getCustomizeFunctionValue", binding.propertyId)
        return call?.toDouble()
    }

    /** Published ADAPT-API functions and sensors. */
    private fun xuiFunction(binding: Binding): Double? =
        (call(function(), "getFunctionValue", binding.propertyId, binding.areaInt)
            ?: call(function(), "getFunctionValue", binding.propertyId))?.toDouble()

    private fun xuiSensor(binding: Binding): Double? {
        subscribe(binding.propertyId)
        return (call(sensor(), "getSensorLatestValue", binding.propertyId)
            ?: call(sensor(), "getSensorValue", binding.propertyId))?.toDouble()
    }

    /** Sensors only report once a listener is registered, exactly like the vendor adapter. */
    private fun subscribe(id: Int) {
        if (!registered.add(id)) return
        val target = sensor() ?: return
        val listener = sensorListener ?: return
        invokeRaw(target, "registerListener", listener, id)
    }

    private val sensorListener: Any? by lazy {
        runCatching {
            val type = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensor\$ISensorListener")
            java.lang.reflect.Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                java.lang.reflect.InvocationHandler { _, _, _ -> null },
            )
        }.getOrNull()
    }

    /** Channel-by-channel probes used by the enumeration scan; a null result means unsupported. */
    fun probeCustomize(id: Int, area: Int): Double? =
        call(function(), "getCustomizeFunctionValue", id, area)?.toDouble()
            ?: call(function(), "getCustomizeFunctionValue", id)?.toDouble()

    fun probeFunction(id: Int, area: Int): Double? =
        call(function(), "getFunctionValue", id, area)?.toDouble()
            ?: call(function(), "getFunctionValue", id)?.toDouble()

    fun probeSensor(id: Int): Double? {
        subscribe(id)
        return (call(sensor(), "getSensorLatestValue", id)
            ?: call(sensor(), "getSensorValue", id))?.toDouble()
    }

    fun probeEcarx(id: Int, area: Int): Double? = ecarx.read(id, area)

    override fun close() {
        ecarx.close()
    }

    companion object {
        private const val TAG = "GD-Bridge-Reader"

        /** The vendor object is only created once the head unit's car service is up. */
        const val RETRY_MS = 5_000L

        /** Reflective call with primitive coercion; the vendor classes differ per head unit. */
        fun call(target: Any?, name: String, vararg args: Any?): Number? {
            if (target == null) return null
            val result = invokeRaw(target, name, *args) ?: return null
            return when (result) {
                is Number -> result
                is Boolean -> if (result) 1.0 else 0.0
                else -> null
            }
        }

        fun invokeRaw(target: Any?, name: String, vararg args: Any?): Any? {
            if (target == null) return null
            val methods = runCatching { target.javaClass.methods }.getOrDefault(emptyArray())
            var attempted = false
            for (method in methods) {
                if (method.name != name || method.parameterCount != args.size) continue
                attempted = true
                val converted = coerce(method.parameterTypes, args) ?: continue
                runCatching { method.invoke(target, *converted) }.getOrNull()?.let { return it }
            }
            if (!attempted) Log.d(TAG, "no $name on ${target.javaClass.simpleName}")
            return null
        }

        fun coerce(params: Array<Class<*>>, args: Array<out Any?>): Array<Any?>? {
            val out = arrayOfNulls<Any?>(args.size)
            for (index in args.indices) {
                val value = args[index] ?: return null
                out[index] = when (params[index]) {
                    Int::class.javaPrimitiveType, Integer::class.java -> (value as? Number)?.toInt()
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> (value as? Number)?.toLong()
                    Float::class.javaPrimitiveType, java.lang.Float::class.java -> (value as? Number)?.toFloat()
                    Double::class.javaPrimitiveType, java.lang.Double::class.java -> (value as? Number)?.toDouble()
                    Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> value as? Boolean
                    else -> if (params[index].isInstance(value)) value else null
                } ?: return null
            }
            return out
        }
    }
}

/**
 * The ECARX property binder (`com.simplerbit.autoservice`). Bound lazily and kept for the
 * lifetime of the reader so repeated profile reads do not thrash the vendor service.
 */
internal class EcarxPropertyService(private val context: Context) : Closeable {
    @Volatile private var delegate: Any? = null
    @Volatile private var bound = false
    private val ready = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            delegate = runCatching {
                val stub = Class.forName("ecarx.car.hardware.property.IECarXCarProperty\$Stub")
                stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            }.getOrNull()
            ready.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            delegate = null
            ready.countDown()
        }
    }

    fun read(binding: Binding): Double? = read(binding.propertyId, binding.areaInt)

    fun read(id: Int, area: Int): Double? {
        if (!ensureBound()) return null
        val target = delegate ?: return null
        return (call(target, "getIntProperty", id, area)
            ?: call(target, "getFloatProperty", id, area)
            ?: call(target, "getIntProperty", id)
            ?: call(target, "getFloatProperty", id)
            ?: call(target, "getPropertyValue", id))?.toDouble()
    }

    /** True once the vendor binder is reachable; never forces a bind. */
    val available: Boolean get() = delegate != null

    private fun ensureBound(): Boolean {
        if (delegate != null) return true
        if (!bound) {
            bound = runCatching {
                context.bindService(
                    Intent("com.simplerbit.autoservice.action.BIND_CAR_PROPERTY_SERVICE")
                        .setPackage("com.simplerbit.autoservice"),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
            if (!bound) Log.d("GD-Bridge-Reader", "ECARX property service not installed")
        }
        if (bound) ready.await(3, TimeUnit.SECONDS)
        return delegate != null
    }

    override fun close() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        delegate = null
    }

    private fun call(target: Any?, name: String, vararg args: Any?): Number? {
        val methods = runCatching { target?.javaClass?.methods }.getOrNull() ?: return null
        for (method in methods) {
            if (method.name != name || method.parameterCount != args.size) continue
            val converted = PropertyReader.coerce(method.parameterTypes, args) ?: continue
            val result = runCatching { method.invoke(target, *converted) }.getOrNull()
            if (result is Number) return result
        }
        return null
    }
}
