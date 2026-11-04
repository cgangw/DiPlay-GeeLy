// GD vehicle bridge: boot/permission maintenance and the legacy internal service.
package com.geely.desktop.vehicle.bridge

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.util.concurrent.TimeUnit

/** Legacy `com.geely.desktop.vehicle.action.BIND` boundary; kept so installed clients still bind. */
class VehicleBridgeService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}

class BridgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in BOOT_ACTIONS) return
        val pending = goAsync()
        Thread {
            try {
                SelfGrant.restore(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/** A replaced client loses its VPN app-op; restoring it here removes the post-install ADB step. */
class AutoGrantPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_REPLACED) return
        val replaced = intent.data?.schemeSpecificPart ?: return
        if (replaced != context.packageName && replaced !in SelfGrant.clientPackages) return
        val pending = goAsync()
        Thread {
            try {
                SelfGrant.restore(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** The bridge holds permissions the vendor services check; a root shell restores them after update. */
internal object SelfGrant {
    private const val TAG = "GD-Bridge-Grant"

    private val CAR_PERMISSIONS = listOf(
        "android.permission.CAR_CONTROL",
        "android.car.permission.CAR_TIRES",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    )
    private val APPOPS = listOf("RUN_IN_BACKGROUND", "ACTIVATE_VPN")
    val clientPackages = listOf("com.shihab.diplay", "com.shihab.diplay.hudtest")

    fun restore(context: Context) {
        val packageName = context.packageName
        val script = buildString {
            missingPermissions(context, packageName).forEach { permission ->
                append("pm grant ").append(quote(packageName)).append(' ').append(permission)
                append(" >/dev/null 2>&1 || cmd package grant ").append(quote(packageName))
                append(' ').append(permission).append(" >/dev/null 2>&1 || true; ")
            }
            appops(packageName).forEach { append(it).append("; ") }
            clientPackages.forEach { client -> appops(client).forEach { append(it).append("; ") } }
            append("true")
        }
        val output = runRoot(script) ?: run {
            Log.i(TAG, "no root channel")
            return
        }
        Log.i(TAG, "permission restore output=${output.take(200)}")
    }

    private fun missingPermissions(context: Context, packageName: String): List<String> {
        val info = runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return emptyList()
        val requested = info.requestedPermissions.orEmpty()
        return requested.filter { permission ->
            permission in CAR_PERMISSIONS || runCatching {
                context.packageManager.getPermissionInfo(permission, 0).protectionLevel and 0xf ==
                    android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.distinct()
    }

    private fun appops(packageName: String): List<String> = APPOPS.map { op ->
        "appops set ${quote(packageName)} $op allow >/dev/null 2>&1 || true"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun runRoot(script: String): String? {
        val commands = listOf(
            arrayOf("su", "-c", script),
            arrayOf("su", "0", "sh", "-c", script),
        )
        for (command in commands) {
            val output = try {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    null
                } else {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (_: Throwable) {
                null
            } ?: continue
            // A non-root shell answers the uid probe as well, so trust only the explicit identity.
            val identity = try {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                process.waitFor(10, TimeUnit.SECONDS)
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                ""
            }
            if ("uid=0" in identity) return output
        }
        return null
    }
}
