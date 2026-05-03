package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.InteractionLogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs player interactions with redstone-input blocks: doors, trapdoors,
 * buttons, levers (right-click) and pressure plates (stepping on).
 *
 * <p>Right-click interactions ride on {@link PlayerInteractEvent.RightClickBlock}.
 * Pressure plates have no convenient event hook, so we rate-limit-scan online
 * players each tick and log the transition when a player steps onto a plate
 * they were not previously standing on. This avoids any Mixin and is cheap:
 * one block-state lookup per online player per scan.
 */
@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class InteractionEventHandler {

    /** Last pressure plate position each player was standing on, so we don't log every tick. */
    private static final Map<UUID, BlockPos> lastPlatePos = new ConcurrentHashMap<>();

    /** Scan online players this often (4 times/sec at 20 TPS — fine-grained enough for plates). */
    private static final int PLATE_SCAN_INTERVAL_TICKS = 5;
    private static int plateTickCounter = 0;

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return; // inspector handler cancels first; respect that
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Don't log a "right-click" when the player is using the inspector item.
        Item held = player.getMainHandItem().getItem();
        String heldId = BuiltInRegistries.ITEM.getKey(held).toString();
        if (heldId.equals(LogBlockConfig.INSPECTOR_ITEM.get())) return;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        String action = resolveRightClickAction(block, state);
        if (action == null) return; // not an interactable we care about

        log(player, pos, block, action);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++plateTickCounter < PLATE_SCAN_INTERVAL_TICKS) return;
        plateTickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;
        if (LogBlockMod.getDatabase() == null) return;

        // Track who is currently online so we can evict stale entries for
        // players who logged out while standing on a plate.
        var currentlyOnline = new HashMap<UUID, Boolean>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            currentlyOnline.put(player.getUUID(), true);

            BlockPos foot = player.blockPosition();
            ServerLevel level = player.serverLevel();
            BlockState below = level.getBlockState(foot);
            Block block = below.getBlock();

            BlockPos prev = lastPlatePos.get(player.getUUID());
            if (block instanceof BasePressurePlateBlock) {
                if (prev == null || !prev.equals(foot)) {
                    lastPlatePos.put(player.getUUID(), foot.immutable());
                    log(player, foot, block, InteractionLogEntry.ACTION_STEPPED);
                }
            } else if (prev != null) {
                lastPlatePos.remove(player.getUUID());
            }
        }

        // Evict entries for players no longer online
        lastPlatePos.keySet().removeIf(uuid -> !currentlyOnline.containsKey(uuid));
    }

    /**
     * Decide what verb (if any) to log for a right-click on this block. Doors
     * and trapdoors flip their OPEN property; we honour the *post-click* state
     * by reading {@code BlockStateProperties.OPEN} as it stood *before* the
     * vanilla handler runs and toggling it ourselves — the chat then matches
     * what the player will see a tick later.
     */
    private static String resolveRightClickAction(Block block, BlockState state) {
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock) {
            boolean wasOpen = state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
            return wasOpen ? InteractionLogEntry.ACTION_CLOSED : InteractionLogEntry.ACTION_OPENED;
        }
        if (block instanceof ButtonBlock) {
            return InteractionLogEntry.ACTION_PRESSED;
        }
        if (block instanceof LeverBlock) {
            return InteractionLogEntry.ACTION_FLIPPED;
        }
        return null;
    }

    private static void log(Player player, BlockPos pos, Block block, String action) {
        if (LogBlockMod.getDatabase() == null) return;
        String world = BlockEventHandler.dimensionName(player.level());
        String blockType = BuiltInRegistries.BLOCK.getKey(block).toString();
        LogBlockMod.getDatabase().logInteraction(
            world, pos.getX(), pos.getY(), pos.getZ(),
            blockType, action, player.getName().getString());
    }
}
