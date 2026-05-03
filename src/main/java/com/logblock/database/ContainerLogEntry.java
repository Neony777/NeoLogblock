package com.logblock.database;

public record ContainerLogEntry(
    long id,
    String world,
    int x,
    int y,
    int z,
    String containerType,
    String item,
    int amount,
    String actorName,
    String action,
    long timestamp
) {
    public static final String ACTION_TAKE = "took";
    public static final String ACTION_PUT  = "put";
}
