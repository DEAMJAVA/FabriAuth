package net.deamjava.fabri_auth.limbo

import net.deamjava.fabri_auth.config.ConfigLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.Connection
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.server.players.PlayerList
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Replaces the old "join for real, then teleport to a limbo dimension" flow.
 *
 * PlayerListMixin cancels PlayerList#placeNewPlayer BEFORE it runs for any
 * not-yet-authenticated player, and hands control here instead. We build our
 * own ServerGamePacketListenerImpl bound to a private limbo ServerLevel
 * WITHOUT ever calling placeNewPlayer's world-registration side effects
 * (players list / playersByUUID / join broadcast / scoreboard / tab list).
 * As far as the rest of the server and every other player are concerned,
 * this connection has not joined.
 *
 * On successful /login or /register, [promote] restores the player's real
 * level, position and inventory, then calls the REAL placeNewPlayer again.
 * Because AuthStateManager now reports them authenticated, PlayerListMixin
 * lets that call through untouched, and vanilla performs a completely normal
 * join — the client re-inits cleanly off a fresh ClientboundLoginPacket,
 * exactly like any other dimension change.
 */
object FakeJoinManager {

    private data class FakeSession(
        val player: ServerPlayer,
        val connection: Connection,
        val cookie: CommonListenerCookie,
        val playerList: PlayerList,
        val savedInventory: List<ItemStack>,
        val realLevel: ServerLevel,
        val realX: Double,
        val realY: Double,
        val realZ: Double,
        val realYRot: Float,
        val realXRot: Float
    )

    private val sessions = ConcurrentHashMap<UUID, FakeSession>()

    private val LIMBO_SPAWN = BlockPos(0, 1, 0)
    private val LIMBO_SPAWN_X get() = LIMBO_SPAWN.x.toDouble() + 0.5
    private val LIMBO_SPAWN_Y get() = LIMBO_SPAWN.y.toDouble()
    private val LIMBO_SPAWN_Z get() = LIMBO_SPAWN.z.toDouble() + 0.5

    fun isFakeSession(uuid: UUID): Boolean = sessions.containsKey(uuid)

