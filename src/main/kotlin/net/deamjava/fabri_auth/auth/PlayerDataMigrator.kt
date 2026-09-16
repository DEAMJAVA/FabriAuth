package net.deamjava.fabri_auth.auth

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID


object PlayerDataMigrator {

    sealed class MigrationResult {
        object Success : MigrationResult()
        object NothingToMigrate : MigrationResult()
        object SaveFailed : MigrationResult()
    }

    fun migrate(player: ServerPlayer, sourceUuid: UUID, targetUuid: UUID): MigrationResult {
        val server = player.level().server

        if (!forceSavePlayer(player)) {
            return MigrationResult.SaveFailed
        }

        val locations = listOf(
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile() to "dat",
            server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).toFile() to "json",
            server.getWorldPath(LevelResource.PLAYER_STATS_DIR).toFile() to "json"
        )

        var movedAnything = false
        for ((dir, ext) in locations) {
            val sourceFile = File(dir, "$sourceUuid.$ext")
            if (!sourceFile.exists()) continue
            val targetFile = File(dir, "$targetUuid.$ext")
            if (targetFile.exists()) {
                println("[FabriAuth] Overwriting existing ${targetFile.name} in ${dir.name} during migration " +
                        "of ${player.name.string} ($sourceUuid -> $targetUuid).")
            }
            targetFile.parentFile?.mkdirs()
            Files.move(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            movedAnything = true
        }

        return if (movedAnything) MigrationResult.Success else MigrationResult.NothingToMigrate
    }

    internal fun forceSavePlayer(player: ServerPlayer): Boolean {
        return try {
            val playerList = player.level().server.playerList
            val saveMethod = net.minecraft.server.players.PlayerList::class.java
                .getDeclaredMethod("save", ServerPlayer::class.java)
            saveMethod.isAccessible = true
            saveMethod.invoke(playerList, player)
            true
        } catch (e: ReflectiveOperationException) {
            println("[FabriAuth] Could not force-save ${player.name.string} before migration " +
                    "(${e.message}). Aborting migration rather than moving stale data.")
            false
        }
    }
}