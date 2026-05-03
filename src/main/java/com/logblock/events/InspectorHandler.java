package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.commands.LbCommand;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.DatabaseManager;
import com.logblock.util.ChatFormatter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import com.logblock.util.SoundEffects;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class InspectorHandler {

    /** Per-player current page (for sneak+click page cycling) */
    private static final Map<UUID, Integer> playerPages = new ConcurrentHashMap<>();

    /** Per-player last inspected position (for page continuity) */
    private static final Map<UUID, BlockPos> lastInspectedPos = new ConcurrentHashMap<>();

    /**
     * Per-player per-block "last hint shown" timestamp. The Sneak+right-click
     * hint is only printed the first time a player inspects a particular
     * block, then suppressed for {@link #HINT_COOLDOWN_MS} ms.
     */
    private static final Map<HintKey, Long> lastHintMs = new ConcurrentHashMap<>();

    /** 10 minutes — after this, the hint is shown again on the next inspect. */
    private static final long HINT_COOLDOWN_MS = 10L * 60L * 1000L;

    private record HintKey(UUID player, String world, BlockPos pos) {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        // Check if player is holding the configured inspector item
        Item held = player.getMainHandItem().getItem();
        String heldId = BuiltInRegistries.ITEM.getKey(held).toString();
        String configuredId = LogBlockConfig.INSPECTOR_ITEM.get();

        if (!heldId.equals(configuredId)) return;

        event.setCanceled(true);

        BlockPos pos = event.getPos();
        String world = BlockEventHandler.dimensionName(event.getLevel());
        DatabaseManager db = LogBlockMod.getDatabase();
        if (db == null) {
            player.sendSystemMessage(Component.literal("LogBlock database not ready."));
            return;
        }

        int pageSize = LogBlockConfig.MAX_RESULTS_PER_PAGE.get();
        long sinceMs = System.currentTimeMillis() -
            (LogBlockConfig.DEFAULT_TIME_WINDOW_MINUTES.get() * 60_000L);

        // If sneaking and clicking the same block, advance to next page
        int page = 0;
        BlockPos lastPos = lastInspectedPos.get(player.getUUID());
        if (player.isCrouching() && pos.equals(lastPos)) {
            page = playerPages.getOrDefault(player.getUUID(), 0) + 1;
        }

        playerPages.put(player.getUUID(), page);
        lastInspectedPos.put(player.getUUID(), pos);

        // Register an "active query" for this player so the chat pagination
        // buttons (Prev / Next, which run /lb page <n>) work without the player
        // having to run /lb block first.
        LbCommand.setCoordQueryContext(player.getUUID(), world,
            pos.getX(), pos.getY(), pos.getZ(), sinceMs);

        boolean canTeleport = player.hasPermissions(LogBlockConfig.TELEPORT_PERMISSION_LEVEL.get());

        SoundEffects.playInspect(player);

        // Decide whether to attach the "Sneak+right-click" hint above the
        // pagination bar. Only on the first click on a particular block, with
        // a 10-minute cooldown per player+block. Skipped entirely when this is
        // already a sneak-paginate click (page > 0) since the player obviously
        // already knows the gesture.
        Component hint = null;
        if (page == 0 && shouldShowHint(player.getUUID(), world, pos)) {
            hint = ChatFormatter.sneakHint();
        }

        LbCommand.TimelineResult result = LbCommand.showCoordinateTimeline(
            player::sendSystemMessage,
            world, pos.getX(), pos.getY(), pos.getZ(),
            sinceMs, page, pageSize, canTeleport, hint);

        if (result.foundAny()) {
            SoundEffects.playQuery(player);
        } else {
            SoundEffects.playNoResults(player);
        }

        // Only mark the hint as "shown" when it actually rendered (i.e. there
        // was more than one page). One-page results don't print the hint and
        // shouldn't burn the cooldown.
        if (hint != null && result.totalPages() > 1) {
            markHintShown(player.getUUID(), world, pos);
        }

        // Opportunistically prune stale cooldown entries to keep the map small.
        pruneExpiredHints();
    }

    private static boolean shouldShowHint(UUID player, String world, BlockPos pos) {
        Long last = lastHintMs.get(new HintKey(player, world, pos.immutable()));
        return last == null || (System.currentTimeMillis() - last) >= HINT_COOLDOWN_MS;
    }

    private static void markHintShown(UUID player, String world, BlockPos pos) {
        lastHintMs.put(new HintKey(player, world, pos.immutable()), System.currentTimeMillis());
    }

    private static void pruneExpiredHints() {
        if (lastHintMs.isEmpty()) return;
        long cutoff = System.currentTimeMillis() - HINT_COOLDOWN_MS;
        lastHintMs.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
