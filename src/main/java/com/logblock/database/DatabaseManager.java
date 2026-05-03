package com.logblock.database;

import com.logblock.LogBlockMod;
import com.logblock.config.LogBlockConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DatabaseManager {

    /** Single shared connection used in SQLite mode. Null when running on MySQL. */
    private Connection sqliteConnection;
    /** HikariCP pool used in MySQL mode. Null when running on SQLite. */
    private HikariDataSource mysqlPool;
    private boolean isMysql = false;

    private final ThreadPoolExecutor writeQueue = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        r -> {
            Thread t = new Thread(r, "logblock-db-writer");
            t.setDaemon(true);
            return t;
        });

    public void initialize() {
        String type = LogBlockConfig.DATABASE_TYPE.get();
        String legacyUrl = LogBlockConfig.DATABASE_URL.get();
        boolean wantMysql =
            "mysql".equalsIgnoreCase(type)
                || "mariadb".equalsIgnoreCase(type)
                || legacyUrl.startsWith("jdbc:mysql:")
                || legacyUrl.startsWith("jdbc:mariadb:");

        if (wantMysql && initializeMysql(legacyUrl)) {
            isMysql = true;
        } else {
            if (wantMysql) {
                LogBlockMod.LOGGER.error(
                    "LogBlock: MySQL initialization failed; falling back to SQLite. " +
                    "Check the [database] section of the config and verify the JDBC driver is installed.");
            }
            initializeSqlite(legacyUrl);
            isMysql = false;
        }

        try {
            createSchema();
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: failed to create database schema", e);
        }
    }

    private boolean initializeMysql(String legacyUrl) {
        // Make sure a JDBC driver is on the classpath. Try both common drivers
        // and remember which one we got so we can pick the right URL scheme.
        boolean haveMysql = false;
        boolean haveMariadb = false;
        try { Class.forName("com.mysql.cj.jdbc.Driver"); haveMysql = true; }
        catch (ClassNotFoundException ignored) { /* try mariadb */ }
        try { Class.forName("org.mariadb.jdbc.Driver"); haveMariadb = true; }
        catch (ClassNotFoundException ignored) { /* none */ }
        if (!haveMysql && !haveMariadb) {
            LogBlockMod.LOGGER.error(
                "LogBlock: no MySQL/MariaDB JDBC driver found on the classpath. " +
                "Install mysql-connector-j or mariadb-java-client in the server's mods folder.");
            return false;
        }

        String jdbcUrl;
        String user = null;
        String pass = null;
        if (!legacyUrl.isBlank()
            && (legacyUrl.startsWith("jdbc:mysql:") || legacyUrl.startsWith("jdbc:mariadb:"))) {
            jdbcUrl = legacyUrl;
        } else {
            // If only the MariaDB driver is available, use the jdbc:mariadb:
            // scheme — newer mariadb-java-client versions no longer accept
            // jdbc:mysql:// URLs.
            String scheme = (!haveMysql && haveMariadb) ? "jdbc:mariadb://" : "jdbc:mysql://";
            jdbcUrl = scheme
                + LogBlockConfig.MYSQL_HOST.get() + ":"
                + LogBlockConfig.MYSQL_PORT.get() + "/"
                + LogBlockConfig.MYSQL_DATABASE.get();
            user = LogBlockConfig.MYSQL_USER.get();
            pass = LogBlockConfig.MYSQL_PASSWORD.get();
        }

        HikariDataSource ds = null;
        try {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(jdbcUrl);
            if (user != null) hc.setUsername(user);
            if (pass != null) hc.setPassword(pass);
            hc.setMaximumPoolSize(LogBlockConfig.MYSQL_POOL_SIZE.get());
            hc.setMinimumIdle(1);
            hc.setPoolName("LogBlock-Hikari");
            hc.setConnectionTimeout(10_000L);
            hc.setValidationTimeout(5_000L);
            hc.setLeakDetectionThreshold(60_000L);
            // Cap how long the constructor will block waiting for the very first
            // connection. We still validate connectivity ourselves below.
            hc.setInitializationFailTimeout(5_000L);

            ds = new HikariDataSource(hc);

            // Force-acquire one connection to verify connectivity before we commit.
            try (Connection c = ds.getConnection()) {
                if (!c.isValid(5)) {
                    throw new SQLException("HikariCP test connection reported invalid");
                }
            }

            mysqlPool = ds;
            LogBlockMod.LOGGER.info(
                "LogBlock: MySQL/MariaDB connected via HikariCP (poolSize={}) at {}",
                LogBlockConfig.MYSQL_POOL_SIZE.get(), sanitizeJdbcUrl(jdbcUrl));
            return true;
        } catch (Exception e) {
            LogBlockMod.LOGGER.error(
                "LogBlock: failed to connect to MySQL/MariaDB at {}",
                sanitizeJdbcUrl(jdbcUrl), e);
            if (ds != null) {
                try { ds.close(); } catch (Exception ignored) { /* nothing useful to do */ }
            }
            mysqlPool = null;
            return false;
        }
    }

    private void initializeSqlite(String legacyUrl) {
        try {
            Class.forName("org.sqlite.JDBC");
            String jdbcUrl = (!legacyUrl.isBlank() && legacyUrl.startsWith("jdbc:sqlite:"))
                ? legacyUrl
                : buildSqliteUrl();
            sqliteConnection = DriverManager.getConnection(jdbcUrl);
            try (Statement s = sqliteConnection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL;");
                s.execute("PRAGMA synchronous=NORMAL;");
            }
            LogBlockMod.LOGGER.info("LogBlock: SQLite connected at {}", jdbcUrl);
        } catch (Exception e) {
            LogBlockMod.LOGGER.error("LogBlock: failed to initialize SQLite database", e);
        }
    }

    /**
     * Strip credentials from a JDBC URL before logging it. Handles the common
     * forms: {@code user:pass@host}, query parameters {@code user=}/{@code password=},
     * and is case-insensitive. We intentionally err on the side of over-redacting:
     * better to lose a hostname in a log line than to leak a password.
     */
    static String sanitizeJdbcUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        String safe = url;
        // Strip user:pass@ between scheme and host (e.g. jdbc:mysql://user:pass@host:3306/db)
        safe = safe.replaceAll("(?i)(jdbc:[a-z0-9]+://)[^/@?]+@", "$1***@");
        // Strip ?user=...&password=... (case-insensitive, until next & or end)
        safe = safe.replaceAll("(?i)([?&;])(user|username|password|passwd|pwd)=[^&;]*", "$1$2=***");
        return safe;
    }

    private String buildSqliteUrl() {
        String rawPath = LogBlockConfig.DATABASE_PATH.get();
        Path dbPath = new File(rawPath).isAbsolute()
            ? Path.of(rawPath)
            : FMLPaths.CONFIGDIR.get().resolve(rawPath);
        dbPath.getParent().toFile().mkdirs();
        return "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    /**
     * Acquire a JDBC connection. In MySQL mode this returns a fresh pooled
     * connection that MUST be released via {@link #release(Connection)}. In
     * SQLite mode it returns the shared single connection; release() is a no-op.
     */
    private Connection borrow() throws SQLException {
        if (mysqlPool != null) return mysqlPool.getConnection();
        if (sqliteConnection == null) {
            throw new SQLException("LogBlock database is not initialized");
        }
        return sqliteConnection;
    }

    private void release(Connection c) {
        if (mysqlPool != null && c != null) {
            try { c.close(); } catch (SQLException ignored) { /* return to pool best-effort */ }
        }
    }

    private void createSchema() throws SQLException {
        String idCol  = isMysql ? "BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id)"
                                : "INTEGER PRIMARY KEY AUTOINCREMENT";
        // Short, indexed identifier-style columns (world, actor, action, etc.).
        String textT  = isMysql ? "VARCHAR(255)" : "TEXT";
        // Potentially long serialized strings: block state with properties,
        // item identifiers with NBT, modded entity names. Use TEXT on MySQL so
        // strict mode does not reject inserts that exceed 255 chars.
        String longT  = isMysql ? "TEXT"         : "TEXT";
        String bigT   = isMysql ? "MEDIUMTEXT"   : "TEXT";
        String realT  = isMysql ? "DOUBLE"       : "REAL";
        String intT   = isMysql ? "INT"          : "INTEGER";
        String bigInt = isMysql ? "BIGINT"       : "INTEGER";
        String tableSuffix = isMysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";

        Connection c = borrow();
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS block_logs (" +
                "id               " + idCol + "," +
                "world            " + textT  + " NOT NULL," +
                "x                " + intT   + " NOT NULL," +
                "y                " + intT   + " NOT NULL," +
                "z                " + intT   + " NOT NULL," +
                "block_before     " + longT  + " NOT NULL," +
                "block_after      " + longT  + " NOT NULL," +
                "block_entity_nbt " + bigT   + "," +
                "actor_name       " + textT  + " NOT NULL," +
                "actor_type       " + textT  + " NOT NULL," +
                "action           " + textT  + " NOT NULL," +
                "timestamp        " + bigInt + " NOT NULL" +
                ")" + tableSuffix);
            createIndex(s, "idx_bl_pos",   "block_logs",     "world, x, y, z");
            createIndex(s, "idx_bl_actor", "block_logs",     "actor_name");
            createIndex(s, "idx_bl_time",  "block_logs",     "timestamp");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS container_logs (" +
                "id             " + idCol + "," +
                "world          " + textT  + " NOT NULL," +
                "x              " + intT   + " NOT NULL," +
                "y              " + intT   + " NOT NULL," +
                "z              " + intT   + " NOT NULL," +
                "container_type " + textT  + " NOT NULL," +
                "item           " + longT  + " NOT NULL," +
                "amount         " + intT   + " NOT NULL," +
                "actor_name     " + textT  + " NOT NULL," +
                "action         " + textT  + " NOT NULL," +
                "timestamp      " + bigInt + " NOT NULL" +
                ")" + tableSuffix);
            createIndex(s, "idx_cl_pos",   "container_logs", "world, x, y, z");
            createIndex(s, "idx_cl_actor", "container_logs", "actor_name");
            createIndex(s, "idx_cl_time",  "container_logs", "timestamp");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS entity_logs (" +
                "id          " + idCol + "," +
                "world       " + textT  + " NOT NULL," +
                "x           " + realT  + " NOT NULL," +
                "y           " + realT  + " NOT NULL," +
                "z           " + realT  + " NOT NULL," +
                "entity_type " + textT  + " NOT NULL," +
                "entity_name " + longT  + " NOT NULL," +
                "killer_name " + textT  + " NOT NULL," +
                "killer_type " + textT  + " NOT NULL," +
                "timestamp   " + bigInt + " NOT NULL" +
                ")" + tableSuffix);
            createIndex(s, "idx_el_killer", "entity_logs", "killer_name");
            createIndex(s, "idx_el_time",   "entity_logs", "timestamp");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS interaction_logs (" +
                "id         " + idCol + "," +
                "world      " + textT  + " NOT NULL," +
                "x          " + intT   + " NOT NULL," +
                "y          " + intT   + " NOT NULL," +
                "z          " + intT   + " NOT NULL," +
                "block_type " + textT  + " NOT NULL," +
                "action     " + textT  + " NOT NULL," +
                "actor_name " + textT  + " NOT NULL," +
                "timestamp  " + bigInt + " NOT NULL" +
                ")" + tableSuffix);
            createIndex(s, "idx_il_pos",   "interaction_logs", "world, x, y, z");
            createIndex(s, "idx_il_actor", "interaction_logs", "actor_name");
            createIndex(s, "idx_il_time",  "interaction_logs", "timestamp");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_sessions (" +
                "id          " + idCol + "," +
                "player_name " + textT  + " NOT NULL," +
                "world       " + textT  + " NOT NULL," +
                "blocks_json " + bigT   + " NOT NULL," +
                "created_at  " + bigInt + " NOT NULL," +
                "completed   " + intT   + " NOT NULL DEFAULT 0" +
                ")" + tableSuffix);

            migrateAddColumnIfMissing(s, "block_logs", "block_entity_nbt", bigT);
        } finally {
            release(c);
        }
    }

    /**
     * Creates a named index on a table in a dialect-safe way.
     * SQLite supports {@code CREATE INDEX IF NOT EXISTS}; MySQL does not, so we
     * catch duplicate-index errors (error code 1061) and treat them as success.
     */
    private void createIndex(Statement s, String name, String table, String columns) throws SQLException {
        if (isMysql) {
            try {
                s.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
            } catch (SQLException e) {
                if (e.getErrorCode() != 1061) {
                    throw e;
                }
            }
        } else {
            s.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
        }
    }

    private void migrateAddColumnIfMissing(Statement s, String table, String column, String type) {
        try {
            s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException ignored) {
            // Column already exists — this is expected on subsequent startups
            // (SQLite: "duplicate column name", MySQL error 1060).
        }
    }


    public void logBlockChange(String world, int x, int y, int z,
                               String blockBefore, String blockAfter,
                               String blockEntityNbt,
                               String actorName, String actorType, String action) {
        long ts = System.currentTimeMillis();
        writeQueue.submit(() -> {
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO block_logs " +
                        "(world,x,y,z,block_before,block_after,block_entity_nbt,actor_name,actor_type,action,timestamp) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, world);
                    ps.setInt(2, x);
                    ps.setInt(3, y);
                    ps.setInt(4, z);
                    ps.setString(5, blockBefore);
                    ps.setString(6, blockAfter);
                    ps.setString(7, blockEntityNbt);
                    ps.setString(8, actorName);
                    ps.setString(9, actorType);
                    ps.setString(10, action);
                    ps.setLong(11, ts);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: failed to write block log", e);
            } finally {
                release(c);
            }
        });
    }

    public void logContainerChange(String world, int x, int y, int z,
                                   String containerType, String item, int amount,
                                   String actorName, String action) {
        long ts = System.currentTimeMillis();
        writeQueue.submit(() -> {
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO container_logs " +
                        "(world,x,y,z,container_type,item,amount,actor_name,action,timestamp) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, world);
                    ps.setInt(2, x);
                    ps.setInt(3, y);
                    ps.setInt(4, z);
                    ps.setString(5, containerType);
                    ps.setString(6, item);
                    ps.setInt(7, amount);
                    ps.setString(8, actorName);
                    ps.setString(9, action);
                    ps.setLong(10, ts);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: failed to write container log", e);
            } finally {
                release(c);
            }
        });
    }

    public void logEntityKill(String world, double x, double y, double z,
                              String entityType, String entityName,
                              String killerName, String killerType) {
        long ts = System.currentTimeMillis();
        writeQueue.submit(() -> {
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO entity_logs " +
                        "(world,x,y,z,entity_type,entity_name,killer_name,killer_type,timestamp) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, world);
                    ps.setDouble(2, x);
                    ps.setDouble(3, y);
                    ps.setDouble(4, z);
                    ps.setString(5, entityType);
                    ps.setString(6, entityName);
                    ps.setString(7, killerName);
                    ps.setString(8, killerType);
                    ps.setLong(9, ts);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: failed to write entity log", e);
            } finally {
                release(c);
            }
        });
    }

    public void logInteraction(String world, int x, int y, int z,
                               String blockType, String action, String actorName) {
        long ts = System.currentTimeMillis();
        writeQueue.submit(() -> {
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO interaction_logs " +
                        "(world,x,y,z,block_type,action,actor_name,timestamp) " +
                        "VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, world);
                    ps.setInt(2, x);
                    ps.setInt(3, y);
                    ps.setInt(4, z);
                    ps.setString(5, blockType);
                    ps.setString(6, action);
                    ps.setString(7, actorName);
                    ps.setLong(8, ts);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: failed to write interaction log", e);
            } finally {
                release(c);
            }
        });
    }

    public void saveRollbackSession(String playerName, String world, String blocksJson) {
        long ts = System.currentTimeMillis();
        writeQueue.submit(() -> {
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO rollback_sessions (player_name,world,blocks_json,created_at,completed) VALUES (?,?,?,?,0)")) {
                    ps.setString(1, playerName);
                    ps.setString(2, world);
                    ps.setString(3, blocksJson);
                    ps.setLong(4, ts);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: failed to save rollback session", e);
            } finally {
                release(c);
            }
        });
    }

    public void markRollbackComplete(String playerName) {
        writeQueue.submit(() -> {
            Connection c = null;
            try {
                c = borrow();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE rollback_sessions SET completed=1 WHERE player_name=? AND completed=0")) {
                    ps.setString(1, playerName);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: failed to mark rollback complete", e);
            } finally {
                release(c);
            }
        });
    }


    public List<BlockLogEntry> queryBlockLogs(String world, int x, int y, int z,
                                              long sinceMs, int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM block_logs WHERE world=? AND x=? AND y=? AND z=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
                ps.setLong(5, sinceMs); ps.setInt(6, pageSize); ps.setLong(7, (long) page * pageSize);
                return readBlockLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public List<BlockLogEntry> queryBlockLogsByPlayer(String actorName, long sinceMs,
                                                      int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM block_logs WHERE actor_name=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, actorName); ps.setLong(2, sinceMs);
                ps.setInt(3, pageSize); ps.setLong(4, (long) page * pageSize);
                return readBlockLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public List<ContainerLogEntry> queryContainerLogsByPlayer(String actorName, long sinceMs,
                                                              int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM container_logs WHERE actor_name=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, actorName); ps.setLong(2, sinceMs);
                ps.setInt(3, pageSize); ps.setLong(4, (long) page * pageSize);
                return readContainerLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    /** Returns the most recent incomplete rollback session JSON per player name. */
    public Map<String, String> loadPendingRollbackSessions() {
        Map<String, String> result = new LinkedHashMap<>();
        Connection c = null;
        try {
            c = borrow();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                    "SELECT player_name, blocks_json FROM rollback_sessions " +
                    "WHERE completed=0 ORDER BY created_at DESC")) {
                while (rs.next()) {
                    String player = rs.getString("player_name");
                    result.putIfAbsent(player, rs.getString("blocks_json"));
                }
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: failed to load rollback sessions", e);
        } finally {
            release(c);
        }
        return result;
    }

    public List<BlockLogEntry> queryBlockLogsInRadius(String world, int cx, int cy, int cz,
                                                      int radius, long sinceMs,
                                                      int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM block_logs WHERE world=? " +
                    "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                    "AND timestamp>=? ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, world);
                ps.setInt(2, cx - radius); ps.setInt(3, cx + radius);
                ps.setInt(4, cy - radius); ps.setInt(5, cy + radius);
                ps.setInt(6, cz - radius); ps.setInt(7, cz + radius);
                ps.setLong(8, sinceMs); ps.setInt(9, pageSize); ps.setLong(10, (long) page * pageSize);
                return readBlockLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public List<ContainerLogEntry> queryContainerLogs(String world, int x, int y, int z,
                                                      long sinceMs, int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM container_logs WHERE world=? AND x=? AND y=? AND z=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
                ps.setLong(5, sinceMs); ps.setInt(6, pageSize); ps.setLong(7, (long) page * pageSize);
                return readContainerLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    /** Returns all entries in a given area newer than sinceMs, newest first — used by rollback. */
    public List<BlockLogEntry> queryForRollback(String world, int cx, int cy, int cz,
                                                int radius, long sinceMs) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM block_logs WHERE world=? " +
                    "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                    "AND timestamp>=? ORDER BY timestamp DESC")) {
                ps.setString(1, world);
                ps.setInt(2, cx - radius); ps.setInt(3, cx + radius);
                ps.setInt(4, cy - radius); ps.setInt(5, cy + radius);
                ps.setInt(6, cz - radius); ps.setInt(7, cz + radius);
                ps.setLong(8, sinceMs);
                return readBlockLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: rollback query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public int getDatabaseSize() {
        Connection c = null;
        try {
            c = borrow();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM block_logs")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        } finally {
            release(c);
        }
    }

    public int getContainerLogSize() {
        Connection c = null;
        try {
            c = borrow();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM container_logs")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        } finally {
            release(c);
        }
    }

    public List<InteractionLogEntry> queryInteractionLogs(String world, int x, int y, int z,
                                                          long sinceMs, int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM interaction_logs WHERE world=? AND x=? AND y=? AND z=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
                ps.setLong(5, sinceMs); ps.setInt(6, pageSize); ps.setLong(7, (long) page * pageSize);
                return readInteractionLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public List<InteractionLogEntry> queryInteractionLogsByPlayer(String actorName, long sinceMs,
                                                                  int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM interaction_logs WHERE actor_name=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, actorName); ps.setLong(2, sinceMs);
                ps.setInt(3, pageSize); ps.setLong(4, (long) page * pageSize);
                return readInteractionLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public List<ContainerLogEntry> queryContainerLogsInRadius(String world, int cx, int cy, int cz,
                                                              int radius, long sinceMs,
                                                              int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM container_logs WHERE world=? " +
                    "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                    "AND timestamp>=? ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, world);
                ps.setInt(2, cx - radius); ps.setInt(3, cx + radius);
                ps.setInt(4, cy - radius); ps.setInt(5, cy + radius);
                ps.setInt(6, cz - radius); ps.setInt(7, cz + radius);
                ps.setLong(8, sinceMs); ps.setInt(9, pageSize); ps.setLong(10, (long) page * pageSize);
                return readContainerLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    public List<InteractionLogEntry> queryInteractionLogsInRadius(String world, int cx, int cy, int cz,
                                                                  int radius, long sinceMs,
                                                                  int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM interaction_logs WHERE world=? " +
                    "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                    "AND timestamp>=? ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, world);
                ps.setInt(2, cx - radius); ps.setInt(3, cx + radius);
                ps.setInt(4, cy - radius); ps.setInt(5, cy + radius);
                ps.setInt(6, cz - radius); ps.setInt(7, cz + radius);
                ps.setLong(8, sinceMs); ps.setInt(9, pageSize); ps.setLong(10, (long) page * pageSize);
                return readInteractionLogs(ps.executeQuery());
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: query failed", e);
            return Collections.emptyList();
        } finally {
            release(c);
        }
    }

    /** Cheap COUNT(*) for one log table at one coordinate within a time window. */
    public int countLogsAt(String table, String world, int x, int y, int z, long sinceMs) {
        if (!table.equals("block_logs") && !table.equals("container_logs")
                && !table.equals("interaction_logs")) {
            throw new IllegalArgumentException("countLogsAt: unsupported table " + table);
        }
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE world=? AND x=? AND y=? AND z=? AND timestamp>=?")) {
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
                ps.setLong(5, sinceMs);
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: count failed", e);
            return 0;
        } finally {
            release(c);
        }
    }

    /** Cheap COUNT(*) for one log table within a cubic radius and time window. */
    public int countLogsInRadius(String table, String world, int cx, int cy, int cz,
                                 int radius, long sinceMs) {
        if (!table.equals("block_logs") && !table.equals("container_logs")
                && !table.equals("interaction_logs")) {
            throw new IllegalArgumentException("countLogsInRadius: unsupported table " + table);
        }
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE world=? " +
                    "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                    "AND timestamp>=?")) {
                ps.setString(1, world);
                ps.setInt(2, cx - radius); ps.setInt(3, cx + radius);
                ps.setInt(4, cy - radius); ps.setInt(5, cy + radius);
                ps.setInt(6, cz - radius); ps.setInt(7, cz + radius);
                ps.setLong(8, sinceMs);
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: count failed", e);
            return 0;
        } finally {
            release(c);
        }
    }

    public int getInteractionLogSize() {
        Connection c = null;
        try {
            c = borrow();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM interaction_logs")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        } finally {
            release(c);
        }
    }

    public int getEntityLogSize() {
        Connection c = null;
        try {
            c = borrow();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM entity_logs")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return -1;
        } finally {
            release(c);
        }
    }

    public List<EntityLogEntry> queryEntityLogsByPlayer(String killerName, long sinceMs, int page, int pageSize) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM entity_logs WHERE killer_name=? AND timestamp>=? " +
                    "ORDER BY timestamp DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, killerName);
                ps.setLong(2, sinceMs);
                ps.setInt(3, pageSize);
                ps.setInt(4, page * pageSize);
                ResultSet rs = ps.executeQuery();
                List<EntityLogEntry> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new EntityLogEntry(
                        rs.getLong("id"), rs.getString("world"),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getString("entity_type"), rs.getString("entity_name"),
                        rs.getString("killer_name"), rs.getString("killer_type"),
                        rs.getLong("timestamp")
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.warn("LogBlock: queryEntityLogsByPlayer failed: {}", e.getMessage());
            return List.of();
        } finally {
            release(c);
        }
    }

    public int getWriteQueueDepth() {
        return (int) writeQueue.getQueue().size();
    }

    /**
     * Deletes log rows older than {@code cutoffMs} from every log table and
     * any completed rollback sessions. Submitted to the single-threaded write
     * queue so it serialises naturally with concurrent inserts. Returns the
     * total number of rows removed.
     *
     * <p>Each table is purged in its own transaction; an exception on one
     * table is logged but does not abort the others.
     */
    public PurgeResult purgeOlderThan(long cutoffMs) {
        try {
            return writeQueue.submit(() -> doPurge(cutoffMs)).get();
        } catch (Exception e) {
            LogBlockMod.LOGGER.error("LogBlock: purge failed", e);
            return new PurgeResult(0, 0, 0, 0, 0);
        }
    }

    /**
     * Fire-and-forget variant of {@link #purgeOlderThan(long)}. Submits the work
     * to the writer thread and invokes {@code onDone} on that same thread when
     * finished. Use this from the server tick path so a large first purge cannot
     * stall ticks or trip the server watchdog.
     */
    public void purgeOlderThanAsync(long cutoffMs, java.util.function.Consumer<PurgeResult> onDone) {
        writeQueue.submit(() -> {
            PurgeResult r = doPurge(cutoffMs);
            try { onDone.accept(r); } catch (Exception e) {
                LogBlockMod.LOGGER.error("LogBlock: purge callback failed", e);
            }
        });
    }

    private PurgeResult doPurge(long cutoffMs) {
        int blocks       = deleteOlderThan("block_logs",       "timestamp", cutoffMs);
        int containers   = deleteOlderThan("container_logs",   "timestamp", cutoffMs);
        int entities     = deleteOlderThan("entity_logs",      "timestamp", cutoffMs);
        int interactions = deleteOlderThan("interaction_logs", "timestamp", cutoffMs);
        int sessions     = deleteCompletedRollbackSessionsOlderThan(cutoffMs);
        return new PurgeResult(blocks, containers, entities, interactions, sessions);
    }

    private int deleteOlderThan(String table, String tsCol, long cutoffMs) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + table + " WHERE " + tsCol + " < ?")) {
                ps.setLong(1, cutoffMs);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: failed to purge {}: {}", table, e.getMessage());
            return 0;
        } finally {
            release(c);
        }
    }

    private int deleteCompletedRollbackSessionsOlderThan(long cutoffMs) {
        Connection c = null;
        try {
            c = borrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM rollback_sessions WHERE completed=1 AND created_at < ?")) {
                ps.setLong(1, cutoffMs);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            LogBlockMod.LOGGER.error("LogBlock: failed to purge rollback_sessions: {}", e.getMessage());
            return 0;
        } finally {
            release(c);
        }
    }

    /** Result of a purge run; counts are per-table. */
    public record PurgeResult(int blockLogs, int containerLogs, int entityLogs,
                              int interactionLogs, int rollbackSessions) {
        public int total() {
            return blockLogs + containerLogs + entityLogs + interactionLogs + rollbackSessions;
        }
    }


    private List<BlockLogEntry> readBlockLogs(ResultSet rs) throws SQLException {
        List<BlockLogEntry> result = new ArrayList<>();
        while (rs.next()) {
            result.add(new BlockLogEntry(
                rs.getLong("id"), rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("block_before"), rs.getString("block_after"),
                rs.getString("block_entity_nbt"),
                rs.getString("actor_name"), rs.getString("actor_type"),
                rs.getString("action"), rs.getLong("timestamp")
            ));
        }
        return result;
    }

    private List<InteractionLogEntry> readInteractionLogs(ResultSet rs) throws SQLException {
        List<InteractionLogEntry> result = new ArrayList<>();
        while (rs.next()) {
            result.add(new InteractionLogEntry(
                rs.getLong("id"), rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("block_type"), rs.getString("action"),
                rs.getString("actor_name"), rs.getLong("timestamp")
            ));
        }
        return result;
    }

    private List<ContainerLogEntry> readContainerLogs(ResultSet rs) throws SQLException {
        List<ContainerLogEntry> result = new ArrayList<>();
        while (rs.next()) {
            result.add(new ContainerLogEntry(
                rs.getLong("id"), rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("container_type"), rs.getString("item"),
                rs.getInt("amount"), rs.getString("actor_name"),
                rs.getString("action"), rs.getLong("timestamp")
            ));
        }
        return result;
    }

    public void shutdown() {
        writeQueue.shutdown();
        try {
            if (!writeQueue.awaitTermination(10, TimeUnit.SECONDS)) {
                writeQueue.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (mysqlPool != null) {
            try { mysqlPool.close(); }
            catch (Exception e) { LogBlockMod.LOGGER.error("LogBlock: error closing HikariCP pool", e); }
            mysqlPool = null;
        }
        if (sqliteConnection != null) {
            try {
                if (!sqliteConnection.isClosed()) sqliteConnection.close();
            } catch (SQLException e) {
                LogBlockMod.LOGGER.error("LogBlock: error closing SQLite connection", e);
            }
            sqliteConnection = null;
        }
    }
}
