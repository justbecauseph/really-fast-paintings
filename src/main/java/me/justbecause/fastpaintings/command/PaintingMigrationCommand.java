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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import me.justbecause.fastpaintings.init.ModRegistry;

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
        int totalAnchorBes = 0;
        int totalScannedChunks = 0;

        Iterable<ServerLevel> levels = (source.getEntity() instanceof ServerPlayer player)
                ? List.of(player.level())
                : source.getServer().getAllLevels();

        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == EntityTypes.PAINTING) {
                    totalEntities++;
                }
            }

            Set<LevelChunk> chunks = findLoadedChunks(level, source);
            totalScannedChunks += chunks.size();
            for (LevelChunk chunk : chunks) {
                ChunkScan scan = scanChunk(chunk);
                totalAnchors += scan.anchorBlocks();
                totalHelperParts += scan.helperParts();
                totalAnchorBes += scan.anchorBlockEntities();
            }
        }

        final int entities = totalEntities;
        final int anchors = totalAnchors;
        final int parts = totalHelperParts;
        final int scannedChunks = totalScannedChunks;
        final int anchorBes = totalAnchorBes;
        source.sendSuccess(() -> Component.literal(
                String.format("§6[FastPaintings]§r Scanned §e%d§r loaded chunks (inspects loaded chunks around active players/spawn; does not scan offline saves or unloaded chunks):\n" +
                        "  - Vanilla painting entities: §e%d§r\n" +
                        "  - Block painting anchors: §e%d§r (BlockEntities: §e%d§r)\n" +
                        "  - Multipart helper parts: §e%d§r", scannedChunks, entities, anchors, anchorBes, parts)
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
                String.format("§6[FastPaintings]§r Converted §a%d§r painting entities across all dimensions to block-backed paintings (Scope: all loaded entities in all dimensions).", count)
        ), true);

        return converted;
    }

    private static int runRestore(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int restored = 0;
        int skipped = 0;
        int totalScannedChunks = 0;

        Iterable<ServerLevel> levels = (source.getEntity() instanceof ServerPlayer player)
                ? List.of(player.level())
                : source.getServer().getAllLevels();

        for (ServerLevel level : levels) {
            Set<LevelChunk> chunks = findLoadedChunks(level, source);
            totalScannedChunks += chunks.size();
            Set<PaintingBlockEntity> toRestore = new java.util.LinkedHashSet<>();
            for (LevelChunk chunk : chunks) {
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof PaintingBlockEntity pbe) {
                        toRestore.add(pbe);
                    }
                }
            }

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
        final int scannedChunks = totalScannedChunks;
        source.sendSuccess(() -> Component.literal(
                String.format("§6[FastPaintings]§r Restored §a%d§r block-backed paintings back to vanilla entities (Skipped: %d) (Scope: inspected %d loaded chunks around active players/spawn).", count, skippedCount, scannedChunks)
        ), true);

        return restored;
    }

    public static Set<LevelChunk> findLoadedChunks(ServerLevel level, CommandSourceStack source) {
        Set<LevelChunk> chunks = new java.util.LinkedHashSet<>();
        int chunkRadius = 16;
        if (source.getEntity() instanceof ServerPlayer player && level == player.level()) {
            int playerChunkX = SectionPos.blockToSectionCoord(player.getBlockX());
            int playerChunkZ = SectionPos.blockToSectionCoord(player.getBlockZ());
            for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
                for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                    collectChunk(level, cx, cz, chunks);
                }
            }
        } else {
            if (!level.players().isEmpty()) {
                for (ServerPlayer p : level.players()) {
                    int pChunkX = SectionPos.blockToSectionCoord(p.getBlockX());
                    int pChunkZ = SectionPos.blockToSectionCoord(p.getBlockZ());
                    for (int cx = pChunkX - 16; cx <= pChunkX + 16; cx++) {
                        for (int cz = pChunkZ - 16; cz <= pChunkZ + 16; cz++) {
                            collectChunk(level, cx, cz, chunks);
                        }
                    }
                }
            } else {
                BlockPos spawn = level.getRespawnData().pos();
                int spawnChunkX = SectionPos.blockToSectionCoord(spawn.getX());
                int spawnChunkZ = SectionPos.blockToSectionCoord(spawn.getZ());
                for (int cx = spawnChunkX - 16; cx <= spawnChunkX + 16; cx++) {
                    for (int cz = spawnChunkZ - 16; cz <= spawnChunkZ + 16; cz++) {
                        collectChunk(level, cx, cz, chunks);
                    }
                }
            }
        }
        return chunks;
    }

    private static void collectChunk(ServerLevel level, int cx, int cz, Set<LevelChunk> chunks) {
        if (level.getChunkSource().hasChunk(cx, cz)) {
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
    }

    public record ChunkScan(int anchorBlocks, int helperParts, int anchorBlockEntities) {}

    public static ChunkScan scanChunk(LevelChunk chunk) {
        int anchorBlocks = 0;
        int helperParts = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            if (!section.maybeHas(state -> state.is(ModRegistry.PAINTING_BLOCK) || state.is(ModRegistry.PAINTING_PART_BLOCK))) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(ModRegistry.PAINTING_BLOCK)) {
                            anchorBlocks++;
                        } else if (state.is(ModRegistry.PAINTING_PART_BLOCK)) {
                            helperParts++;
                        }
                    }
                }
            }
        }
        int anchorBes = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof PaintingBlockEntity) {
                anchorBes++;
            }
        }
        return new ChunkScan(anchorBlocks, helperParts, anchorBes);
    }

    public static int countAnchorsInChunk(LevelChunk chunk) {
        return scanChunk(chunk).anchorBlocks();
    }

    public static int countHelperPartsInChunk(LevelChunk chunk) {
        return scanChunk(chunk).helperParts();
    }

    public static int countAnchorBlockEntitiesInChunk(LevelChunk chunk) {
        return scanChunk(chunk).anchorBlockEntities();
    }

    private PaintingMigrationCommand() {}
}
