// GD vehicle bridge: instrument projection lease. The display mode itself lives in DiPlay;
// this side only records who currently owns the instrument surface.
package com.geely.desktop.vehicle.bridge

import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import java.io.Closeable

internal class ProjectionLease : Closeable {
    private data class Lease(val owner: IBinder, val frameReady: Boolean, val id: Long, val since: Long)

    private val lock = Any()
    private var lease: Lease? = null
    private var nextId = 0L
    private var lastStage = "IDLE"
    private var recipient: IBinder.DeathRecipient? = null

    fun update(owner: IBinder, frameReady: Boolean): Bundle = synchronized(lock) {
        val current = lease
        if (current == null || current.owner !== owner) {
            releaseLocked()
            nextId += 1
            val fresh = Lease(owner, frameReady, nextId, SystemClock.elapsedRealtime())
            val death = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    synchronized(lock) { if (lease?.owner === owner) releaseLocked() }
                }
            }
            runCatching { owner.linkToDeath(death, 0) }.onSuccess { recipient = death }
            lease = fresh
            lastStage = if (frameReady) "ACTIVE" else "RESTORE_PENDING"
            budget()
        } else {
            lease = current.copy(frameReady = frameReady)
            lastStage = if (frameReady) "ACTIVE" else "RESTORE_PENDING"
            budget()
        }
    }

    fun release(owner: IBinder): Bundle = synchronized(lock) {
        if (lease?.owner === owner) releaseLocked()
        budget()
    }

    fun status(): Bundle = synchronized(lock) { budget() }

    private fun releaseLocked() {
        lease?.let { held -> recipient?.let { death -> runCatching { held.owner.unlinkToDeath(death, 0) } } }
        lease = null
        recipient = null
        lastStage = "IDLE"
    }

    private fun budget(): Bundle {
        val held = lease
        return Bundle().apply {
            putInt("schema", 1)
            putString("stage", lastStage)
            putBoolean("active", held != null)
            putBoolean("frameReady", held?.frameReady ?: false)
            putLong("leaseId", held?.id ?: 0L)
            putLong("sinceMs", held?.since ?: 0L)
            putInt("protocol", 1)
        }
    }

    override fun close() = synchronized(lock) { releaseLocked() }
}
