package com.logblock.util;

import com.logblock.database.BlockLogEntry;
import com.logblock.database.ContainerLogEntry;
import com.logblock.database.EntityLogEntry;
import com.logblock.database.InteractionLogEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChatFormatter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIMESTAMP_FULL_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
    private static final NumberFormat NUMBER_FMT = NumberFormat.getInstance(Locale.US);

    private ChatFormatter() {}


    public static Component blockHeader(long sinceMs, int x, int y, int z, String world,
                                        int page, boolean canTeleport) {
        String window = formatWindow(sinceMs);
        MutableComponent c = Component.literal("Block changes in the last ")
            .withStyle(ChatFormatting.GRAY);
        c.append(Component.literal(window).withStyle(ChatFormatting.WHITE));
        c.append(Component.literal(" at ").withStyle(ChatFormatting.GRAY));
        c.append(coordComponent(x, y, z, world, canTeleport));
        c.append(Component.literal(" (page " + (page + 1) + "):").withStyle(ChatFormatting.GRAY));
        return c;
    }

    public static Component containerHeader(int x, int y, int z, String world,
                                            int page, boolean canTeleport) {
        MutableComponent c = Component.literal("Container changes at ")
            .withStyle(ChatFormatting.GRAY);
        c.append(coordComponent(x, y, z, world, canTeleport));
        c.append(Component.literal(" (page " + (page + 1) + "):").withStyle(ChatFormatting.GRAY));
        return c;
    }

    /**
     * Header for the unified per-coordinate timeline that interleaves block,
     * container and interaction events at one position.
     */
    public static Component timelineHeader(int x, int y, int z, String world,
                                           int page, int totalPages, boolean canTeleport) {
        MutableComponent c = Component.literal("Activity at ").withStyle(ChatFormatting.GRAY);
        c.append(coordComponent(x, y, z, world, canTeleport));
        c.append(Component.literal(" (page " + (page + 1) + " of " + totalPages + "):")
            .withStyle(ChatFormatting.GRAY));
        return c;
    }

    public static Component formatInteractionEntry(InteractionLogEntry e) {
        return formatInteractionEntry(e, false, false);
    }

    public static Component formatInteractionEntry(InteractionLogEntry e,
                                                   boolean canTeleport, boolean includeCoords) {
        ChatFormatting actionColor = switch (e.action()) {
            case InteractionLogEntry.ACTION_OPENED  -> ChatFormatting.GREEN;
            case InteractionLogEntry.ACTION_CLOSED  -> ChatFormatting.RED;
            case InteractionLogEntry.ACTION_PRESSED -> ChatFormatting.YELLOW;
            case InteractionLogEntry.ACTION_FLIPPED -> ChatFormatting.GOLD;
            case InteractionLogEntry.ACTION_STEPPED -> ChatFormatting.AQUA;
            default                                  -> ChatFormatting.WHITE;
        };
        MutableComponent c = timestampComponent(e.timestamp());
        c.append(playerComponent(e.actorName(), "PLAYER"));
        c.append(actionComponent(e.action(), actionColor));
        c.append(itemComponent(e.blockType(), ChatFormatting.WHITE));
        if (includeCoords) {
            c.append(Component.literal(" @ ").withStyle(ChatFormatting.DARK_GRAY));
            c.append(coordComponent(e.x(), e.y(), e.z(), e.world(), canTeleport));
        }
        return c;
    }

    public static Component noResults() {
        return Component.literal("No log entries found.").withStyle(ChatFormatting.YELLOW);
    }

    public static Component summary(int shown, int page, String activity) {
        return Component.literal(
            "  Showing " + shown + " " + activity + " (page " + (page + 1) + ")")
            .withStyle(ChatFormatting.DARK_GRAY).withStyle(ChatFormatting.ITALIC);
    }

    /**
     * Clickable pagination bar: [ ◄ Prev   Page N   Next ► ]
     * Prev is enabled when {@code page > 0}; Next is enabled when {@code hasMore} is true
     * (caller signals this with {@code entries.size() == pageSize}).
     * Disabled buttons render in dark grey and have no click event.
     */
    public static Component paginationFooter(int page, boolean hasMore) {
        boolean hasPrev = page > 0;
        MutableComponent bar = Component.literal("[ ").withStyle(ChatFormatting.DARK_GRAY);

        // Prev
        Style prevStyle = hasPrev
            ? Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lb page " + page))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Go to page " + page).withStyle(ChatFormatting.GREEN)))
            : Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
        bar.append(Component.literal("◄ Prev").withStyle(prevStyle));

        // Centre marker
        bar.append(Component.literal("   Page ").withStyle(ChatFormatting.GRAY));
        bar.append(Component.literal(String.valueOf(page + 1))
            .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withBold(true)));
        bar.append(Component.literal("   ").withStyle(ChatFormatting.GRAY));

        // Next
        int nextPage = page + 2; // 1-indexed for /lb page
        Style nextStyle = hasMore
            ? Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lb page " + nextPage))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("Go to page " + nextPage).withStyle(ChatFormatting.GREEN)))
            : Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
        bar.append(Component.literal("Next ►").withStyle(nextStyle));

        bar.append(Component.literal(" ]").withStyle(ChatFormatting.DARK_GRAY));
        return bar;
    }


    public static Component formatBlockEntry(BlockLogEntry e) {
        return formatBlockEntry(e, false, false);
    }

    public static Component formatBlockEntry(BlockLogEntry e, boolean canTeleport, boolean includeCoords) {
        ChatFormatting actionColor = blockActionColor(e.action());

        MutableComponent c = timestampComponent(e.timestamp());
        c.append(playerComponent(e.actorName(), e.actorType()));
        c.append(actionComponent(e.action(), actionColor));
        c.append(formatBlockDisplay(e));
        if (includeCoords) {
            c.append(Component.literal(" @ ").withStyle(ChatFormatting.DARK_GRAY));
            c.append(coordComponent(e.x(), e.y(), e.z(), e.world(), canTeleport));
        }
        return c;
    }

    public static Component formatContainerEntry(ContainerLogEntry e) {
        return formatContainerEntry(e, false, false);
    }

    public static Component formatContainerEntry(ContainerLogEntry e, boolean canTeleport, boolean includeCoords) {
        ChatFormatting actionColor = e.action().equals(ContainerLogEntry.ACTION_TAKE)
            ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.AQUA;

        MutableComponent c = timestampComponent(e.timestamp());
        c.append(playerComponent(e.actorName(), "PLAYER"));
        c.append(actionComponent(e.action(), actionColor));

        // Quantity in white-bold, item name in colored aqua/light-purple to match action
        c.append(Component.literal(NUMBER_FMT.format(e.amount()) + "\u00d7 ")
            .withStyle(ChatFormatting.WHITE).withStyle(ChatFormatting.BOLD));
        c.append(itemComponent(e.item(), actionColor));
        // "put 32× DIAMOND into CHEST" / "took 12× IRON_INGOT from CHEST"
        String preposition = e.action().equals(ContainerLogEntry.ACTION_PUT) ? " into " : " from ";
        c.append(Component.literal(preposition).withStyle(ChatFormatting.GRAY));
        c.append(itemComponent(e.containerType(), ChatFormatting.GRAY));
        if (includeCoords) {
            c.append(Component.literal(" @ ").withStyle(ChatFormatting.DARK_GRAY));
            c.append(coordComponent(e.x(), e.y(), e.z(), e.world(), canTeleport));
        }
        return c;
    }

    public static Component formatEntityEntry(EntityLogEntry e) {
        return formatEntityEntry(e, false, false);
    }

    public static Component formatEntityEntry(EntityLogEntry e, boolean canTeleport, boolean includeCoords) {
        MutableComponent c = timestampComponent(e.timestamp());
        c.append(playerComponent(e.killerName(), e.killerType()));
        c.append(actionComponent("killed", ChatFormatting.DARK_PURPLE));
        c.append(itemComponent(e.entityName(), ChatFormatting.WHITE));
        if (includeCoords) {
            c.append(Component.literal(" @ ").withStyle(ChatFormatting.DARK_GRAY));
            c.append(coordComponent((int) e.x(), (int) e.y(), (int) e.z(), e.world(), canTeleport));
        }
        return c;
    }


    public static List<Component> formatBlockPage(List<BlockLogEntry> entries,
                                                  long sinceMs, int x, int y, int z,
                                                  String world, int page) {
        return formatBlockPage(entries, sinceMs, x, y, z, world, page, false, false, entries.size());
    }

    public static List<Component> formatBlockPage(List<BlockLogEntry> entries,
                                                  long sinceMs, int x, int y, int z,
                                                  String world, int page,
                                                  boolean canTeleport, boolean includeCoords) {
        return formatBlockPage(entries, sinceMs, x, y, z, world, page, canTeleport, includeCoords,
            entries.size());
    }

    public static List<Component> formatBlockPage(List<BlockLogEntry> entries,
                                                  long sinceMs, int x, int y, int z,
                                                  String world, int page,
                                                  boolean canTeleport, boolean includeCoords,
                                                  int pageSize) {
        List<Component> out = new ArrayList<>();
        out.add(blockHeader(sinceMs, x, y, z, world, page, canTeleport));
        if (entries.isEmpty()) {
            out.add(noResults());
            if (page > 0) out.add(paginationFooter(page, false));
        } else {
            entries.forEach(e -> out.add(formatBlockEntry(e, canTeleport, includeCoords)));
            out.add(summary(entries.size(), page, "block change(s)"));
            out.add(paginationFooter(page, entries.size() >= pageSize));
        }
        return out;
    }

    public static List<Component> formatContainerPage(List<ContainerLogEntry> entries,
                                                      int x, int y, int z, String world, int page) {
        return formatContainerPage(entries, x, y, z, world, page, false, false, entries.size());
    }

    public static List<Component> formatContainerPage(List<ContainerLogEntry> entries,
                                                      int x, int y, int z, String world, int page,
                                                      boolean canTeleport, boolean includeCoords) {
        return formatContainerPage(entries, x, y, z, world, page, canTeleport, includeCoords,
            entries.size());
    }

    public static List<Component> formatContainerPage(List<ContainerLogEntry> entries,
                                                      int x, int y, int z, String world, int page,
                                                      boolean canTeleport, boolean includeCoords,
                                                      int pageSize) {
        List<Component> out = new ArrayList<>();
        out.add(containerHeader(x, y, z, world, page, canTeleport));
        if (entries.isEmpty()) {
            out.add(noResults());
            if (page > 0) out.add(paginationFooter(page, false));
        } else {
            entries.forEach(e -> out.add(formatContainerEntry(e, canTeleport, includeCoords)));
            out.add(summary(entries.size(), page, "container change(s)"));
            out.add(paginationFooter(page, entries.size() >= pageSize));
        }
        return out;
    }


    /** Action color — distinct hue per action type so the chat reads at a glance. */
    private static ChatFormatting blockActionColor(String action) {
        return switch (action) {
            case BlockLogEntry.ACTION_CREATE  -> ChatFormatting.GREEN;
            case BlockLogEntry.ACTION_DESTROY -> ChatFormatting.RED;
            case BlockLogEntry.ACTION_REPLACE -> ChatFormatting.GOLD;
            case BlockLogEntry.ACTION_EXPLODE -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.WHITE;
        };
    }

    /** Returns a styled block-display component that is hoverable to show full block-state IDs. */
    private static MutableComponent formatBlockDisplay(BlockLogEntry e) {
        if (e.action().equals(BlockLogEntry.ACTION_REPLACE)) {
            MutableComponent c = itemComponent(e.blockBefore(), ChatFormatting.GRAY);
            c.append(Component.literal(" \u2192 ").withStyle(ChatFormatting.DARK_GRAY));
            c.append(itemComponent(e.blockAfter(), ChatFormatting.WHITE));
            return c;
        } else if (e.action().equals(BlockLogEntry.ACTION_DESTROY)
                || e.action().equals(BlockLogEntry.ACTION_EXPLODE)) {
            return itemComponent(e.blockBefore(), ChatFormatting.WHITE);
        } else {
            return itemComponent(e.blockAfter(), ChatFormatting.WHITE);
        }
    }

    /** Block/item name, colored, with hover showing the full resource ID + state. */
    private static MutableComponent itemComponent(String resourceOrState, ChatFormatting color) {
        String display = displayName(resourceOrState);
        Component hover = Component.literal(resourceOrState == null ? "AIR" : resourceOrState)
            .withStyle(ChatFormatting.GRAY);
        return Component.literal(display).withStyle(
            Style.EMPTY.withColor(color).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    private static MutableComponent timestampComponent(long timestamp) {
        String shortTs = TIMESTAMP_FMT.format(Instant.ofEpochMilli(timestamp));
        String fullTs  = TIMESTAMP_FULL_FMT.format(Instant.ofEpochMilli(timestamp));
        return Component.literal("[" + shortTs + "] ").withStyle(
            Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal(fullTs).withStyle(ChatFormatting.GRAY))));
    }

    private static MutableComponent playerComponent(String name, String actorType) {
        ChatFormatting color = switch (actorType == null ? "" : actorType) {
            case "ENTITY"    -> ChatFormatting.YELLOW;
            case "EXPLOSION" -> ChatFormatting.DARK_RED;
            default          -> ChatFormatting.GREEN;
        };
        return Component.literal(name).withStyle(color);
    }

    private static MutableComponent actionComponent(String action, ChatFormatting color) {
        return Component.literal(" " + action + " ").withStyle(
            Style.EMPTY.withColor(color).withBold(true));
    }

    /** Coordinate triple, click-to-teleport for admins, hover always shows dimension. */
    private static MutableComponent coordComponent(int x, int y, int z, String world,
                                                   boolean canTeleport) {
        String text = "(" + x + ", " + y + ", " + z + ")";
        Style style = Style.EMPTY.withColor(ChatFormatting.GOLD);
        if (canTeleport) {
            String dimension = world == null ? "minecraft:overworld" : world;
            String cmd = "/execute in " + dimension + " run tp @s " + x + " " + y + " " + z;
            Component hover = Component.literal("Click to teleport to " + text + " in " + dimension)
                .withStyle(ChatFormatting.GREEN);
            style = style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .withUnderlined(true);
        } else {
            Component hover = Component.literal("Position in " + (world == null ? "?" : world))
                .withStyle(ChatFormatting.GRAY);
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
        }
        return Component.literal(text).withStyle(style);
    }

    /**
     * Converts a serialized block/item ID (possibly with state properties) to a display name.
     * "minecraft:spruce_slab[type=top,waterlogged=false]" → "SPRUCE_SLAB"
     */
    public static String displayName(String resourceOrState) {
        if (resourceOrState == null || resourceOrState.isBlank()) return "AIR";
        int bracket = resourceOrState.indexOf('[');
        String id = bracket >= 0 ? resourceOrState.substring(0, bracket) : resourceOrState;
        String path = id.contains(":") ? id.split(":", 2)[1] : id;
        return path.toUpperCase();
    }

    /** Formats elapsed ms as "N minutes / N hours / N days" relative to now. */
    private static String formatWindow(long sinceMs) {
        long minutes = (System.currentTimeMillis() - sinceMs) / 60_000;
        if (minutes < 60) return minutes + " minutes";
        long hours = minutes / 60;
        if (hours < 48) return hours + " hours";
        return (hours / 24) + " days";
    }
}
