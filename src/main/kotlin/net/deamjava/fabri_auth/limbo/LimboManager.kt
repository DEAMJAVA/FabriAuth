package net.deamjava.fabri_auth.limbo

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
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

    private val LIMBO_SPAWN = BlockPos(0, 1, 0)

    private val persistFile: File by lazy {
        FabricLoader.getInstance()
            .gameDir
            .resolve("fabri-auth-limbo.json")
            .toFile()
    }

    fun load(server: MinecraftServer) {
        if (!persistFile.exists()) return
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
                        val tag = net.minecraft.nbt.TagParser.parseCompoundFully(nbtStr)
                        // parseOptional removed — use CODEC directly
                        val result = ItemStack.CODEC.parse(
                            net.minecraft.nbt.NbtOps.INSTANCE, tag
                        )
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
        try {
            persistFile.parentFile?.mkdirs()
            val serializable = savedStates.mapKeys { it.key.toString() }
                .mapValues { (_, state) ->
                    PersistedState(
                        dimensionKey = state.dimensionKey.identifier().toString(),
                        x = state.x, y = state.y, z = state.z,
                        yRot = state.yRot, xRot = state.xRot,
                        inventoryNbt = state.inventory.map { stack ->
                            if (stack.isEmpty) ""
                            else runCatching {
                                // stack.save() removed — use CODEC directly
                                val encoded = ItemStack.CODEC.encodeStart(
                                    net.minecraft.nbt.NbtOps.INSTANCE, stack
                                )
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
            iter.remove()
            val player = server.playerList.getPlayer(uuid) ?: continue
            executeSendToLimbo(player)
        }
    }

    fun returnFromLimbo(player: ServerPlayer) {
        pendingLimbo.remove(player.uuid)

        val state = savedStates.remove(player.uuid) ?: return

        player.inventory.clearContent()
        restoreInventory(player, state.inventory)

        val targetLevel = player.level().server.getLevel(state.dimensionKey)
            ?: player.level().server.overworld()

        player.teleportTo(
            targetLevel,
            state.x, state.y, state.z,
            emptySet(),
            state.yRot, state.xRot,
            false
        )

        // Persist the removal so the file stays in sync
        save(player.level().server)
    }

    fun onPlayerDisconnect(uuid: UUID, server: MinecraftServer) {
        pendingLimbo.remove(uuid)
        // savedStates is intentionally kept — player reconnects and gets sent
        // back to limbo, then returnFromLimbo restores everything on auth.
        if (savedStates.containsKey(uuid)) {
            save(server)
        }
    }

    fun discardSavedState(uuid: UUID, server: MinecraftServer) {
        pendingLimbo.remove(uuid)
        savedStates.remove(uuid)
        save(server)
    }

    fun hasSavedState(uuid: UUID): Boolean = savedStates.containsKey(uuid)

    fun isInLimbo(player: ServerPlayer): Boolean {
        savedStates[player.uuid] ?: return false
        val limboLevel = getLimboLevel(player.level().server) ?: return false
        if (player.level().dimension() != limboLevel.dimension()) return false

        val limboX = LIMBO_SPAWN.x.toDouble() + 0.5
        val limboY = LIMBO_SPAWN.y.toDouble()
        val limboZ = LIMBO_SPAWN.z.toDouble() + 0.5

        return kotlin.math.abs(player.x - limboX) < 1.0 &&
                kotlin.math.abs(player.y - limboY) < 3.0 &&
                kotlin.math.abs(player.z - limboZ) < 1.0
    }

    private fun executeSendToLimbo(player: ServerPlayer) {
        val uuid = player.uuid
        val limboLevel = getLimboLevel(player.level().server) ?: return

        if (savedStates.containsKey(uuid)) {
            player.inventory.clearContent()
            teleportToLimbo(player, limboLevel)
            return
        }

        savedStates[uuid] = SavedState(
            dimensionKey = player.level().dimension(),
            x = player.x, y = player.y, z = player.z,
            yRot = player.yRot, xRot = player.xRot,
            inventory = copyInventory(player)
        )

        player.inventory.clearContent()
        teleportToLimbo(player, limboLevel)

        save(player.level().server)
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