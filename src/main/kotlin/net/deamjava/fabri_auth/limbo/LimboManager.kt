package net.deamjava.fabri_auth.limbo

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LimboManager {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private data class PersistedState(
        val dimensionKey: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yRot: Float,
        val xRot: Float,
        val inventoryNbt: List<String>
    )

    private data class SavedState(
        val dimensionKey: ResourceKey<Level>,
        val x: Double,
        val y: Double,
        val z: Double,
        val yRot: Float,
        val xRot: Float,
        val inventory: List<ItemStack>
    )

    private val savedStates = ConcurrentHashMap<UUID, SavedState>()
    private val pendingLimbo = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingReturns = ConcurrentHashMap<UUID, () -> Unit>()

    private val LIMBO_SPAWN = BlockPos(0, 1, 0)

    val LIMBO_SPAWN_X get() = LIMBO_SPAWN.x.toDouble() + 0.5
    val LIMBO_SPAWN_Y get() = LIMBO_SPAWN.y.toDouble()
    val LIMBO_SPAWN_Z get() = LIMBO_SPAWN.z.toDouble() + 0.5

    private val persistFile: File by lazy {
        FabricLoader.getInstance()
            .gameDir
            .resolve("fabri-auth-limbo.json")
            .toFile()
    }

    fun load(server: MinecraftServer) {
        if (!persistFile.exists()) return
        val registryOps = server.registryAccess()
            .createSerializationContext(NbtOps.INSTANCE)
        try {
            val type = object : TypeToken<Map<String, PersistedState>>() {}.type
            val raw: Map<String, PersistedState> =
                GSON.fromJson(persistFile.readText(), type) ?: return

            raw.forEach { (uuidStr, ps) ->
                val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return@forEach
                val dimId = Identifier.tryParse(ps.dimensionKey) ?: return@forEach
                val dimKey = ResourceKey.create(Registries.DIMENSION, dimId)

                val inv = ps.inventoryNbt.map { nbtStr ->
                    if (nbtStr.isBlank()) ItemStack.EMPTY
                    else runCatching {
                        val tag = TagParser.parseCompoundFully(nbtStr)
                        val result = ItemStack.CODEC.parse(registryOps, tag)
                        result.resultOrPartial { err ->
                            println("[FabriAuth] Failed to parse item: $err")
                        }.orElse(ItemStack.EMPTY)
                    }.getOrElse { ItemStack.EMPTY }
                }

                savedStates[uuid] = SavedState(
                    dimensionKey = dimKey,
                    x = ps.x, y = ps.y, z = ps.z,
                    yRot = ps.yRot, xRot = ps.xRot,
                    inventory = inv
                )
            }
            println("[FabriAuth] Loaded ${savedStates.size} pending limbo state(s).")
        } catch (e: Exception) {
            println("[FabriAuth] Failed to load limbo states: ${e.message}")
        }
    }

    fun save(server: MinecraftServer) {
        val registryOps = server.registryAccess()
            .createSerializationContext(NbtOps.INSTANCE)
        try {
            persistFile.parentFile?.mkdirs()
            val serializable = savedStates
                .mapKeys { it.key.toString() }
                .mapValues { (_, state) ->
                    PersistedState(
                        dimensionKey = state.dimensionKey.identifier().toString(),
                        x = state.x, y = state.y, z = state.z,
                        yRot = state.yRot, xRot = state.xRot,
                        inventoryNbt = state.inventory.map { stack ->
                            if (stack.isEmpty) ""
                            else runCatching {
                                val encoded = ItemStack.CODEC.encodeStart(registryOps, stack)
                                encoded.resultOrPartial { err ->
                                    println("[FabriAuth] Failed to encode item: $err")
                                }.orElse(null)?.toString() ?: ""
                            }.getOrElse { "" }
                        }
                    )
                }
            persistFile.writeText(GSON.toJson(serializable))
        } catch (e: Exception) {
            println("[FabriAuth] Failed to save limbo states: ${e.message}")
        }
    }

    fun sendToLimbo(player: ServerPlayer) {
        if (!ConfigLoader.config.limboEnabled) return
        pendingLimbo.add(player.uuid)
    }

    fun tickPendingTeleports(server: MinecraftServer) {
        if (pendingLimbo.isEmpty()) return
        val iter = pendingLimbo.iterator()
        while (iter.hasNext()) {
            val uuid = iter.next()
            val player = server.playerList.getPlayer(uuid)
            if (player == null) {
                iter.remove()
                continue
            }
            val secured = executeSendToLimbo(player)
            if (secured) {
                iter.remove()
            }
        }
    }

    fun returnFromLimbo(player: ServerPlayer, onComplete: (() -> Unit)? = null): Boolean {
        val uuid = player.uuid
        pendingLimbo.remove(uuid)

        val state = savedStates[uuid]
        if (state == null) {
            onComplete?.invoke()
            return true
        }

        val server = player.level().server
        val targetLevel = server.getLevel(state.dimensionKey)
        if (targetLevel == null) {
            println("[FabriAuth] Destination dimension '${state.dimensionKey.identifier()}' for " +
                    "${player.name.string} isn't loaded yet; will retry returning them from limbo.")
            if (onComplete != null) pendingReturns[uuid] = onComplete
            return false
        }

        targetLevel.getChunk(state.x.toInt() shr 4, state.z.toInt() shr 4)

        val safeY = findSafeY(targetLevel, state.x, state.y, state.z)

        player.inventory.clearContent()
        restoreInventory(player, state.inventory)

        player.teleportTo(
            targetLevel,
            state.x, safeY, state.z,
            emptySet(),
            state.yRot, state.xRot,
            false
        )

        savedStates.remove(uuid)
        pendingReturns.remove(uuid)
        save(server)

        onComplete?.invoke()
        return true
    }

    /** Retries any returns-from-limbo that couldn't complete because their destination dimension wasn't loaded. */
    fun tickPendingReturns(server: MinecraftServer) {
        if (pendingReturns.isEmpty()) return
        val iter = pendingReturns.entries.iterator()
        while (iter.hasNext()) {
            val (uuid, onComplete) = iter.next()
            iter.remove()
            val player = server.playerList.getPlayer(uuid) ?: continue
            returnFromLimbo(player, onComplete)
        }
    }

    private fun findSafeY(level: ServerLevel, x: Double, savedY: Double, z: Double): Double {
        val blockX = x.toInt()
        val blockZ = z.toInt()
        val minY = level.minY
        val maxY = level.maxY
        val startY = savedY.toInt().coerceIn(minY, maxY - 2)

        if (isSafeStanding(level, blockX, startY, blockZ)) return savedY

        val searchRadius = 32
        for (offset in 1..searchRadius) {
            val up = startY + offset
            if (up <= maxY - 2 && isSafeStanding(level, blockX, up, blockZ)) return up.toDouble()
            val down = startY - offset
            if (down >= minY + 1 && isSafeStanding(level, blockX, down, blockZ)) return down.toDouble()
        }


        val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)
        return surfaceY.toDouble()
    }

    private fun isSafeStanding(level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val floor = BlockPos(x, y - 1, z)
        val feet = BlockPos(x, y, z)
        val head = BlockPos(x, y + 1, z)
        return !level.getBlockState(floor).isAir &&
                level.getBlockState(feet).isAir &&
                level.getBlockState(head).isAir
    }

    fun onPlayerDisconnect(uuid: UUID, server: MinecraftServer) {
        pendingLimbo.remove(uuid)
        pendingReturns.remove(uuid)
        if (savedStates.containsKey(uuid)) {
            save(server)
        }
    }

    fun discardSavedState(uuid: UUID, server: MinecraftServer) {
        pendingLimbo.remove(uuid)
        pendingReturns.remove(uuid)
        savedStates.remove(uuid)
        save(server)
    }

    fun hasSavedState(uuid: UUID): Boolean = savedStates.containsKey(uuid)

    fun isInLimbo(player: ServerPlayer): Boolean =
        savedStates.containsKey(player.uuid) || pendingLimbo.contains(player.uuid)


    private fun executeSendToLimbo(player: ServerPlayer): Boolean {
        val uuid = player.uuid
        val server = player.level().server
        val limboLevel = getLimboLevel(server)
        if (limboLevel == null) {
            println("[FabriAuth] Limbo dimension '${ConfigLoader.config.limboWorldName}' is not available yet; " +
                    "keeping ${player.name.string} frozen in place until it loads.")
            return false
        }

        ensureLimboFloor(limboLevel)

        if (savedStates.containsKey(uuid)) {
            if (ConfigLoader.config.limboClearInventory) player.inventory.clearContent()  // <- guard
            teleportToLimbo(player, limboLevel)
            return true
        }

        savedStates[uuid] = SavedState(
            dimensionKey = player.level().dimension(),
            x = player.x, y = player.y, z = player.z,
            yRot = player.yRot, xRot = player.xRot,
            inventory = copyInventory(player)
        )

        if (ConfigLoader.config.limboClearInventory) player.inventory.clearContent()  // <- guard
        teleportToLimbo(player, limboLevel)

        save(server)
        return true
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

    private fun teleportToLimbo(player: ServerPlayer, limboLevel: ServerLevel) {
        player.teleportTo(
            limboLevel,
            LIMBO_SPAWN.x.toDouble() + 0.5,
            LIMBO_SPAWN.y.toDouble(),
            LIMBO_SPAWN.z.toDouble() + 0.5,
            emptySet(),
            0f, 0f,
            false
        )
    }

    private fun copyInventory(player: ServerPlayer): List<ItemStack> =
        (0 until player.inventory.containerSize).map { i ->
            player.inventory.getItem(i).copy()
        }

    private fun restoreInventory(player: ServerPlayer, saved: List<ItemStack>) {
        player.inventory.clearContent()
        saved.forEachIndexed { i, stack ->
            if (i < player.inventory.containerSize) {
                player.inventory.setItem(i, stack.copy())
            }
        }
    }
}