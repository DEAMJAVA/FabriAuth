package net.deamjava.fabri_auth.compat

import net.deamjava.fabri_auth.limbo.FakeJoinManager
import java.util.UUID

object AntiLogoutCompat {
    fun isManagedByLimbo(uuid: UUID): Boolean =
        FakeJoinManager.isFakeSession(uuid)
}