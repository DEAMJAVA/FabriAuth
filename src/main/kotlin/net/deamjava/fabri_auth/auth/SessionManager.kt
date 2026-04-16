// src/main/kotlin/net/deamjava/fabri_auth/session/SessionManager.kt
package net.deamjava.fabri_auth.session

import net.deamjava.fabri_auth.config.ConfigLoader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Session(
    val uuid: UUID,
    val ip: String,
    val createdAt: Long = System.currentTimeMillis()
)

object SessionManager {

    /** uuid -> Session */
    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun createSession(uuid: UUID, ip: String) {
        sessions[uuid] = Session(uuid, ip)
    }

    /**
     * Returns true if a valid unexpired session exists for this uuid+ip combo.
     * If ip-based sessions are disabled, only checks uuid and expiry.
     */
    fun hasValidSession(uuid: UUID, ip: String): Boolean {
        val cfg = ConfigLoader.config
        if (!cfg.sessionEnabled) return false
        val session = sessions[uuid] ?: return false
        val elapsed = (System.currentTimeMillis() - session.createdAt) / 1000L
        if (elapsed > cfg.sessionTimeoutSeconds) {
            sessions.remove(uuid)
            return false
        }
        return if (cfg.sessionIpBased) session.ip == ip else true
    }

    fun invalidateSession(uuid: UUID) {
        sessions.remove(uuid)
    }

    fun pruneExpired() {
        val cfg = ConfigLoader.config
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            (now - session.createdAt) / 1000L > cfg.sessionTimeoutSeconds
        }
    }
}