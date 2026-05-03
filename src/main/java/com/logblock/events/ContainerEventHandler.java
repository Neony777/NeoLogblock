package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.ContainerLogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs container item transactions using a per-slot {@link ContainerListener}.
 * The listener is attached when the player opens a container and removed on close.
 * {@code ContainerListener.slotChanged} fires server-side each broadcast tick for
 * every slot whose content changed since the previous broadcast, giving per-operation
 * granularity rather than an open/close net diff.
 */
@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ContainerEventHandler {

    private static final Map<UUID, BlockPos> pendingContainerPos = new ConcurrentHashMap<>();
    private static final Map<UUID, ContainerListener> openListeners = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!LogBlockConfig.LOG_CONTAINERS.get()) return;
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer)) return;

        BlockPos pos = event.getPos();
        Level level = event.getLevel();
        Block block = level.getBlockState(pos).getBlock();

        if (isContainerBlock(block)) {
            pendingContainerPos.put(player.getUUID(), pos);
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!LogBlockConfig.LOG_CONTAINERS.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        AbstractContainerMenu menu = event.getContainer();
        if (menu == null) return;

        BlockPos pos = pendingContainerPos.remove(player.getUUID());
        if (pos == null) return;

        Level level = player.level();
        String world = BlockEventHandler.dimensionName(level);
        Block block = level.getBlockState(pos).getBlock();
        String containerType = BuiltInRegistries.BLOCK.getKey(block).toString();
        String playerName = player.getName().getString();
        int containerSlots = getContainerSlotCount(menu);

        // Per-slot snapshot updated by the listener on each broadcast
        List<ItemStack> perSlotState = new ArrayList<>(containerSlots);
        for (int i = 0; i < containerSlots && i < menu.slots.size(); i++) {
            perSlotState.add(menu.slots.get(i).getItem().copy());
        }

        ContainerListener listener = new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu changedMenu, int slotIndex, ItemStack newStack) {
                if (slotIndex < 0 || slotIndex >= containerSlots || slotIndex >= perSlotState.size()) return;
                if (LogBlockMod.getDatabase() == null) return;

                ItemStack oldStack = perSlotState.get(slotIndex);
                int oldCount = oldStack.isEmpty() ? 0 : oldStack.getCount();
                int newCount = newStack.isEmpty() ? 0 : newStack.getCount();

                boolean itemTypeChanged = !oldStack.isEmpty() && !newStack.isEmpty()
                    && !oldStack.getItem().equals(newStack.getItem());

                if (oldCount == newCount && !itemTypeChanged) {
                    // No meaningful change
                    return;
                }

                // If the item type changed, treat as: take old item, put new item
                if (itemTypeChanged) {
                    if (oldCount > 0) {
                        LogBlockMod.getDatabase().logContainerChange(
                            world, pos.getX(), pos.getY(), pos.getZ(), containerType,
                            BuiltInRegistries.ITEM.getKey(oldStack.getItem()).toString(),
                            oldCount, playerName, ContainerLogEntry.ACTION_TAKE);
                    }
                    if (newCount > 0) {
                        LogBlockMod.getDatabase().logContainerChange(
                            world, pos.getX(), pos.getY(), pos.getZ(), containerType,
                            BuiltInRegistries.ITEM.getKey(newStack.getItem()).toString(),
                            newCount, playerName, ContainerLogEntry.ACTION_PUT);
                    }
                } else if (newCount < oldCount) {
                    int taken = oldCount - newCount;
                    LogBlockMod.getDatabase().logContainerChange(
                        world, pos.getX(), pos.getY(), pos.getZ(), containerType,
                        BuiltInRegistries.ITEM.getKey(oldStack.getItem()).toString(),
                        taken, playerName, ContainerLogEntry.ACTION_TAKE);
                } else {
                    int put = newCount - oldCount;
                    LogBlockMod.getDatabase().logContainerChange(
                        world, pos.getX(), pos.getY(), pos.getZ(), containerType,
                        BuiltInRegistries.ITEM.getKey(newStack.getItem()).toString(),
                        put, playerName, ContainerLogEntry.ACTION_PUT);
                }

                // Always update local state so the next broadcast compares correctly
                perSlotState.set(slotIndex, newStack.isEmpty() ? ItemStack.EMPTY : newStack.copy());
            }

            @Override
            public void dataChanged(AbstractContainerMenu changedMenu, int dataSlot, int value) {}
        };

        menu.addSlotListener(listener);
        openListeners.put(player.getUUID(), listener);
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ContainerListener listener = openListeners.remove(player.getUUID());
        if (listener != null && event.getContainer() != null) {
            event.getContainer().removeSlotListener(listener);
        }
        pendingContainerPos.remove(player.getUUID()); // clean up if open never fired
    }

    public static boolean isContainerBlock(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return id.contains("chest")
            || id.contains("barrel")
            || id.contains("furnace")
            || id.contains("hopper")
            || id.contains("dropper")
            || id.contains("dispenser")
            || id.contains("shulker")
            || id.contains("smoker")
            || id.contains("blast_furnace");
    }

    private static int getContainerSlotCount(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36);
    }
}
