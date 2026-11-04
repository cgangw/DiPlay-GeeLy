// GD vehicle bridge: the public service DiPlay binds to.
package com.geely.desktop.vehicle.bridge

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.geely.desktop.vehicle.properties.IVehicleProperties
import com.geely.desktop.vehicle.properties.IVehicleSteeringCallback
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

class VehiclePropertiesService : Service() {
    private lateinit var core: BridgeCore

    override fun onCreate() {
        super.onCreate()
        core = BridgeCore(this)
        Log.i(TAG, "vehicle bridge started")
    }

    override fun onBind(intent: Intent?): IBinder = BinderApi(core)

    override fun onDestroy() {
        runCatching { core.close() }
        super.onDestroy()
    }

    private class BinderApi(private val core: BridgeCore) : IVehicleProperties.Stub() {
        override fun getStatus(): Bundle {
            core.assertCaller()
            return core.status()
        }

        override fun getModelIds(): Array<String> {
            core.assertCaller()
            return Presets.modelIds
        }

        override fun getPreset(modelId: String?): String {
            core.assertCaller()
            return modelId?.let { Presets.open(core.context, it) }
                ?: throw IllegalArgumentException("Unknown model")
        }

        override fun getActiveProfile(): String {
            core.assertCaller()
            return core.activeProfile()?.let(Profiles::encode).orEmpty()
        }

        override fun saveProfile(profileJson: String?) {
            core.assertCaller()
            requireNotNull(profileJson) { "Missing profile" }
            core.saveProfile(Profiles.decode(profileJson))
        }

        override fun readProperties(profileJson: String?): Bundle {
            core.assertCaller()
            return core.read(profileJson)
        }

        override fun openProbeReport(): ParcelFileDescriptor {
            core.assertCaller()
            return core.probeReport()
        }

        override fun registerSteeringListener(
            callback: IVehicleSteeringCallback?,
            keyCodes: IntArray?,
            intercept: Boolean,
        ): Bundle {
            core.assertCaller()
            requireNotNull(callback) { "Missing steering callback" }
            return core.steering.register(callback, keyCodes ?: intArrayOf(), intercept)
        }

        override fun unregisterSteeringListener(callback: IVehicleSteeringCallback?) {
            core.assertCaller()
            if (callback != null) core.steering.unregister(callback)
        }

        override fun getSteeringStatus(): Bundle {
            core.assertCaller()
            return core.steering.status()
        }

        override fun updateProjection(owner: IBinder?, frameReady: Boolean): Bundle {
            core.assertCaller()
            requireNotNull(owner) { "Missing projection owner" }
            return core.projection.update(owner, frameReady)
        }

        override fun releaseProjection(owner: IBinder?): Bundle {
            core.assertCaller()
            requireNotNull(owner) { "Missing projection owner" }
            return core.projection.release(owner)
        }

        override fun getProjectionStatus(): Bundle {
            core.assertCaller()
            return core.projection.status()
        }
    }

    private companion object {
        const val TAG = "GD-Bridge-Service"
    }
}

/** Shared state for the binder: profile, reader, steering and projection. */
internal class BridgeCore(val context: android.content.Context) : Closeable {
    val reader = PropertyReader(context)
    val steering = SteeringBridge(context)
    val projection = ProjectionLease()

    @Volatile private var profile: Profile? = Profiles.load(context)

    fun activeProfile(): Profile? = profile

    fun saveProfile(next: Profile) {
        profile = next
        Profiles.save(context, next)
    }

    fun status(): Bundle = Bundle().apply {
        val current = profile
        putInt("schema", 1)
        putBoolean("connected", reader.connected)
        putString(
            "stage",
            when {
                !reader.connected -> "BRIDGE_STARTING"
                current == null -> "PROFILE_REQUIRED"
                else -> "CONNECTED"
            },
        )
        putString("model", current?.model.orEmpty())
        putInt("bindings", current?.bindings?.size ?: 0)
        putStringArrayList(
            "channels",
            arrayListOf(
                "gd_vehicle_bridge=" + if (reader.connected) "READY" else "UNAVAILABLE",
                "adapt_api=" + if (reader.connected) "READY" else "UNAVAILABLE",
                "ecarx_service=OPTIONAL",
                "direct_binder=UNAVAILABLE",
            ),
        )
        putInt("minimumApi", 28)
        putInt("steeringProtocol", 1)
        putInt("projectionProtocol", 1)
        putString("bridge", "GD-Vehicle-Bridge-0.11.25")
    }

