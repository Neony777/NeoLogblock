package com.logblock.database;

public record BlockLogEntry(
    long id,
    String world,
    int x,
    int y,
    int z,
    String blockBefore,
    String blockAfter,
    String blockEntityNbt,
    String actorName,
    String actorType,
    String action,
    long timestamp
) {
    public static final String ACTION_DESTROY  = "destroyed";
    public static final String ACTION_CREATE   = "created";
    public static final String ACTION_REPLACE  = "replaced";
    public static final String ACTION_EXPLODE  = "exploded";

    public static final String ACTOR_PLAYER    = "PLAYER";
    public static final String ACTOR_ENTITY    = "ENTITY";
    public static final String ACTOR_EXPLOSION = "EXPLOSION";
}
