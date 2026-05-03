package com.logblock.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class LogBlockConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> INSPECTOR_ITEM;
    public static final ModConfigSpec.IntValue MAX_RESULTS_PER_PAGE;
    public static final ModConfigSpec.LongValue DEFAULT_TIME_WINDOW_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_PATH;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_URL;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_TYPE;
    public static final ModConfigSpec.ConfigValue<String> MYSQL_HOST;
    public static final ModConfigSpec.IntValue MYSQL_PORT;
    public static final ModConfigSpec.ConfigValue<String> MYSQL_USER;
    public static final ModConfigSpec.ConfigValue<String> MYSQL_PASSWORD;
    public static final ModConfigSpec.ConfigValue<String> MYSQL_DATABASE;
    public static final ModConfigSpec.IntValue MYSQL_POOL_SIZE;
    public static final ModConfigSpec.BooleanValue LOG_BLOCKS;
    public static final ModConfigSpec.BooleanValue LOG_CONTAINERS;
    public static final ModConfigSpec.BooleanValue LOG_EXPLOSIONS;
    public static final ModConfigSpec.BooleanValue LOG_ENTITIES;
    public static final ModConfigSpec.IntValue MAX_ROLLBACK_RADIUS;
    public static final ModConfigSpec.BooleanValue PLAY_SOUNDS;
    public static final ModConfigSpec.DoubleValue SOUND_VOLUME;
    public static final ModConfigSpec.IntValue TELEPORT_PERMISSION_LEVEL;
    public static final ModConfigSpec.IntValue PURGE_DAYS_OLDER_THAN;

    static {
        BUILDER.comment("Inspector Settings").push("inspector");

        INSPECTOR_ITEM = BUILDER
            .comment("Item used to right-click blocks and inspect their history (full item ID).")
            .define("inspectorItem", "minecraft:wooden_pickaxe");

        MAX_RESULTS_PER_PAGE = BUILDER
            .comment("Log entries shown per page in chat.")
            .defineInRange("maxResultsPerPage", 10, 1, 50);

        DEFAULT_TIME_WINDOW_MINUTES = BUILDER
            .comment("Default query time window in minutes. 43200 = 30 days.")
            .defineInRange("defaultTimeWindowMinutes", 43200L, 1L, Long.MAX_VALUE);

        BUILDER.pop();
        BUILDER.comment("Database Settings").push("database");

        DATABASE_TYPE = BUILDER
            .comment("Database backend: \"sqlite\" (default), \"mysql\", or \"mariadb\".",
                     "Use \"mysql\"/\"mariadb\" for MySQL or MariaDB on busy multi-player servers.",
                     "When set to \"mysql\"/\"mariadb\", a HikariCP-pooled connection is used and the",
                     "mysqlHost/mysqlPort/mysqlUser/mysqlPassword/mysqlDatabase settings apply.",
                     "Note: the JDBC driver itself is NOT bundled with this mod -- you must drop",
                     "either mysql-connector-j or mariadb-java-client into the server's mods folder.",
                     "If MySQL initialization fails the mod automatically falls back to SQLite.")
            .define("databaseType", "sqlite", o -> {
                if (!(o instanceof String s)) return false;
                String v = s.toLowerCase();
                return v.equals("sqlite") || v.equals("mysql") || v.equals("mariadb");
            });

        DATABASE_PATH = BUILDER
            .comment("SQLite database path, relative to config dir or absolute.",
                     "Used only when databaseType=sqlite and databaseUrl is blank.")
            .define("databasePath", "logblock/logblock.db");

        DATABASE_URL = BUILDER
            .comment("Optional full JDBC connection URL (advanced). When non-blank it",
                     "overrides databasePath / mysql* settings.",
                     "SQLite example:  jdbc:sqlite:/absolute/path/to/logblock.db",
                     "MySQL example:   jdbc:mysql://localhost:3306/logblock?user=root&password=secret",
                     "MySQL/MariaDB requires the corresponding JDBC driver JAR",
                     "(mysql-connector-j or mariadb-java-client) on the mods classpath.")
            .define("databaseUrl", "");

        MYSQL_HOST = BUILDER
            .comment("MySQL/MariaDB server host (used when databaseType=mysql).")
            .define("mysqlHost", "localhost");

        MYSQL_PORT = BUILDER
            .comment("MySQL/MariaDB server port.")
            .defineInRange("mysqlPort", 3306, 1, 65535);

        MYSQL_USER = BUILDER
            .comment("MySQL/MariaDB username.")
            .define("mysqlUser", "logblock");

        MYSQL_PASSWORD = BUILDER
            .comment("MySQL/MariaDB password.")
            .define("mysqlPassword", "");

        MYSQL_DATABASE = BUILDER
            .comment("MySQL/MariaDB database/schema name. Must already exist on the server.")
            .define("mysqlDatabase", "logblock");

        MYSQL_POOL_SIZE = BUILDER
            .comment("HikariCP maximum pool size for MySQL connections.",
                     "Tune up for very busy servers; the default is plenty for most.")
            .defineInRange("mysqlPoolSize", 10, 1, 100);

        BUILDER.pop();
        BUILDER.comment("Logging Categories").push("logging");

        LOG_BLOCKS = BUILDER
            .comment("Log block placements and destructions.")
            .define("logBlocks", true);

        LOG_CONTAINERS = BUILDER
            .comment("Log player interactions with containers.")
            .define("logContainers", true);

        LOG_EXPLOSIONS = BUILDER
            .comment("Log blocks destroyed by explosions.")
            .define("logExplosions", true);

        LOG_ENTITIES = BUILDER
            .comment("Log entity kills (mobs killed by players or other entities).")
            .define("logEntities", true);

        MAX_ROLLBACK_RADIUS = BUILDER
            .comment("Maximum radius allowed for /lb rollback (in blocks).")
            .defineInRange("maxRollbackRadius", 50, 1, 500);

        BUILDER.pop();
        BUILDER.comment("UI / Feedback Settings").push("ui");

        PLAY_SOUNDS = BUILDER
            .comment("Play subtle sound effects for inspections, queries, rollbacks, and errors.")
            .define("playSounds", true);

        SOUND_VOLUME = BUILDER
            .comment("Master volume for LogBlock sound effects (0.0 = silent, 1.0 = full).")
            .defineInRange("soundVolume", 0.4, 0.0, 1.0);

        TELEPORT_PERMISSION_LEVEL = BUILDER
            .comment("Minimum permission level required to use click-to-teleport on log entries.",
                     "0 = everyone, 2 = ops (default), 4 = server owner.")
            .defineInRange("teleportPermissionLevel", 2, 0, 4);

        BUILDER.pop();
        BUILDER.comment("Database Maintenance").push("maintenance");

        PURGE_DAYS_OLDER_THAN = BUILDER
            .comment("Automatic log pruning: delete entries older than this many days.",
                     "Runs once per in-game day (24000 ticks). Set to 0 to disable.",
                     "Affects block_logs, container_logs, entity_logs and completed rollback_sessions.",
                     "Note: SQLite reuses freed pages but does not shrink the .db file on disk;",
                     "to reclaim disk space, run VACUUM manually with the sqlite3 CLI when offline.")
            .defineInRange("purgeDaysOlderThan", 90, 0, 3650);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