    /**
     * Called from PlayerListMixin/FabriAuthJoinGate instead of letting the
     * real placeNewPlayer run. Must not throw uncaught — on any setup
     * failure we disconnect cleanly rather than leaving the client stuck on
     * the "joining world" screen forever.
     */
    fun beginFakeSession(
        playerList: PlayerList,
        connection: Connection,
        player: ServerPlayer,
        cookie: CommonListenerCookie,
        server: MinecraftServer
    ) {
        val limboLevel = getLimboLevel(server)
        if (limboLevel == null) {
            connection.disconnect(
                Component.literal("§cAuthentication limbo is not available right now. Please reconnect shortly.")
            )
            return
        }

        try {
            ensureLimboFloor(limboLevel)

            // Snapshot the player's REAL level/position/inventory before we
            // ever overwrite them for limbo display. placeNewPlayer trusts
            // player.level() directly, so this must be restored in promote()
            // before we call it again.
            val realLevel = player.level()
            val realX = player.x
            val realY = player.y
            val realZ = player.z
            val realYRot = player.yRot
            val realXRot = player.xRot

            val savedInventory = copyInventory(player)
            if (ConfigLoader.config.limboClearInventory) {
                player.inventory.clearContent()
            }

            player.setServerLevel(limboLevel)
            player.absSnapTo(LIMBO_SPAWN_X, LIMBO_SPAWN_Y, LIMBO_SPAWN_Z, 0f, 0f)

            val playerConnection = ServerGamePacketListenerImpl(server, connection, player, cookie)
            connection.setupInboundProtocol(
                GameProtocols.SERVERBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(server.registryAccess()),
                    GameProtocols.Context { player.isCreative() }
                ),
                playerConnection
            )
            // ServerGamePacketListenerImpl's constructor already sets player.connection = this.

            val levelData = limboLevel.levelData
            playerConnection.send(
                ClientboundLoginPacket(
                    player.id,
                    levelData.isHardcore,
                    server.levelKeys(),
                    playerList.maxPlayers,
                    playerList.viewDistance,
                    playerList.simulationDistance,
                    false,
                    true,
                    false,
                    player.createCommonSpawnInfo(limboLevel),
                    server.usesAuthentication(),
                    server.enforceSecureProfile()
                )
            )
            playerConnection.send(ClientboundChangeDifficultyPacket(levelData.difficulty, levelData.isDifficultyLocked))
            playerConnection.send(ClientboundPlayerAbilitiesPacket(player.abilities))
            playerConnection.send(ClientboundSetHeldSlotPacket(player.inventory.selectedSlot))

            playerList.sendLevelInfo(player, limboLevel)
            limboLevel.addNewPlayer(player)
            player.initInventoryMenu()
            playerConnection.teleport(LIMBO_SPAWN_X, LIMBO_SPAWN_Y, LIMBO_SPAWN_Z, 0f, 0f)

            sessions[player.uuid] = FakeSession(
                player, connection, cookie, playerList, savedInventory,
                realLevel, realX, realY, realZ, realYRot, realXRot
            )
        } catch (e: Exception) {
            println("[FabriAuth] Failed to start fake-limbo session for ${player.name.string}: ${e.message}")
            connection.disconnect(Component.literal("§cFailed to initialize authentication. Please reconnect."))
        }
    }

    /**
     * Drives keepalive / chat-spam decay / idle-timeout for fake sessions.
     * These players are never in PlayerList#players, so the server's normal
     * per-tick player loop skips them entirely — without this, their
     * connection can silently time out while they're sitting at the
     * password prompt. Call once per server tick.
     */
    fun tickAll() {
        if (sessions.isEmpty()) return
        for (session in sessions.values) {
            try {
                session.player.connection.tick()
            } catch (e: Exception) {
                println("[FabriAuth] Error ticking fake-limbo session for ${session.player.name.string}: ${e.message}")
            }
        }
    }

    /** Called once /login, /register, or async premium verification succeeds. */
    fun promote(uuid: UUID) {
        val session = sessions.remove(uuid) ?: return
        val player = session.player

        player.inventory.clearContent()
        session.savedInventory.forEachIndexed { i, stack ->
            if (i < player.inventory.containerSize) player.inventory.setItem(i, stack.copy())
        }

        val limboLevel = player.level()
        limboLevel.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)

        player.setServerLevel(session.realLevel)
        player.absSnapTo(session.realX, session.realY, session.realZ, session.realYRot, session.realXRot)

        // Re-entering placeNewPlayer re-triggers PlayerListMixin's gate; since
        // AuthStateManager now reports this uuid authenticated, it lets the
        // full vanilla join run — sending a fresh ClientboundLoginPacket that
        // fully re-initializes the client into the player's real world.
        session.playerList.placeNewPlayer(session.connection, player, session.cookie)
    }

    /**
     * Called from ServerGamePacketListenerImplMixin's onDisconnect hook when
     * a fake-session connection dies before ever logging in. Must run BEFORE
     * vanilla's own onDisconnect -> removePlayerFromWorld -> PlayerList#remove
     * -> save() sequence, so the restored (non-cleared) inventory is what
     * actually gets persisted to disk.
     */
    fun onDisconnect(uuid: UUID) {
        val session = sessions.remove(uuid) ?: return
        val player = session.player

        player.inventory.clearContent()
        session.savedInventory.forEachIndexed { i, stack ->
            if (i < player.inventory.containerSize) player.inventory.setItem(i, stack.copy())
        }
    }

    private fun ensureLimboFloor(limboLevel: ServerLevel) {
        val floorPos = LIMBO_SPAWN.below()
        limboLevel.chunkSource.getChunkNow(floorPos.x shr 4, floorPos.z shr 4) ?: run {
            limboLevel.getChunk(floorPos.x shr 4, floorPos.z shr 4)
        }
        if (limboLevel.getBlockState(floorPos).isAir) {
            limboLevel.setBlockAndUpdate(floorPos, Blocks.BARRIER.defaultBlockState())
        }
    }

    private fun getLimboLevel(server: MinecraftServer): ServerLevel? {
        val cfg = ConfigLoader.config
        val configuredKey = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.tryParse(cfg.limboWorldName) ?: return getDefaultLimboLevel(server)
        )
        return server.getLevel(configuredKey) ?: getDefaultLimboLevel(server)
    }

    private fun getDefaultLimboLevel(server: MinecraftServer): ServerLevel? {
        val defaultKey = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.tryParse("fabri_auth:limbo") ?: return null
        )
        return server.getLevel(defaultKey)
    }

    private fun copyInventory(player: ServerPlayer): List<ItemStack> =
        (0 until player.inventory.containerSize).map { i -> player.inventory.getItem(i).copy() }
}