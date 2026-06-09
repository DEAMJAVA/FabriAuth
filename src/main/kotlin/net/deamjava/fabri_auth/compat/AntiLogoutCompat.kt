package net.deamjava.fabri_auth.compat

import net.deamjava.fabri_auth.limbo.LimboManager
import java.util.UUID

object AntiLogoutCompat {

    fun isManagedByLimbo(uuid: UUID): Boolean =
        LimboManager.hasSavedState(uuid)
}