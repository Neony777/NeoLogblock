package com.logblock.database;

public record EntityLogEntry(
    long id,
    String world,
    double x,
    double y,
    double z,
    String entityType,
    String entityName,
    String killerName,
    String killerType,
    long timestamp
) {
    public static final String KILLER_PLAYER  = "PLAYER";
    public static final String KILLER_ENTITY  = "ENTITY";
    public static final String KILLER_UNKNOWN = "UNKNOWN";
}
