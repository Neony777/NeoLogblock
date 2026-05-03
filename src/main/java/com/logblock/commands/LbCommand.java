package com.logblock.commands;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.logblock.LogBlockMod;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.BlockLogEntry;
import com.logblock.database.ContainerLogEntry;
import com.logblock.database.DatabaseManager;
import com.logblock.database.InteractionLogEntry;
import com.logblock.events.BlockEventHandler;
import com.logblock.util.BlockStateSerializer;
import com.logblock.util.ChatFormatter;
import com.logblock.util.SoundEffects;
import com.logblock.util.TimelineEntry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class LbCommand {

    private static final Gson GSON = new Gson();

    private static final Map<UUID, QueryContext> queryContexts = new ConcurrentHashMap<>();
    private static final Map<UUID, RollbackSession> rollbackSessions = new ConcurrentHashMap<>();
    /** Rollback sessions loaded from DB on server start, keyed by player name (no UUID yet). */
    private static final Map<String, RollbackSession> pendingByName = new ConcurrentHashMap<>();

    private record QueryContext(
        String type,
        String world,
        int x, int y, int z,
        int radius,
        String playerFilter,
        long sinceMs
    ) {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var lb = Commands.literal("lb");

        lb.then(Commands.literal("block")
            .executes(ctx -> executeBlockInspect(ctx.getSource(), 0)));

        lb.then(Commands.literal("container")
            .executes(ctx -> executeContainerInspect(ctx.getSource(), 0)));

        lb.then(Commands.literal("player")
            .then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> executePlayerQuery(ctx.getSource(),
                    StringArgumentType.getString(ctx, "name"), 0))));

        lb.then(Commands.literal("area")
            .executes(ctx -> executeAreaQuery(ctx.getSource(), 10, 0))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
                .executes(ctx -> executeAreaQuery(ctx.getSource(),
                    IntegerArgumentType.getInteger(ctx, "radius"), 0))));

        lb.then(Commands.literal("time")
            .then(Commands.argument("minutes", LongArgumentType.longArg(1))
                .executes(ctx -> executeTimeFilter(ctx.getSource(),
                    LongArgumentType.getLong(ctx, "minutes")))));

        lb.then(Commands.literal("page")
            .then(Commands.argument("n", IntegerArgumentType.integer(1))
                .executes(ctx -> executePage(ctx.getSource(),
                    IntegerArgumentType.getInteger(ctx, "n") - 1))));

        lb.then(Commands.literal("rollback")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> executeRollback(ctx.getSource(), 10, 60))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                .executes(ctx -> executeRollback(ctx.getSource(),
                    IntegerArgumentType.getInteger(ctx, "radius"), 60))
                .then(Commands.argument("minutes", LongArgumentType.longArg(1))
                    .executes(ctx -> executeRollback(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "radius"),
                        LongArgumentType.getLong(ctx, "minutes"))))));

        lb.then(Commands.literal("redo")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> executeRedo(ctx.getSource())));

        lb.then(Commands.literal("reload")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> executeReload(ctx.getSource())));

        lb.then(Commands.literal("status")
            .executes(ctx -> executeStatus(ctx.getSource())));

        lb.then(Commands.literal("purge")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> executePurge(ctx.getSource(),
                LogBlockConfig.PURGE_DAYS_OLDER_THAN.get()))
            .then(Commands.argument("days", IntegerArgumentType.integer(0, 3650))
                .executes(ctx -> executePurge(ctx.getSource(),
                    IntegerArgumentType.getInteger(ctx, "days")))));

        lb.then(Commands.literal("help")
            .executes(ctx -> executeHelp(ctx.getSource())));

        lb.executes(ctx -> executeHelp(ctx.getSource()));

        event.getDispatcher().register(lb);
    }


    /**
     * Safety cap on rows fetched per table for the merged timeline. Even if a
     * caller asks for an absurdly high page, we cap the per-table fetch here
     * to bound memory; with the COUNT()-driven totalPages that follows, this
     * only affects users who explicitly jump beyond ~MAX_TIMELINE_FETCH/pageSize
     * pages and never silently truncates page 1..N of normal browsing.
     */
    private static final int MAX_TIMELINE_FETCH = 5_000;

    private static int executeBlockInspect(CommandSourceStack source, int page) {
        return executeCoordInspect(source, page);
    }

    private static int executeContainerInspect(CommandSourceStack source, int page) {
        return executeCoordInspect(source, page);
    }

    /**
     * Unified inspector for {@code /lb block} and {@code /lb container}: both
     * commands now show one chronological timeline merging block, container
     * and interaction events at the targeted coordinate.
     */
    private static int executeCoordInspect(CommandSourceStack source, int page) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            BlockPos pos = getTargetBlock(player);
            if (pos == null) {
                source.sendFailure(Component.literal("No block in range."));
                SoundEffects.playError(player);
                return 0;
            }
            String world = BlockEventHandler.dimensionName(player.serverLevel());
            long sinceMs = defaultSinceMs();
            int pageSize = LogBlockConfig.MAX_RESULTS_PER_PAGE.get();
            boolean canTeleport = canTeleport(source);

            queryContexts.put(player.getUUID(),
                new QueryContext("coord", world, pos.getX(), pos.getY(), pos.getZ(), 0, null, sinceMs));

            TimelineResult result = showCoordinateTimeline(
                source::sendSystemMessage,
                world, pos.getX(), pos.getY(), pos.getZ(),
                sinceMs, page, pageSize, canTeleport);
            playResultSound(player, result.foundAny());
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
        }
        return 1;
    }

    /**
     * Renders the unified per-coordinate timeline (block + container +
     * interaction events) into {@code sink}. Returns whether any rows were
     * found in the active page. Pulled out so {@link com.logblock.events.InspectorHandler}
     * can share the same renderer.
     */
    /** Result of rendering a timeline view. */
    public record TimelineResult(int totalPages, boolean foundAny) {
        public static final TimelineResult EMPTY = new TimelineResult(0, false);
    }

    public static TimelineResult showCoordinateTimeline(java.util.function.Consumer<Component> sink,
                                                        String world, int x, int y, int z,
                                                        long sinceMs, int page, int pageSize,
                                                        boolean canTeleport) {
        return showCoordinateTimeline(sink, world, x, y, z, sinceMs, page, pageSize, canTeleport, null);
    }

    /**
     * @param preFooterHint optional component (e.g. the "Sneak+right-click..." hint)
     *                      printed immediately above the pagination bar; ignored when
     *                      there's only one page. Pass {@code null} to omit.
     */
    public static TimelineResult showCoordinateTimeline(java.util.function.Consumer<Component> sink,
                                                        String world, int x, int y, int z,
                                                        long sinceMs, int page, int pageSize,
                                                        boolean canTeleport,
                                                        Component preFooterHint) {
        DatabaseManager db = LogBlockMod.getDatabase();
        if (db == null) {
            sink.accept(Component.literal("LogBlock database not ready.").withStyle(ChatFormatting.RED));
            return TimelineResult.EMPTY;
        }

        // True total via cheap COUNT(*) per table — this drives correct page
        // count and "Next" availability regardless of per-table fetch caps.
        int blockCount       = db.countLogsAt("block_logs",       world, x, y, z, sinceMs);
        int containerCount   = db.countLogsAt("container_logs",   world, x, y, z, sinceMs);
        int interactionCount = db.countLogsAt("interaction_logs", world, x, y, z, sinceMs);
        int total            = blockCount + containerCount + interactionCount;
        int totalPages       = Math.max(1, (total + pageSize - 1) / pageSize);
        int from             = page * pageSize;
        int to               = Math.min(from + pageSize, total);

        sink.accept(ChatFormatter.timelineHeader(x, y, z, world, page, totalPages, canTeleport));

        if (total == 0 || from >= total) {
            sink.accept(ChatFormatter.noResults());
            if (page > 0) sink.accept(ChatFormatter.paginationFooter(page, totalPages));
            return new TimelineResult(totalPages, false);
        }

        // To render page `page`, the worst-case is that one source supplies
        // every row up to the end of the page, so we need at most `to` rows
        // from each. Capped by MAX_TIMELINE_FETCH to bound memory on extreme
        // pages — but COUNT() above already gave us a faithful totalPages.
        int needed = Math.min(to, MAX_TIMELINE_FETCH);
        var blockEntries       = db.queryBlockLogs       (world, x, y, z, sinceMs, 0, needed);
        var containerEntries   = db.queryContainerLogs   (world, x, y, z, sinceMs, 0, needed);
        var interactionEntries = db.queryInteractionLogs (world, x, y, z, sinceMs, 0, needed);

        List<TimelineEntry> all = new ArrayList<>(
            blockEntries.size() + containerEntries.size() + interactionEntries.size());
        for (BlockLogEntry e : blockEntries) {
            all.add(new TimelineEntry(e.timestamp(),
                ChatFormatter.formatBlockEntry(e, canTeleport, false)));
        }
        for (ContainerLogEntry e : containerEntries) {
            all.add(new TimelineEntry(e.timestamp(),
                ChatFormatter.formatContainerEntry(e, canTeleport, false)));
        }
        for (InteractionLogEntry e : interactionEntries) {
            all.add(new TimelineEntry(e.timestamp(),
                ChatFormatter.formatInteractionEntry(e, canTeleport, false)));
        }
        all.sort(Comparator.comparingLong(TimelineEntry::timestamp).reversed());

        int sliceEnd  = Math.min(to, all.size());
        int displayed = Math.max(0, sliceEnd - from);
        if (displayed == 0) {
            sink.accept(Component.literal(
                "(no rows in this page — page is past the loaded window; try a lower page or refine filters)")
                .withStyle(ChatFormatting.YELLOW));
        } else {
            for (int i = from; i < sliceEnd; i++) sink.accept(all.get(i).formatted());
        }
        if (totalPages > 1) {
            if (preFooterHint != null) sink.accept(preFooterHint);
            sink.accept(ChatFormatter.paginationFooter(page, totalPages));
        }
        return new TimelineResult(totalPages, true);
    }

    /**
     * Renders a unified area timeline (block + container + interaction events
     * within a cubic radius around the given centre). Uses true COUNT()s for
     * pagination correctness, mirroring {@link #showCoordinateTimeline}.
     */
    public static boolean showAreaTimeline(java.util.function.Consumer<Component> sink,
                                           String world, int cx, int cy, int cz, int radius,
                                           long sinceMs, int page, int pageSize,
                                           boolean canTeleport) {
        DatabaseManager db = LogBlockMod.getDatabase();
        if (db == null) {
            sink.accept(Component.literal("LogBlock database not ready.").withStyle(ChatFormatting.RED));
            return false;
        }

        int blockCount       = db.countLogsInRadius("block_logs",       world, cx, cy, cz, radius, sinceMs);
        int containerCount   = db.countLogsInRadius("container_logs",   world, cx, cy, cz, radius, sinceMs);
        int interactionCount = db.countLogsInRadius("interaction_logs", world, cx, cy, cz, radius, sinceMs);
        int total            = blockCount + containerCount + interactionCount;
        int totalPages       = Math.max(1, (total + pageSize - 1) / pageSize);
        int from             = page * pageSize;
        int to               = Math.min(from + pageSize, total);

        sink.accept(ChatFormatter.areaTimelineHeader(radius, page, totalPages));

        if (total == 0 || from >= total) {
            sink.accept(ChatFormatter.noResults());
            if (page > 0) sink.accept(ChatFormatter.paginationFooter(page, totalPages));
            return false;
        }

        int needed = Math.min(to, MAX_TIMELINE_FETCH);
        var blockEntries       = db.queryBlockLogsInRadius      (world, cx, cy, cz, radius, sinceMs, 0, needed);
        var containerEntries   = db.queryContainerLogsInRadius  (world, cx, cy, cz, radius, sinceMs, 0, needed);
        var interactionEntries = db.queryInteractionLogsInRadius(world, cx, cy, cz, radius, sinceMs, 0, needed);

        List<TimelineEntry> all = new ArrayList<>(
            blockEntries.size() + containerEntries.size() + interactionEntries.size());
        for (BlockLogEntry e : blockEntries) {
            all.add(new TimelineEntry(e.timestamp(),
                ChatFormatter.formatBlockEntry(e, canTeleport, true)));
        }
        for (ContainerLogEntry e : containerEntries) {
            all.add(new TimelineEntry(e.timestamp(),
                ChatFormatter.formatContainerEntry(e, canTeleport, true)));
        }
        for (InteractionLogEntry e : interactionEntries) {
            all.add(new TimelineEntry(e.timestamp(),
                ChatFormatter.formatInteractionEntry(e, canTeleport, true)));
        }
        all.sort(Comparator.comparingLong(TimelineEntry::timestamp).reversed());

        int sliceEnd  = Math.min(to, all.size());
        int displayed = Math.max(0, sliceEnd - from);
        if (displayed == 0) {
            sink.accept(Component.literal(
                "(no rows in this page — page is past the loaded window; try a lower page or refine filters)")
                .withStyle(ChatFormatting.YELLOW));
        } else {
            for (int i = from; i < sliceEnd; i++) sink.accept(all.get(i).formatted());
        }
        if (totalPages > 1) {
            sink.accept(ChatFormatter.paginationFooter(page, totalPages));
        }
        return true;
    }

    private static int executePlayerQuery(CommandSourceStack source, String playerName, int page) {
        try {
            ServerPlayer executor = source.getPlayerOrException();
            long sinceMs = currentContext(executor).map(QueryContext::sinceMs).orElse(defaultSinceMs());
            int pageSize = LogBlockConfig.MAX_RESULTS_PER_PAGE.get();
            boolean canTeleport = canTeleport(source);

            queryContexts.put(executor.getUUID(),
                new QueryContext("player", null, 0, 0, 0, 0, playerName, sinceMs));

            source.sendSystemMessage(Component.literal(
                "Actions by " + playerName + " (page " + (page + 1) + "):").withStyle(ChatFormatting.GOLD));

            var blockEntries       = LogBlockMod.getDatabase().queryBlockLogsByPlayer(playerName, sinceMs, page, pageSize);
            var containerEntries   = LogBlockMod.getDatabase().queryContainerLogsByPlayer(playerName, sinceMs, page, pageSize);
            var entityEntries      = LogBlockMod.getDatabase().queryEntityLogsByPlayer(playerName, sinceMs, page, pageSize);
            var interactionEntries = LogBlockMod.getDatabase().queryInteractionLogsByPlayer(playerName, sinceMs, page, pageSize);

            boolean any = false;
            if (!blockEntries.isEmpty()) {
                source.sendSystemMessage(Component.literal("-- Block activity --").withStyle(ChatFormatting.DARK_GRAY));
                blockEntries.forEach(e -> source.sendSystemMessage(
                    ChatFormatter.formatBlockEntry(e, canTeleport, true)));
                any = true;
            }
            if (!containerEntries.isEmpty()) {
                source.sendSystemMessage(Component.literal("-- Container activity --").withStyle(ChatFormatting.DARK_GRAY));
                containerEntries.forEach(e -> source.sendSystemMessage(
                    ChatFormatter.formatContainerEntry(e, canTeleport, true)));
                any = true;
            }
            if (!interactionEntries.isEmpty()) {
                source.sendSystemMessage(Component.literal("-- Interactions --").withStyle(ChatFormatting.DARK_GRAY));
                interactionEntries.forEach(e -> source.sendSystemMessage(
                    ChatFormatter.formatInteractionEntry(e, canTeleport, true)));
                any = true;
            }
            if (!entityEntries.isEmpty()) {
                source.sendSystemMessage(Component.literal("-- Entity kills --").withStyle(ChatFormatting.DARK_GRAY));
                entityEntries.forEach(e -> source.sendSystemMessage(
                    ChatFormatter.formatEntityEntry(e, canTeleport, true)));
                any = true;
            }
            if (!any) {
                source.sendSystemMessage(ChatFormatter.noResults());
                if (page > 0) source.sendSystemMessage(ChatFormatter.paginationFooter(page, false));
            } else {
                boolean hasMore = blockEntries.size() >= pageSize
                    || containerEntries.size() >= pageSize
                    || interactionEntries.size() >= pageSize
                    || entityEntries.size() >= pageSize;
                source.sendSystemMessage(ChatFormatter.paginationFooter(page, hasMore));
            }
            playResultSound(executor, any);
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
        }
        return 1;
    }

    private static int executeAreaQuery(CommandSourceStack source, int radius, int page) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            BlockPos pos = player.blockPosition();
            String world = BlockEventHandler.dimensionName(player.serverLevel());
            long sinceMs = defaultSinceMs();
            int pageSize = LogBlockConfig.MAX_RESULTS_PER_PAGE.get();
            boolean canTeleport = canTeleport(source);

            queryContexts.put(player.getUUID(),
                new QueryContext("area", world, pos.getX(), pos.getY(), pos.getZ(), radius, null, sinceMs));

            boolean foundAny = showAreaTimeline(
                source::sendSystemMessage,
                world, pos.getX(), pos.getY(), pos.getZ(), radius,
                sinceMs, page, pageSize, canTeleport);
            playResultSound(player, foundAny);
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
        }
        return 1;
    }

    private static int executeTimeFilter(CommandSourceStack source, long minutes) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            QueryContext ctx = queryContexts.get(player.getUUID());
            if (ctx == null) {
                source.sendFailure(Component.literal("No active query. Run /lb block or /lb area first."));
                return 0;
            }
            long sinceMs = System.currentTimeMillis() - (minutes * 60_000L);
            QueryContext updated = new QueryContext(ctx.type(), ctx.world(), ctx.x(), ctx.y(), ctx.z(),
                ctx.radius(), ctx.playerFilter(), sinceMs);
            queryContexts.put(player.getUUID(), updated);
            // Re-execute the active query with the new time window immediately
            return executePage(source, 0);
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
        }
        return 1;
    }

    private static int executePage(CommandSourceStack source, int page) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            QueryContext ctx = queryContexts.get(player.getUUID());
            if (ctx == null) {
                source.sendFailure(Component.literal("No active query. Run /lb block or /lb area first."));
                return 0;
            }
            int pageSize = LogBlockConfig.MAX_RESULTS_PER_PAGE.get();
            boolean canTeleport = canTeleport(source);
            switch (ctx.type()) {
                // "block" + "container" remain as aliases routed into the unified timeline
                case "coord", "block", "container" -> {
                    TimelineResult result = showCoordinateTimeline(
                        source::sendSystemMessage,
                        ctx.world(), ctx.x(), ctx.y(), ctx.z(),
                        ctx.sinceMs(), page, pageSize, canTeleport);
                    playResultSound(player, result.foundAny());
                }
                case "player" -> executePlayerQuery(source, ctx.playerFilter(), page);
                case "area" -> {
                    boolean foundAny = showAreaTimeline(
                        source::sendSystemMessage,
                        ctx.world(), ctx.x(), ctx.y(), ctx.z(), ctx.radius(),
                        ctx.sinceMs(), page, pageSize, canTeleport);
                    playResultSound(player, foundAny);
                }
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
        }
        return 1;
    }

    private static int executeRollback(CommandSourceStack source, int radius, long minutes) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            int maxRadius = LogBlockConfig.MAX_ROLLBACK_RADIUS.get();
            if (radius > maxRadius) {
                source.sendFailure(Component.literal("Radius exceeds maximum: " + maxRadius));
                return 0;
            }

            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();
            String world = BlockEventHandler.dimensionName(level);
            long sinceMs = System.currentTimeMillis() - (minutes * 60_000L);

            var entries = LogBlockMod.getDatabase()
                .queryForRollback(world, center.getX(), center.getY(), center.getZ(), radius, sinceMs);

            if (entries.isEmpty()) {
                source.sendSystemMessage(Component.literal(
                    "No changes found in the last " + minutes + "min within " + radius + " blocks.")
                    .withStyle(ChatFormatting.YELLOW));
                SoundEffects.playNoResults(player);
                return 1;
            }

            // Deduplicate: for each position keep only the oldest entry (list is DESC by ts)
            Map<String, BlockLogEntry> oldestPerPos = new LinkedHashMap<>();
            for (BlockLogEntry e : entries) {
                oldestPerPos.put(e.x() + "," + e.y() + "," + e.z(), e);
            }

            List<RollbackSession.BlockSnapshot> snapshots = new ArrayList<>();
            int restored = 0;

            for (BlockLogEntry entry : oldestPerPos.values()) {
                BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());

                BlockState targetState = BlockStateSerializer.deserialize(entry.blockBefore());
                BlockState currentState = level.getBlockState(pos);

                // Snapshot the current state and NBT so /lb redo can re-apply it
                String currentNbt = BlockEventHandler.serializeBlockEntityNbt(level, pos);

                // Restore the block state (flags=3: update clients + notify neighbours)
                level.setBlock(pos, targetState, 3);

                // Restore block entity data that was captured at time of destruction
                if (entry.blockEntityNbt() != null && !entry.blockEntityNbt().isBlank()) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        try {
                            CompoundTag tag = TagParser.parseTag(entry.blockEntityNbt());
                            be.loadWithComponents(tag, level.registryAccess());
                            be.setChanged();
                        } catch (Exception ex) {
                            LogBlockMod.LOGGER.warn("LogBlock: could not restore block entity NBT at {}: {}",
                                pos, ex.getMessage());
                        }
                    }
                }

                snapshots.add(new RollbackSession.BlockSnapshot(pos, world, currentState, targetState, currentNbt));
                restored++;
            }

            // Persist the session so /lb redo survives server restarts
            RollbackSession session = new RollbackSession(player.getName().getString(), snapshots);
            rollbackSessions.put(player.getUUID(), session);
            pendingByName.remove(player.getName().getString());

            List<Map<String, Object>> sessionJson = new ArrayList<>();
            for (RollbackSession.BlockSnapshot s : snapshots) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", s.pos().getX());
                m.put("y", s.pos().getY());
                m.put("z", s.pos().getZ());
                m.put("dimensionKey", s.dimensionKey());
                m.put("stateBefore", BlockStateSerializer.serialize(s.stateBefore()));
                m.put("stateAfter", BlockStateSerializer.serialize(s.stateAfter()));
                m.put("blockEntityNbt", s.blockEntityNbt());
                sessionJson.add(m);
            }
            LogBlockMod.getDatabase().saveRollbackSession(
                player.getName().getString(), world, GSON.toJson(sessionJson));

            int finalRestored = restored;
            source.sendSuccess(() -> Component.literal(
                "Rolled back " + finalRestored + " blocks. Use /lb redo to reverse.")
                .withStyle(ChatFormatting.GREEN), true);
            SoundEffects.playRollback(player);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Rollback failed: " + e.getMessage()));
            LogBlockMod.LOGGER.error("Rollback error", e);
            playErrorIfPlayer(source);
        }
        return 1;
    }

    private static int executeRedo(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            // Check active in-memory session first, then fall back to DB-loaded session
            RollbackSession session = rollbackSessions.remove(player.getUUID());
            if (session == null) {
                session = pendingByName.remove(player.getName().getString());
            }

            if (session == null) {
                source.sendFailure(Component.literal("No rollback to redo."));
                SoundEffects.playError(player);
                return 0;
            }

            int redone = 0;

            for (RollbackSession.BlockSnapshot snap : session.getSnapshots()) {
                // Resolve the correct dimension for each snapshot
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimKey =
                    net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(snap.dimensionKey()));
                ServerLevel targetLevel = player.server.getLevel(dimKey);
                if (targetLevel == null) {
                    LogBlockMod.LOGGER.warn("LogBlock: redo skipping block in unknown dimension '{}'", snap.dimensionKey());
                    continue;
                }

                // Restore to the pre-rollback state (what was there before /lb rollback ran)
                targetLevel.setBlock(snap.pos(), snap.stateBefore(), 3);

                // Restore block entity NBT if available
                if (snap.blockEntityNbt() != null && !snap.blockEntityNbt().isBlank()) {
                    BlockEntity be = targetLevel.getBlockEntity(snap.pos());
                    if (be != null) {
                        try {
                            CompoundTag tag = TagParser.parseTag(snap.blockEntityNbt());
                            be.loadWithComponents(tag, targetLevel.registryAccess());
                            be.setChanged();
                        } catch (Exception ignored) {}
                    }
                }
                redone++;
            }

            LogBlockMod.getDatabase().markRollbackComplete(player.getName().getString());

            int finalRedone = redone;
            source.sendSuccess(() -> Component.literal(
                "Redo complete — restored " + finalRedone + " blocks.")
                .withStyle(ChatFormatting.GREEN), true);
            SoundEffects.playRedo(player);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Redo failed: " + e.getMessage()));
            playErrorIfPlayer(source);
        }
        return 1;
    }

    private static int executeReload(CommandSourceStack source) {
        try {
            // Shut down the existing database connection and open a fresh one using
            // whatever config values are currently in effect. NeoForge auto-reloads
            // config files from disk when they change, so values are already up to date.
            DatabaseManager db = LogBlockMod.getDatabase();
            if (db != null) {
                db.shutdown();
            }
            DatabaseManager fresh = new DatabaseManager();
            fresh.initialize();
            LogBlockMod.setDatabase(fresh);

            source.sendSuccess(() -> Component.literal("LogBlock database reinitialized.")
                .withStyle(ChatFormatting.GREEN), true);
            playReloadIfPlayer(source);
            source.sendSystemMessage(Component.literal(
                "Inspector item: " + LogBlockConfig.INSPECTOR_ITEM.get()).withStyle(ChatFormatting.GRAY));
            source.sendSystemMessage(Component.literal(
                "Page size: " + LogBlockConfig.MAX_RESULTS_PER_PAGE.get()).withStyle(ChatFormatting.GRAY));
            source.sendSystemMessage(Component.literal(
                "Default window: " + LogBlockConfig.DEFAULT_TIME_WINDOW_MINUTES.get() + " minutes")
                .withStyle(ChatFormatting.GRAY));
            source.sendSystemMessage(Component.literal(
                "Logging — blocks:" + flag(LogBlockConfig.LOG_BLOCKS.get()) +
                " containers:" + flag(LogBlockConfig.LOG_CONTAINERS.get()) +
                " explosions:" + flag(LogBlockConfig.LOG_EXPLOSIONS.get()) +
                " entities:" + flag(LogBlockConfig.LOG_ENTITIES.get()))
                .withStyle(ChatFormatting.GRAY));
        } catch (Exception e) {
            source.sendFailure(Component.literal("Reload failed: " + e.getMessage()));
        }
        return 1;
    }

    private static int executeStatus(CommandSourceStack source) {
        DatabaseManager db = LogBlockMod.getDatabase();
        if (db == null) {
            source.sendFailure(Component.literal("Database not initialized."));
            return 0;
        }
        source.sendSystemMessage(Component.literal("=== LogBlock Status ===").withStyle(ChatFormatting.GOLD));
        source.sendSystemMessage(Component.literal(
            "Block logs:     " + db.getDatabaseSize()).withStyle(ChatFormatting.WHITE));
        source.sendSystemMessage(Component.literal(
            "Container logs: " + db.getContainerLogSize()).withStyle(ChatFormatting.WHITE));
        source.sendSystemMessage(Component.literal(
            "Interaction logs: " + db.getInteractionLogSize()).withStyle(ChatFormatting.WHITE));
        source.sendSystemMessage(Component.literal(
            "Entity logs:    " + db.getEntityLogSize()).withStyle(ChatFormatting.WHITE));
        source.sendSystemMessage(Component.literal(
            "Write queue:    " + db.getWriteQueueDepth() + " pending").withStyle(ChatFormatting.WHITE));
        return 1;
    }

    private static int executeHelp(CommandSourceStack source) {
        boolean isOp = source.hasPermission(2);

        // --- Banner ---
        source.sendSystemMessage(Component.literal("§6§l═══════ §e§lLogBlock Help§r §6§l═══════"));
        source.sendSystemMessage(Component.literal("Track, query and roll back block changes on your server.")
            .withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC));
        source.sendSystemMessage(Component.empty());

        // --- Inspection ---
        source.sendSystemMessage(section("Inspection"));
        helpRun (source, "/lb block",      "Show recent history for the block you are looking at.");
        helpRun (source, "/lb container",  "Show item put/take history for the container you are looking at.");
        source.sendSystemMessage(Component.literal("    or right-click any block with a ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("wooden pickaxe").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" to inspect it instantly.").withStyle(ChatFormatting.DARK_GRAY)));
        source.sendSystemMessage(Component.empty());

        // --- Queries ---
        source.sendSystemMessage(section("Queries"));
        helpSuggest(source, "/lb player ",      "<name>",        "Show every recent action by a specific player.");
        helpSuggest(source, "/lb area ",        "[radius]",      "Show all block changes near you (default radius 10).");
        helpSuggest(source, "/lb time ",        "<minutes>",     "Re-run the current query with a different time window.");
        helpSuggest(source, "/lb page ",        "<n>",           "Jump to a page of results — or just click Prev / Next.");
        source.sendSystemMessage(Component.empty());

        // --- Admin ---
        if (isOp) {
            source.sendSystemMessage(section("Admin (OP only)"));
            helpSuggest(source, "/lb rollback ", "[radius] [minutes]", "Undo block changes within a radius (default 10 / 60min).");
            helpRun    (source, "/lb redo",                            "Re-apply the last rollback you performed.");
            helpRun    (source, "/lb reload",                          "Reload config from disk and reopen the database.");
            helpSuggest(source, "/lb purge ",   "[days]",              "Delete log entries older than N days (auto runs daily).");
            source.sendSystemMessage(Component.empty());
        }

        // --- Diagnostics ---
        source.sendSystemMessage(section("Diagnostics"));
        helpRun (source, "/lb status",  "Show row counts, write-queue depth and database type.");
        helpRun (source, "/lb help",    "Show this menu.");
        source.sendSystemMessage(Component.empty());

        // --- Tips ---
        source.sendSystemMessage(section("Tips"));
        tip(source, "Hover a block or item name to see its full Minecraft ID.");
        tip(source, "Hover a timestamp to see the exact date and time.");
        tip(source, "Click coordinates in any result to teleport (ops only).");
        tip(source, "Click the green Prev / Next bar at the bottom of results to page.");

        return 1;
    }

    /** Section header — "── Section ────────────────────". */
    private static Component section(String title) {
        return Component.literal("── ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal(title).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
            .append(Component.literal(" ──────────────────").withStyle(ChatFormatting.GOLD));
    }

    /** Help row whose command runs immediately on click (no arguments needed). */
    private static void helpRun(CommandSourceStack source, String cmd, String desc) {
        Component hover = Component.literal("Click to run ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(cmd).withStyle(ChatFormatting.YELLOW));
        Component cmdComp = Component.literal(" " + cmd).withStyle(
            net.minecraft.network.chat.Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, hover)));
        source.sendSystemMessage(cmdComp
            .copy()
            .append(Component.literal(" — " + desc).withStyle(ChatFormatting.GRAY)));
    }

    /** Help row that pre-fills the chat box on click (for commands that need arguments). */
    private static void helpSuggest(CommandSourceStack source, String cmd, String args, String desc) {
        Component hover = Component.literal("Click to fill chat with ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(cmd).withStyle(ChatFormatting.YELLOW));
        Component cmdComp = Component.literal(" " + cmd)
            .withStyle(net.minecraft.network.chat.Style.EMPTY
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                    net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, cmd))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, hover)))
            .copy()
            .append(Component.literal(args).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" — " + desc).withStyle(ChatFormatting.GRAY));
        source.sendSystemMessage(cmdComp);
    }

    private static void tip(CommandSourceStack source, String text) {
        source.sendSystemMessage(Component.literal("  • ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }


    private static long defaultSinceMs() {
        return System.currentTimeMillis() - (LogBlockConfig.DEFAULT_TIME_WINDOW_MINUTES.get() * 60_000L);
    }

    private static Optional<QueryContext> currentContext(ServerPlayer player) {
        return Optional.ofNullable(queryContexts.get(player.getUUID()));
    }

    private static BlockPos getTargetBlock(ServerPlayer player) {
        var hit = player.pick(5.0, 0.0f, false);
        return hit instanceof BlockHitResult bhr ? bhr.getBlockPos() : null;
    }

    private static void help(CommandSourceStack source, String cmd, String desc) {
        source.sendSystemMessage(
            Component.literal(cmd).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" — " + desc).withStyle(ChatFormatting.GRAY)));
    }

    private static String flag(boolean b) {
        return b ? " ✓" : " ✗";
    }

    /** True if the source has the configured permission level for click-to-teleport. */
    private static boolean canTeleport(CommandSourceStack source) {
        return source.hasPermission(LogBlockConfig.TELEPORT_PERMISSION_LEVEL.get());
    }

    /** Play the appropriate query result sound (no-op for non-player sources). */
    private static void playResultSound(ServerPlayer player, boolean foundResults) {
        if (foundResults) SoundEffects.playQuery(player);
        else SoundEffects.playNoResults(player);
    }

    private static void playErrorIfPlayer(CommandSourceStack source) {
        try { SoundEffects.playError(source.getPlayerOrException()); } catch (Exception ignored) {}
    }

    private static void playReloadIfPlayer(CommandSourceStack source) {
        try { SoundEffects.playReload(source.getPlayerOrException()); } catch (Exception ignored) {}
    }


    // ---------------- Auto-pruning -----------------------------------------------------------

    /** One in-game day = 24000 ticks. Server tick handler counts up and triggers purge. */
    private static final int TICKS_PER_PURGE = 24_000;
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < TICKS_PER_PURGE) return;
        tickCounter = 0;

        int days = LogBlockConfig.PURGE_DAYS_OLDER_THAN.get();
        if (days <= 0) return; // disabled

        DatabaseManager db = LogBlockMod.getDatabase();
        if (db == null) return;

        long cutoffMs = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        // Async so a large first purge cannot stall the tick loop / trip the watchdog.
        db.purgeOlderThanAsync(cutoffMs, r -> LogBlockMod.LOGGER.info(
            "LogBlock: scheduled purge removed {} rows older than {} days " +
            "(blocks={}, containers={}, interactions={}, entities={}, rollback_sessions={}).",
            r.total(), days, r.blockLogs(), r.containerLogs(), r.interactionLogs(),
            r.entityLogs(), r.rollbackSessions()));
    }

    private static int executePurge(CommandSourceStack source, int days) {
        try {
            DatabaseManager db = LogBlockMod.getDatabase();
            if (db == null) {
                source.sendFailure(Component.literal("Database not available."));
                return 0;
            }
            if (days <= 0) {
                source.sendFailure(Component.literal(
                    "Refusing to purge with days=0 (would delete everything). " +
                    "Pass a positive number, e.g. /lb purge 30."));
                return 0;
            }

            long cutoffMs = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
            DatabaseManager.PurgeResult r = db.purgeOlderThan(cutoffMs);

            source.sendSuccess(() -> Component.literal(
                "Purged " + r.total() + " rows older than " + days + " days.")
                .withStyle(ChatFormatting.GREEN), true);
            source.sendSystemMessage(Component.literal(
                "  blocks=" + r.blockLogs() + ", containers=" + r.containerLogs() +
                ", interactions=" + r.interactionLogs() +
                ", entities=" + r.entityLogs() + ", rollback_sessions=" + r.rollbackSessions())
                .withStyle(ChatFormatting.DARK_GRAY));
            LogBlockMod.LOGGER.info(
                "LogBlock: manual purge by {} removed {} rows older than {} days.",
                source.getTextName(), r.total(), days);

            try { SoundEffects.playReload(source.getPlayerOrException()); } catch (Exception ignored) {}
        } catch (Exception e) {
            source.sendFailure(Component.literal("Purge failed: " + e.getMessage()));
            LogBlockMod.LOGGER.error("LogBlock: manual purge error", e);
            playErrorIfPlayer(source);
        }
        return 1;
    }

    /**
     * Called by ServerLifecycleHandler on server start.
     * Reads incomplete rollback sessions from the database and populates the
     * pendingByName map so players can still run /lb redo after a server restart.
     */
    public static void loadRollbackSessionsFromDatabase() {
        DatabaseManager db = LogBlockMod.getDatabase();
        if (db == null) return;

        Map<String, String> raw = db.loadPendingRollbackSessions();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String playerName = entry.getKey();
            String json = entry.getValue();
            try {
                List<Map<String, Object>> snapshotMaps = GSON.fromJson(
                    json, new TypeToken<List<Map<String, Object>>>() {}.getType());

                List<RollbackSession.BlockSnapshot> snapshots = new ArrayList<>();
                for (Map<String, Object> m : snapshotMaps) {
                    int x = ((Number) m.get("x")).intValue();
                    int y = ((Number) m.get("y")).intValue();
                    int z = ((Number) m.get("z")).intValue();
                    String dim = (String) m.getOrDefault("dimensionKey", "minecraft:overworld");
                    String stateBefore = (String) m.get("stateBefore");
                    String stateAfter  = (String) m.get("stateAfter");
                    String nbt = (String) m.get("blockEntityNbt");

                    snapshots.add(new RollbackSession.BlockSnapshot(
                        new BlockPos(x, y, z),
                        dim,
                        BlockStateSerializer.deserialize(stateBefore),
                        BlockStateSerializer.deserialize(stateAfter),
                        nbt
                    ));
                }

                pendingByName.put(playerName, new RollbackSession(playerName, snapshots));
                LogBlockMod.LOGGER.info("LogBlock: Loaded pending rollback for player '{}'", playerName);
            } catch (Exception e) {
                LogBlockMod.LOGGER.warn("LogBlock: Could not restore rollback session for '{}': {}",
                    playerName, e.getMessage());
            }
        }
    }
}
