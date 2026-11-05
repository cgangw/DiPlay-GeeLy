// GD vehicle bridge: discovery scan that finds which vendor channels this head unit can read.
package com.geely.desktop.vehicle.bridge

import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Modifier

/**
 * Enumerates vendor constants and probes every candidate id on every channel, so the exported
 * report shows what this specific head unit can actually read instead of replaying one model's
 * table. Read-only: a probe never writes to the vehicle.
 */
internal class PropertyEnumeration(
    private val reader: PropertyReader,
    private val presets: List<Profile>,
) {
    private data class Candidate(val name: String, val id: Int, val area: Int, val origin: String)

    fun rows(): List<String> {
        if (!reader.connected) return emptyList()
        val out = ArrayList<String>()
        val started = SystemClock.elapsedRealtime()
        symbols().forEach { candidate ->
            if (expired(started, out.size)) return@forEach
            probeSymbol(candidate, out)
        }
        sweepIds().forEach { (id, area) ->
            if (expired(started, out.size)) return@forEach
            probeId(id, area, out)
        }
        Log.i(TAG, "enumerated ${out.size} readable channels in ${SystemClock.elapsedRealtime() - started} ms")
        return out
    }

    /** Published symbols: only these names can be applied automatically by the client importer. */
    private fun symbols(): List<Candidate> {
        val candidates = LinkedHashMap<String, Candidate>()
        reflectiveConstants().forEach { (simple, id) ->
            candidates.getOrPut("$simple:$id") {
                Candidate("$VENDOR_PREFIX$simple", id, ANY_AREA, simple)
            }
        }
        // Preset addresses are probed too: the same id may work on another model's head unit.
        presets.flatMap { it.bindings }.forEach { binding ->
            candidates.getOrPut("${binding.name}:${binding.propertyId}") {
                Candidate(binding.name, binding.propertyId, binding.areaInt, "preset")
            }
        }
        return candidates.values.toList()
    }

    /** Static int constants from the vendor adaptapi classes that are present on this unit. */
    private fun reflectiveConstants(): List<Pair<String, Int>> {
        val found = LinkedHashMap<String, Int>()
        CLASS_NAMES.forEach { className ->
            val type = runCatching { Class.forName(className) }.getOrNull() ?: return@forEach
            runCatching { type.fields }.getOrDefault(emptyArray()).forEach { field ->
                if (!Modifier.isStatic(field.modifiers)) return@forEach
                if (field.type != Int::class.javaPrimitiveType && field.type != Integer::class.java) return@forEach
                val value = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
                if (value <= 0) return@forEach
                found.putIfAbsent(field.name, value)
            }
            Log.i(TAG, "constants from $className: ${found.size}")
        }
        return found.map { it.key to it.value }
    }

    /** Ids adjacent to the preset addresses; catches a shifted model mapping on the same platform. */
    private fun sweepIds(): List<Pair<Int, Int>> {
        val ids = LinkedHashSet<Pair<Int, Int>>()
        presets.flatMap { it.bindings }.forEach { binding ->
            val center = binding.propertyId
            (-SWEEP_RADIUS..SWEEP_RADIUS).forEach { delta ->
                if (delta != 0) ids += (center + delta) to binding.areaInt
            }
        }
        return ids.toList()
    }

    private fun probeSymbol(candidate: Candidate, out: MutableList<String>) {
        val area = if (candidate.area == Int.MIN_VALUE) ANY_AREA else candidate.area
        var any = false
        fun add(chain: String, kind: String, value: Double?) {
            if (!usable(value)) return
            any = true
            out += row(chain, kind, candidate.name, candidate.id, value!!, candidate.origin)
        }
        add("gd_vehicle_bridge", "property", reader.probeCustomize(candidate.id, area))
        add("adapt_api", "signal", reader.probeFunction(candidate.id, area))
        if (candidate.origin.contains("SENSOR") || candidate.name.contains("SENSOR") ||
            candidate.origin == "preset") {
            add("adapt_api", "sensor", reader.probeSensor(candidate.id))
        }
        if (reader.ecarxReady) add("ecarx_service", "property", reader.probeEcarx(candidate.id, area))
        if (!any) Log.d(TAG, "unreadable ${candidate.name} 0x${candidate.id.toUInt().toString(16)}")
    }

    /** Unnamed sweep: proves the address is readable even when no symbol maps to it. */
    private fun probeId(id: Int, area: Int, out: MutableList<String>) {
        val name = "Enum.0x" + id.toUInt().toString(16).uppercase()
        listOf(
            Triple("gd_vehicle_bridge", "property", runCatching { reader.probeCustomize(id, area) }.getOrNull()),
            Triple("adapt_api", "signal", runCatching { reader.probeFunction(id, area) }.getOrNull()),
            Triple("ecarx_service", "property", if (reader.ecarxReady) runCatching { reader.probeEcarx(id, area) }.getOrNull() else null),
        ).forEach { (chain, kind, value) ->
            if (!usable(value) || value == 0.0) return@forEach
            out += row(chain, kind, name, id, value!!, "sweep")
        }
    }

    private fun expired(started: Long, rows: Int): Boolean =
        rows >= MAX_ROWS || SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS

    /**
     * The vendor adapter answers unsupported ids with Float.MIN_VALUE and 255 (a byte-wide -1);
     * reporting those as readable would drown the real channels in noise.
     */
    private fun usable(value: Double?): Boolean {
        if (value == null || !value.isFinite()) return false
        if (kotlin.math.abs(value) < 1e-40) return false
        return value != 255.0 && value != 65535.0
    }

    private fun row(chain: String, kind: String, name: String, id: Int, value: Double, origin: String): String =
        listOf(
            chain,
            kind,
            if (chain == "adapt_api") "global" else "0",
            name,
            id.toString(),
            "0x" + id.toUInt().toString(16).uppercase(),
            "LINEAR",
            value.toString(),
            "READ_OK",
            "原始值",
            "1.0",
            "0.0",
            origin,
            "",
        ).joinToString(",") { "\"" + it.replace("\"", "'") + "\"" }

    private companion object {
        const val TAG = "GD-Bridge-Enum"
        const val VENDOR_PREFIX = "VehicleAdapter."
        const val ANY_AREA = Int.MIN_VALUE
        const val SWEEP_RADIUS = 24
        const val MAX_ROWS = 4000
        const val TIME_BUDGET_MS = 30_000L

        /** Classes that declare published sensor/signal constants; missing ones are skipped. */
        val CLASS_NAMES = arrayOf(
            "com.ecarx.xui.adaptapi.car.sensor.Sensor",
            "com.ecarx.xui.adaptapi.car.sensor.SensorType",
            "com.ecarx.xui.adaptapi.car.sensor.SensorConstants",
            "com.ecarx.xui.adaptapi.car.signal.SignalId",
            "com.ecarx.xui.adaptapi.car.signal.CarSignal",
            "com.ecarx.xui.adaptapi.car.signal.SignalConstants",
            "com.ecarx.xui.adaptapi.car.function.CarFunction",
            "com.ecarx.xui.adaptapi.car.function.CarFunctionConstants",
            "com.ecarx.xui.adaptapi.car.function.VehicleAdapter",
            "com.ecarx.xui.adaptapi.car.property.VehicleAdapter",
            "com.ecarx.xui.adaptapi.car.VehicleAdapter",
            "com.ecarx.xui.adaptapi.car.CarFunction",
        )
    }
}
