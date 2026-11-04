// GD vehicle bridge: the profile contract shared with DiPlay (schema 1).
package com.geely.desktop.vehicle.bridge

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal val PROFILE_CHAINS = setOf(
    "gd_vehicle_bridge", "adapt_api", "ecarx_service", "direct_binder",
    "ecarx_property_aidl", "xui_ecarx_proxy", "vhal_2_0",
)
internal val PROFILE_KINDS = setOf("sensor", "signal", "property")
private val AREA = Regex("global|auto|auto\\(\\d+,\\d+\\)|-?\\d+")

internal data class Binding(
    val field: String,
    val chain: String,
    val kind: String,
    val area: String,
    val propertyId: Int,
    val name: String,
    val origin: String,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "原始值",
    val encoding: String = "LINEAR",
) {
    /** The vendor area argument; global/auto maps to the "any area" sentinel. */
    val areaInt: Int
        get() = when {
            area == "global" || area == "auto" -> Int.MIN_VALUE
            else -> area.substringBefore('(').toIntOrNull() ?: 0
        }
}

internal data class Profile(val model: String, val bindings: List<Binding>)

/** Preset model ids in the order DiPlay lists them. */
internal object Presets {
    const val GALAXY_L6 = "galaxy_l6"
    const val BOYUE_L_G636 = "boyue_l_g636"
    const val BOYUE_L_FX11 = "boyue_l_fx11"

    val modelIds: Array<String> = arrayOf(GALAXY_L6, BOYUE_L_G636, BOYUE_L_FX11)

    fun open(context: Context, modelId: String): String? {
        val res = when (modelId) {
            GALAXY_L6 -> R.raw.galaxy_l6_properties
            BOYUE_L_G636 -> R.raw.boyue_l_g636_properties
            BOYUE_L_FX11 -> R.raw.boyue_l_fx11_properties
            else -> return null
        }
        return context.resources.openRawResource(res).bufferedReader().use { it.readText() }
    }
}

/** The active profile lives in its own atomic file because the service runs in a separate process. */
internal object Profiles {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "vehicle-data-profile.json"))

    fun load(context: Context): Profile? = runCatching {
        val atomic = file(context)
        if (!atomic.baseFile.exists()) null
        else decode(atomic.openRead().bufferedReader().use { it.readText() })
    }.getOrNull()

    fun save(context: Context, profile: Profile) {
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(encode(profile).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun decode(text: String): Profile {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "Unsupported profile schema" }
        val rows = json.getJSONArray("bindings")
        val bindings = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Binding(
                field = row.getString("field"),
                chain = row.getString("chain"),
                kind = row.getString("kind"),
                area = row.getString("area"),
                propertyId = row.getInt("propertyId"),
                name = row.getString("name"),
                origin = row.optString("origin", ""),
                scale = row.optDouble("scale", 1.0),
                offset = row.optDouble("offset", 0.0),
                unit = row.optString("unit", "原始值"),
                encoding = row.optString("encoding", "LINEAR"),
            )
        }
        return Profile(json.getString("model"), bindings).also(::validate)
    }

    fun encode(profile: Profile): String {
        validate(profile)
        val rows = JSONArray()
        profile.bindings.forEach { binding ->
            rows.put(JSONObject().apply {
                put("field", binding.field)
                put("chain", binding.chain)
                put("kind", binding.kind)
                put("area", binding.area)
                put("propertyId", binding.propertyId)
                put("name", binding.name)
                put("origin", binding.origin)
                put("scale", binding.scale)
                put("offset", binding.offset)
                put("unit", binding.unit)
                put("encoding", binding.encoding)
            })
        }
        return JSONObject()
            .put("schema", 1)
            .put("author", "carlito")
            .put("model", profile.model.trim())
            .put("bindings", rows)
            .toString()
    }

    private fun validate(profile: Profile) {
        require(profile.model.trim().length in 1..80) { "请填写车型名称" }
        require(profile.bindings.isNotEmpty() && profile.bindings.size <= 96) { "属性为空或过多" }
        require(profile.bindings.map { it.field }.distinct().size == profile.bindings.size) {
            "属性重复"
        }
        profile.bindings.forEach { binding ->
            require(binding.chain in PROFILE_CHAINS) { "不支持的数据通道 ${binding.chain}" }
            require(binding.kind in PROFILE_KINDS) { "不支持的属性类型 ${binding.kind}" }
            require(AREA.matches(binding.area)) { "不支持的区域 ${binding.area}" }
            require(binding.propertyId != 0) { "属性 ID 无效" }
            require(binding.name.isNotBlank() && binding.name.length <= 160) { "属性名称无效" }
            require(binding.scale.isFinite() && binding.scale > 0) { "缩放系数无效" }
            require(binding.offset.isFinite()) { "偏移无效" }
            require(binding.unit.length <= 16) { "单位过长" }
            require(binding.field.isNotBlank() && binding.field.length <= 48) { "字段名无效" }
        }
    }
}
