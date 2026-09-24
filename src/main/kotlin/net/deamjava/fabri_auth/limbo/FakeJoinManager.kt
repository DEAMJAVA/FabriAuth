package net.deamjava.fabri_auth.limbo

import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.config.ConfigLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.Connection
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
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


object FakeJoinManager {

    private val unsetRemovedMethod = Entity::class.java.getDeclaredMethod("unsetRemoved").apply {
        isAccessible = true
    }

    private fun unsetRemoved(player: ServerPlayer) {
        unsetRemovedMethod.invoke(player)
    }

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
        val realXRot: Float,
        var ticksInLimbo: Int = 0
    )

    private val sessions = ConcurrentHashMap<UUID, FakeSession>()

    private val LIMBO_SPAWN = BlockPos(0, 1, 0)
    private val LIMBO_SPAWN_X get() = LIMBO_SPAWN.x.toDouble() + 0.5
    private val LIMBO_SPAWN_Y get() = LIMBO_SPAWN.y.toDouble()
    private val LIMBO_SPAWN_Z get() = LIMBO_SPAWN.z.toDouble() + 0.5

    private const val REMINDER_INTERVAL_TICKS = 100


    private const val LIMBO_CHUNK_GRID_SIZE = 16

    fun isFakeSession(uuid: UUID): Boolean = sessions.containsKey(uuid)

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

            realLevel.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)
            unsetRemoved(player)

            player.setServerLevel(limboLevel)
            player.absSnapTo(LIMBO_SPAWN_X, LIMBO_SPAWN_Y, LIMBO_SPAWN_Z, 0f, 0f)

            val playerConnection = ServerGamePacketListenerImpl(server, connection, player, cookie)
            player.connection = playerConnection
            connection.setupInboundProtocol(
                GameProtocols.SERVERBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(server.registryAccess()),
                    GameProtocols.Context { player.isCreative() }
                ),
                playerConnection
            )

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
            server.commands.sendCommands(player)

            sendLimboChunks(limboLevel, playerConnection)

            playerList.sendLevelInfo(player, limboLevel)
            limboLevel.addNewPlayer(player)
            player.initInventoryMenu()
            playerConnection.teleport(LIMBO_SPAWN_X, LIMBO_SPAWN_Y, LIMBO_SPAWN_Z, 0f, 0f)
            sendLimboTitle(player)

            sessions[player.uuid] = FakeSession(
                player, connection, cookie, playerList, savedInventory,
                realLevel, realX, realY, realZ, realYRot, realXRot
            )
        } catch (e: Exception) {
            println("[FabriAuth] Failed to start fake-limbo session for ${player.name.string}: ${e.message}")
            connection.disconnect(Component.literal("§cFailed to initialize authentication. Please reconnect."))
        }
    }


    fun tickAll() {
        if (sessions.isEmpty()) return
        for (session in sessions.values) {
            try {
                session.player.connection.tick()
                session.player.level().chunkSource.move(session.player)

                session.ticksInLimbo++
                if (session.ticksInLimbo >= REMINDER_INTERVAL_TICKS) {
                    session.ticksInLimbo = 0
                    sendLimboReminder(session.player)
                }
            } catch (e: Exception) {
                println("[FabriAuth] Error ticking fake-limbo session for ${session.player.name.string}: ${e.message}")
            }
        }
    }

    fun promote(uuid: UUID) {
        val session = sessions.remove(uuid) ?: return
        val player = session.player
        val playerList = session.playerList

        player.inventory.clearContent()
        session.savedInventory.forEachIndexed { i, stack ->
            if (i < player.inventory.containerSize) player.inventory.setItem(i, stack.copy())
        }

        val limboLevel = player.level()
        limboLevel.removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)
        unsetRemoved(player)

        limboLevel.chunkSource.removeEntity(player)

        val realLevel = session.realLevel
        player.setServerLevel(realLevel)
        player.absSnapTo(session.realX, session.realY, session.realZ, session.realYRot, session.realXRot)

        player.setYHeadRot(session.realYRot)
        player.setYBodyRot(session.realYRot)
        player.yRotO = session.realYRot
        player.xRotO = session.realXRot

        val playerConnection = player.connection
        val levelData = realLevel.levelData

        playerConnection.send(
            ClientboundRespawnPacket(player.createCommonSpawnInfo(realLevel), 1)
        )
        playerConnection.teleport(player.x, player.y, player.z, player.yRot, player.xRot)
        playerConnection.send(ClientboundSetDefaultSpawnPositionPacket(realLevel.respawnData))
        playerConnection.send(ClientboundChangeDifficultyPacket(levelData.difficulty, levelData.isDifficultyLocked))
        playerConnection.send(
            ClientboundSetExperiencePacket(
                player.experienceProgress,
                player.totalExperience,
                player.experienceLevel
            )
        )
        playerConnection.send(ClientboundSetHeldSlotPacket(player.inventory.selectedSlot))

        playerList.sendActivePlayerEffects(player)
        playerList.sendLevelInfo(player, realLevel)
        playerList.sendPlayerPermissionLevel(player)

        playerConnection.send(
            net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(playerList.players)
        )

        playerList.players.add(player)
        playerList.playersByUUID[player.uuid] = player

        playerList.broadcastAll(
            net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(listOf(player))
        )

        realLevel.addNewPlayer(player)
        player.initInventoryMenu()

        playerList.broadcastSystemMessage(
            Component.translatable("multiplayer.player.joined", player.displayName).withStyle(net.minecraft.ChatFormatting.YELLOW),
            false
        )
    }

    fun onDisconnect(uuid: UUID) {
        val session = sessions.remove(uuid) ?: return
        val player = session.player

        player.inventory.clearContent()
        session.savedInventory.forEachIndexed { i, stack ->
            if (i < player.inventory.containerSize) player.inventory.setItem(i, stack.copy())
        }
    }

    private fun sendLimboChunks(limboLevel: ServerLevel, connection: ServerGamePacketListenerImpl) {
        val centerChunkX = LIMBO_SPAWN.x shr 4
        val centerChunkZ = LIMBO_SPAWN.z shr 4
        val half = LIMBO_CHUNK_GRID_SIZE / 2

        connection.send(ClientboundSetChunkCacheCenterPacket(centerChunkX, centerChunkZ))
        connection.send(ClientboundSetChunkCacheRadiusPacket(half))

        for (dx in -half until half) {
            for (dz in -half until half) {
                val chunk = limboLevel.getChunk(centerChunkX + dx, centerChunkZ + dz)
                connection.send(ClientboundLevelChunkWithLightPacket(chunk, limboLevel.lightEngine, null, null))
            }
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

    private fun limboPrompt(uuid: UUID): Pair<String, String> {
        val cfg = ConfigLoader.config
        return if (AuthStateManager.isRegistered(uuid)) {
            "§e§lLOG IN" to cfg.messageNotLoggedIn
        } else {
            "§a§lREGISTER" to cfg.messageNotRegistered
        }
    }

    private fun sendLimboTitle(player: ServerPlayer) {
        val (title, subtitle) = limboPrompt(player.uuid)
        player.connection.send(ClientboundSetTitlesAnimationPacket(5, 40, 10))
        player.connection.send(ClientboundSetTitleTextPacket(Component.literal(title)))
        player.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(subtitle)))
    }

    private fun sendLimboReminder(player: ServerPlayer) {
        val (_, message) = limboPrompt(player.uuid)
        player.sendSystemMessage(Component.literal(message))
        sendLimboTitle(player)
    }
}