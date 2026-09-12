package me.justbecause.fastpaintings.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.painting.PaintingConversionService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PaintingMigrationCommand {

    public static void init() {
        CommandRegistrationCallback.EVENT.register(PaintingMigrationCommand::register);
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext,
            Commands.CommandSelection selection
    ) {
        dispatcher.register(
                Commands.literal("fastpaintings")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("stats").executes(PaintingMigrationCommand::runStats))
                        .then(Commands.literal("convert").executes(PaintingMigrationCommand::runConvert))
                        .then(Commands.literal("restore").executes(PaintingMigrationCommand::runRestore))
        );
    }

    private static int runStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int totalEntities = 0;
        int totalAnchors = 0;
        int totalHelperParts = 0;

        Iterable<ServerLevel> levels = (source.getEntity() instanceof ServerPlayer player)
                ? List.of(player.level())
                : source.getServer().getAllLevels();

        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == EntityTypes.PAINTING) {
                    totalEntities++;
                }
            }

            Set<PaintingBlockEntity> anchors = findLoadedAnchors(level, source);
            totalAnchors += anchors.size();
            for (PaintingBlockEntity pbe : anchors) {
                for (BlockPos pos : pbe.getFootprint().occupiedCells()) {
                    if (!pos.equals(pbe.getBlockPos()) && level.getBlockState(pos).is(me.justbecause.fastpaintings.init.ModRegistry.PAINTING_PART_BLOCK)) {
                        totalHelperParts++;
                    }
                }
            }
        }

        final int entities = totalEntities;
        final int anchors = totalAnchors;
        final int parts = totalHelperParts;
        source.sendSuccess(() -> Component.literal(
                String.format("§6[FastPaintings]§r Loaded painting objects (inspects loaded chunks around active players only; does not scan offline saves):\n" +
                        "  - Vanilla painting entities: §e%d§r\n" +
                        "  - Block painting anchors: §e%d§r\n" +
                        "  - Multipart helper parts: §e%d§r", entities, anchors, parts)
        ), false);

        return 1;
    }

    private static int runConvert(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int converted = 0;

        for (ServerLevel level : source.getServer().getAllLevels()) {
            List<Painting> paintings = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == EntityTypes.PAINTING && entity instanceof Painting painting) {
                    paintings.add(painting);
                }
            }

            for (Painting painting : paintings) {
                if (PaintingConversionService.tryConvert(painting, level, true)) {
                    converted++;
                }
            }
        }

        final int count = converted;
        source.sendSuccess(() -> Component.literal(
                String.format("§6[FastPaintings]§r Converted §a%d§r painting entities across all dimensions to block-backed paintings.", count)
        ), true);

        return converted;
    }

    private static int runRestore(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int restored = 0;
        int skipped = 0;

        Iterable<ServerLevel> levels = (source.getEntity() instanceof ServerPlayer player)
                ? List.of(player.level())
                : source.getServer().getAllLevels();

        for (ServerLevel level : levels) {
            Set<PaintingBlockEntity> toRestore = findLoadedAnchors(level, source);

            for (PaintingBlockEntity pbe : toRestore) {
                if (PaintingConversionService.tryRestore(pbe, level)) {
                    restored++;
                } else {
                    skipped++;
                }
            }
        }

        final int count = restored;
        final int skippedCount = skipped;
        source.sendSuccess(() -> Component.literal(
                String.format("§6[FastPaintings]§r Restored §a%d§r block-backed paintings back to vanilla entities (Skipped: %d).", count, skippedCount)
        ), true);

        return restored;
    }

    private static Set<PaintingBlockEntity> findLoadedAnchors(ServerLevel level, CommandSourceStack source) {
        Set<PaintingBlockEntity> anchors = new java.util.LinkedHashSet<>();
        if (source.getEntity() instanceof ServerPlayer player && level == player.level()) {
            int chunkRadius = 16;
            int playerChunkX = SectionPos.blockToSectionCoord(player.getBlockX());
            int playerChunkZ = SectionPos.blockToSectionCoord(player.getBlockZ());
            for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
                for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                    collectAnchorsInChunk(level, cx, cz, anchors);
                }
            }
        } else {
            if (!level.players().isEmpty()) {
                for (ServerPlayer p : level.players()) {
                    int pChunkX = SectionPos.blockToSectionCoord(p.getBlockX());
                    int pChunkZ = SectionPos.blockToSectionCoord(p.getBlockZ());
                    for (int cx = pChunkX - 16; cx <= pChunkX + 16; cx++) {
                        for (int cz = pChunkZ - 16; cz <= pChunkZ + 16; cz++) {
                            collectAnchorsInChunk(level, cx, cz, anchors);
                        }
                    }
                }
            } else {
                BlockPos spawn = level.getRespawnData().pos();
                int spawnChunkX = SectionPos.blockToSectionCoord(spawn.getX());
                int spawnChunkZ = SectionPos.blockToSectionCoord(spawn.getZ());
                for (int cx = spawnChunkX - 16; cx <= spawnChunkX + 16; cx++) {
                    for (int cz = spawnChunkZ - 16; cz <= spawnChunkZ + 16; cz++) {
                        collectAnchorsInChunk(level, cx, cz, anchors);
                    }
                }
            }
        }
        return anchors;
    }

    private static void collectAnchorsInChunk(ServerLevel level, int cx, int cz, Set<PaintingBlockEntity> anchors) {
        if (level.getChunkSource().hasChunk(cx, cz)) {
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk != null) {
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof PaintingBlockEntity pbe) {
                        anchors.add(pbe);
                    }
                }
            }
        }
    }

    private PaintingMigrationCommand() {}
}
