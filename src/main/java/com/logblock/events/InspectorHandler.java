package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.commands.LbCommand;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.DatabaseManager;
import net.minecraft.ChatFormatting;
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

        boolean canTeleport = player.hasPermissions(LogBlockConfig.TELEPORT_PERMISSION_LEVEL.get());

        SoundEffects.playInspect(player);

        boolean foundAny = LbCommand.showCoordinateTimeline(
            player::sendSystemMessage,
            world, pos.getX(), pos.getY(), pos.getZ(),
            sinceMs, page, pageSize, canTeleport);

        if (foundAny) {
            SoundEffects.playQuery(player);
        } else {
            SoundEffects.playNoResults(player);
        }

        if (page == 0) {
            player.sendSystemMessage(Component.literal(
                "Sneak+right-click to view next page.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