    fun read(profileJson: String?): Bundle {
        val target = if (profileJson.isNullOrBlank()) {
            profile ?: throw IllegalStateException("Select a vehicle profile first")
        } else {
            Profiles.decode(profileJson)
        }
        require(target.bindings.size <= MAX_BINDINGS) { "Too many bindings" }
        val values = Bundle()
        val states = Bundle()
        target.bindings.forEach { binding ->
            val reading = runCatching { reader.read(binding) }
                .getOrElse { Reading(null, "READ_FAILED") }
            states.putString(binding.field, reading.state)
            reading.value?.let { raw -> normalize(binding, raw)?.let { values.putDouble(binding.field, it) } }
        }
        return Bundle().apply {
            putInt("schema", 1)
            putBundle("values", values)
            putBundle("states", states)
        }
    }

    fun probeReport(): ParcelFileDescriptor {
        val file = File(context.cacheDir, "geely-property-probe.csv")
        val current = profile
        val bindings = current?.bindings.orEmpty()
        val rows = buildList {
            add("schema=geely_property_probe_v2")
            add("chain,kind,area,name,propertyId,hexId,encoding,value,state,unit,scale,offset,origin,reserved")
            bindings.forEach { binding ->
                val reading = runCatching { reader.read(binding) }
                    .getOrElse { Reading(null, "READ_FAILED") }
                val value = reading.value?.let { normalize(binding, it) }
                add(
                    listOf(
                        binding.chain,
                        binding.kind,
                        binding.area,
                        binding.name,
                        binding.propertyId.toString(),
                        "0x" + binding.propertyId.toUInt().toString(16).uppercase(),
                        binding.encoding,
                        value?.toString().orEmpty(),
                        reading.state,
                        binding.unit,
                        binding.scale.toString(),
                        binding.offset.toString(),
                        binding.origin,
                        "",
                    ).joinToString(",") { "\"" + it.replace("\"", "'") + "\"" },
                )
            }
        }
        file.writeText(rows.joinToString("\n"))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Vendor encodings collapse to the normalized unit DiPlay expects, then scale and offset. */
    private fun normalize(binding: Binding, raw: Double): Double? {
        if (!raw.isFinite()) return null
        if (raw != 0.0 && kotlin.math.abs(raw) < 1e-20) return null
        val decoded = when (binding.encoding) {
            "BINARY" -> if (raw >= 1.0 && raw <= 510.0) 1.0 else 0.0
            "GD_DOOR_OPEN", "GD_WINDOW_OPEN" -> if (raw >= 1.0) 1.0 else 0.0
            "TIRE_PRESSURE_KPA" -> if (raw in 800.0..5000.0) raw / 10.0 else raw
            else -> raw
        }
        return (decoded * binding.scale + binding.offset).takeIf { it.isFinite() }
    }

    fun assertCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = runCatching {
            context.packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        for (name in packages) {
            val allowed = AUTHORIZED[name] ?: continue
            if (signatureDigest(name)?.let { it in allowed } == true) return
        }
        throw SecurityException("Client not authorized for vehicle properties")
    }

    private fun signatureDigest(packageName: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            packageName,
            @Suppress("DEPRECATION") PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    override fun close() {
        reader.close()
        steering.close()
        projection.close()
    }

    private companion object {
        const val MAX_BINDINGS = 96

        /** Published client certificates; the Tianwen build is added for this fork. */
        val AUTHORIZED = mapOf(
            "com.shihab.diplay" to setOf(
                "a15b2614949476c254d9532140ae1cd54455d32681d3ca7340efbac6ea8d0cc7",
                "bd9a50e56cedeca2404449a17f1e8dced22e0089eb912304f658edd57d5dd499",
            ),
            "com.shihab.diplay.hudtest" to setOf(
                "a15b2614949476c254d9532140ae1cd54455d32681d3ca7340efbac6ea8d0cc7",
                "bd9a50e56cedeca2404449a17f1e8dced22e0089eb912304f658edd57d5dd499",
            ),
            "com.shilapi.xcertplay" to setOf(
                "29b48b0183c12406637892204402e71302d676bf6279ff9e2fd2c83aaa4174ec",
            ),
        )
    }
}
