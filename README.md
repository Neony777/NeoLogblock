# NeoLogblock — NeoForge 1.21.1

By **Neo (Neonekpro)**.

A full-featured grief-logging and rollback mod for NeoForge 1.21.1, inspired by the LogBlock Bukkit plugin.

## Features

- **Block logging** — every block placement, destruction, and explosion is recorded with player name and timestamp
- **Container logging** — tracks items taken from or put into chests, barrels, furnaces, hoppers, dispensers, droppers, and shulker boxes
- **Sign text logging** — records edits to sign text (before → after) so you can see what was rewritten on a sign
- **Inspector tool** — right-click any block with a wooden pickaxe (configurable) to view its full change history in chat
- **Sneak + right-click** — cycles through pages of history for a block
- **Paginated chat output** — color-coded entries matching the original LogBlock plugin style
- **SQLite database** — all data persists across server restarts; no external database required
- **Optional MySQL/MariaDB backend** — switch on `databaseType=mysql` for HikariCP-pooled connections on busy multi-player servers (see _Database backend_ below)
- **Async write queue** — logging never blocks the main server thread
- **`/lb` command system** — advanced queries, area searches, and operator rollback/redo

## Downloads

- **GitHub Releases:** https://github.com/Neony777/NeoLogblock/releases
- **Direct (1.1.0):** https://github.com/Neony777/NeoLogblock/releases/download/v1.1.0/NeoLogblock-1.1.0.jar

## Installation

1. Download `NeoLogblock-1.1.0.jar` from the [Releases page](https://github.com/Neony777/NeoLogblock/releases) (or build it yourself — see _Building from Source_).
2. Place the JAR in your NeoForge 1.21.1 server's `mods/` folder.
3. Launch the server — the database is created automatically at `config/logblock/logblock.db`.

## Database backend

By default LogBlock uses an embedded SQLite database stored in
`config/logblock/logblock.db`. This is great for small/medium servers and
requires zero setup. Busy servers can switch to MySQL or MariaDB to get
proper connection pooling (via HikariCP) and offload database I/O to a
dedicated server.

### Switching to MySQL / MariaDB

1. Install the JDBC driver — **the driver is _not_ bundled with this mod**.
   Drop one of the following JARs into your server's `mods/` folder:
   - [mysql-connector-j](https://dev.mysql.com/downloads/connector/j/)
   - [mariadb-java-client](https://mariadb.com/kb/en/about-mariadb-connector-j/)
2. Create the target database/schema on your MySQL/MariaDB server, e.g.:
   ```sql
   CREATE DATABASE logblock CHARACTER SET utf8mb4;
   CREATE USER 'logblock'@'%' IDENTIFIED BY 'changeme';
   GRANT ALL PRIVILEGES ON logblock.* TO 'logblock'@'%';
   ```
3. Edit `config/logblock-server.toml` (created on first run) and set:
   ```toml
   [database]
   databaseType   = "mysql"        # or "mariadb"
   mysqlHost      = "localhost"
   mysqlPort      = 3306
   mysqlUser      = "logblock"
   mysqlPassword  = "changeme"
   mysqlDatabase  = "logblock"
   mysqlPoolSize  = 10              # HikariCP max pool size
   ```
4. Restart the server. On startup you should see:
   `LogBlock: MySQL/MariaDB connected via HikariCP (poolSize=10) at jdbc:mysql://...`

If the JDBC driver is missing, the server cannot reach the database, or
credentials are wrong, LogBlock logs a clear error and **automatically
falls back to SQLite** so logging keeps working. JDBC URLs are sanitized
before being logged so passwords are never leaked into server logs.

## Building from Source

### Requirements

- JDK 21
- Gradle 8.8+ (or use the included wrapper: `./gradlew`)
- Internet connection (downloads NeoForge MDK on first build)

### Setup

If you don't have the Gradle wrapper JAR yet:

```bash
gradle wrapper --gradle-version=8.8
chmod +x gradlew
```

### Build

```bash
./gradlew build
```

The output JAR is at `build/libs/NeoLogblock-1.1.0.jar`.

### Run a test server

```bash
./gradlew runServer
```

## Usage

### Inspector Tool

Hold a **wooden pickaxe** and **right-click** any block to see its history in chat:

```
Block changes in the last 30 days at 347:81:-152 in minecraft:overworld (page 1):
[04-07 19:59] Jawor__ replaced SPRUCE_SLAB with SPRUCE_SLAB
[04-07 19:58] Jawor__ created SPRUCE_SLAB
[04-07 19:45] Jawor__ destroyed SPRUCE_SLAB
[03-31 13:42] viv0 created SPRUCE_SLAB
[03-31 13:32] viv0 destroyed STONE
```

- **Sneak + right-click** the same block to go to the next page
- Container blocks also show item take/put history

### Commands

| Command | Description |
|---|---|
| `/lb block` | Inspect the block you're looking at |
| `/lb container` | Inspect the container you're looking at |
| `/lb player <name>` | Show recent actions by a specific player |
| `/lb area [radius]` | Show all changes within radius blocks of you (default: 10) |
| `/lb time <minutes>` | Filter the active query to the last N minutes |
| `/lb page <n>` | Navigate to page N of the last query result |
| `/lb rollback [radius] [minutes]` | **(OP)** Undo block changes in area within time window |
| `/lb redo` | **(OP)** Reverse the last rollback |
| `/lb status` | Show database size and write queue depth |
| `/lb help` | Show this command list |

### Chat Output Colors

| Color | Meaning |
|---|---|
| Green | Player name / created / put |
| Red | Destroyed / taken |
| Yellow | Replaced |
| Dark Red | Exploded |
| Gray | Timestamps and headers |

## Configuration

The config file is generated at `config/logblock-common.toml` on first launch:

```toml
[inspector]
    # Item used to right-click and inspect blocks
    inspectorItem = "minecraft:wooden_pickaxe"

    # Entries shown per page
    maxResultsPerPage = 10

    # Default query window (minutes). 43200 = 30 days
    defaultTimeWindowMinutes = 43200

[database]
    # Path to the SQLite database (relative to config dir, or absolute)
    databasePath = "logblock/logblock.db"

[logging]
    # Toggle logging categories
    logBlocks = true
    logContainers = true
    logExplosions = true
    logSigns = true

    # Maximum radius for /lb rollback
    maxRollbackRadius = 50
```

## Notes

- The rollback system restores blocks to their state before the logged change. It keeps one redo session per player in memory.
- Container logging tracks differences between opening and closing a container, so it may miss rapid automated transactions (hoppers, etc.). Player-initiated opens/closes are fully captured.
- Logs are never automatically deleted. For large servers, periodically pruning old records via SQL is recommended.

## License

MIT
