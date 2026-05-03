package com.logblock.database;

public record InteractionLogEntry(
    long id,
    String world,
    int x,
    int y,
    int z,
    String blockType,
    String action,
    String actorName,
    long timestamp
) {
    public static final String ACTION_OPENED   = "opened";
    public static final String ACTION_CLOSED   = "closed";
    public static final String ACTION_PRESSED  = "pressed";
    public static final String ACTION_FLIPPED  = "flipped";
    public static final String ACTION_STEPPED  = "stepped on";
}
