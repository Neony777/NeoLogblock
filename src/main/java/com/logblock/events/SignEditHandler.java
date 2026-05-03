package com.logblock.events;

import com.logblock.LogBlockMod;
import com.logblock.config.LogBlockConfig;
import com.logblock.database.BlockLogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs sign text edits.
 *
 * <p>NeoForge 1.21.1 has no dedicated event for sign-text changes, so we
 * watch instead: when a player right-clicks an editable sign or places a
 * new sign, we snapshot the current text and start polling that block
 * once every ~0.5s for up to {@link #WATCH_WINDOW_MS}. As soon as the
 * text differs from the snapshot we record a {@link BlockLogEntry} with
 * {@code ACTION_REPLACE} and stop watching. Cheap: at most one watched
 * sign per online player at a time.
 */
@EventBusSubscriber(modid = LogBlockMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class SignEditHandler {

    private record Watch(BlockPos pos, String world, String beforeText, long startMs) {}

    private static final Map<UUID, Watch> watching = new ConcurrentHashMap<>();
    /** Stop watching a sign 8 seconds after the player opened the edit GUI. */
    private static final long WATCH_WINDOW_MS = 8_000L;
    /** Poll the watched signs ~2 times per second (every 10 ticks). */
    private static final int POLL_INTERVAL_TICKS = 10;
    private static int tickCounter = 0;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        if (!LogBlockConfig.LOG_SIGNS.get()) return;
        if (LogBlockMod.getDatabase() == null) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return;
        if (sign.isWaxed()) return; // waxed signs cannot be edited

        watching.put(player.getUUID(), new Watch(
            pos.immutable(),
            BlockEventHandler.dimensionName(level),
            extractText(sign),
            System.currentTimeMillis()));
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!LogBlockConfig.LOG_SIGNS.get()) return;
        if (LogBlockMod.getDatabase() == null) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // EntityPlaceEvent#getLevel returns LevelAccessor; we only care about
        // server-side Levels. Skip otherwise to avoid an unsafe cast.
        if (!(event.getLevel() instanceof Level level)) return;

        BlockPos pos = event.getPos();
        // Only watch if the placed block is a sign; reading the block-entity
        // here is not reliable yet because the BE may be created in a follow-up
        // tick, so we just remember the position and assume the "before" text
        // is empty.
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SignBlockEntity || event.getPlacedBlock().getBlock().getDescriptionId()
                .toLowerCase().contains("sign")) {
            watching.put(player.getUUID(), new Watch(
                pos.immutable(),
                BlockEventHandler.dimensionName(level),
                "",
                System.currentTimeMillis()));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;
        if (watching.isEmpty()) return;
        if (LogBlockMod.getDatabase() == null) return;

        MinecraftServer server = event.getServer();
        if (server == null) return;
        long now = System.currentTimeMillis();

        watching.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            Watch w = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) return true;
            // Look up the *watched* dimension by name rather than assuming the
            // player is still in the same world (they may have teleported).
            ServerLevel level = resolveLevel(server, w.world);
            if (level == null) return true;
            BlockEntity be = level.getBlockEntity(w.pos);
            if (be instanceof SignBlockEntity sign) {
                String currentText = extractText(sign);
                if (!currentText.equals(w.beforeText)) {
                    BlockState state = level.getBlockState(w.pos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    LogBlockMod.getDatabase().logBlockChange(
                        w.world, w.pos.getX(), w.pos.getY(), w.pos.getZ(),
                        blockId + "{" + w.beforeText + "}",
                        blockId + "{" + currentText + "}",
                        null,
                        player.getName().getString(),
                        BlockLogEntry.ACTOR_PLAYER,
                        BlockLogEntry.ACTION_REPLACE);
                    return true;
                }
            }
            return now - w.startMs > WATCH_WINDOW_MS;
        });
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionName) {
        try {
            ResourceLocation loc = ResourceLocation.parse(dimensionName);
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Extract a flat string of the sign's front + back text. Sides are
     * tagged with {@code F:} / {@code B:}; non-empty lines per side are
     * joined with " | ". Empty sides are omitted entirely.
     */
    private static String extractText(SignBlockEntity sign) {
        StringBuilder sb = new StringBuilder();
        appendSide(sb, "F", sign.getFrontText());
        appendSide(sb, "B", sign.getBackText());
        return sb.toString();
    }

    private static void appendSide(StringBuilder sb, String label, SignText text) {
        if (text == null) return;
        Component[] msgs = text.getMessages(false);
        StringBuilder lines = new StringBuilder();
        for (Component msg : msgs) {
            String s = (msg == null) ? "" : msg.getString();
            if (s == null || s.isEmpty()) continue;
            if (lines.length() > 0) lines.append(" | ");
            lines.append(s);
        }
        if (lines.length() == 0) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(label).append(':').append(lines);
    }

}
