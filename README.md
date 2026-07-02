# PlayerSync — WitchyWorlds Network Fork

A NeoForge mod that synchronizes player data across a network of Minecraft servers using a MySQL/MariaDB backend — inventory, armor, ender chest, XP, health/effects, advancements, and more, so players keep their progress when moving between servers.

This is the **WitchyWorlds Network's** fork of PlayerSync, rebuilt around reliability: safer item serialization, connection pooling, and graceful handling of corrupted data instead of crashing the server.

**Targets:** Minecraft 1.21.1, NeoForge 21.1.137

## What this fork changes

The original mod's straightforward sync logic didn't hold up under real network conditions — corrupted NBT or serialization edge cases could crash a server outright. This fork adds:

- **Placeholder-item recovery** — if an item fails to deserialize, it's replaced with a labeled placeholder (the original data is preserved in NBT) instead of dropping data or crashing
- **Per-item error handling for backpacks** — a single corrupted item in a Sophisticated Backpack no longer takes down the whole backpack's contents
- **Connection pooling** (`JDBCsetUp`) with configurable pool size, timeout, and retry attempts
- **Backwards-compatible serialization** — reads both the old legacy format and the current Base64 format, no database migration needed
- **Cross-server chat sync** (`ChatSync`) — optional, with one server acting as chat host
- **Cobblemon support** via mixins (PC boxes, party, player data) — separate from the vanilla-focused feature set of the upstream mod
- **Async save/load** via a dedicated thread pool, with auto-save every 60 seconds for online players

## Mod support

* [Curios API](https://www.curseforge.com/minecraft/mc-mods/curios)
* [Sophisticated Backpacks](https://www.curseforge.com/minecraft/mc-mods/sophisticated-backpacks)
* [Cobblemon](https://www.curseforge.com/minecraft/mc-mods/cobblemon) (PC, party, and player data sync via mixins)

Additional mod support can be added — see `ModsSupport.java` for the extension pattern.

## Configuration

Settings live in the NeoForge common config (`JdbcConfig.java`). Key options:

| Setting | Purpose |
|---|---|
| `host`, `user_name`, `password`, `db_name`, `use_ssl` | Database connection |
| `Server_id` | Unique ID for this server in the network |
| `sync_world`, `sync_advancements` | What gets synced |
| `sync_chat`, `IsChatServer`, `ChatServerIP` | Cross-server chat sync |
| `kick_when_already_online` | Prevent the same player being logged in on two servers at once |
| `use_legacy_serialization` | Readable-but-fragile DB fields, debugging only — **do not enable in production** |
| `item_placeholder_title_override`, `item_placeholder_description_override` | Customize placeholder items for corrupted data |
| `connection_pool_max_size`, `connection_pool_timeout`, `connection_pool_retry_attempts` | Connection pool tuning |
| `debug_mode`, `debug_connection_pool`, `debug_achievements`, `debug_ftb_quests`, `debug_backpacks`, `debug_cobblemon`, `debug_curios` | Per-subsystem debug logging |

## Development setup

### Database (Docker)

`docker-compose.yml` spins up a MariaDB instance plus [Adminer](https://www.adminer.org/) for local testing:

```sh
docker compose up -d    # start
docker compose down     # stop
```

Data persists in a Docker volume across restarts. Adminer is at http://localhost:8080 — login with server `db`, username `playersync`, database `playersync`, password from `docker-compose.yml` (**change it before using anywhere but local dev**).

### Running

Uses Gradle with the standard NeoForge wrapper tasks:

```sh
./gradlew runServer   # dedicated server with the mod loaded, in ./run
./gradlew runClient   # client instance
./gradlew compileJava # compile only, fastest way to check for errors
./gradlew build       # full build (some tests may fail due to Gradle config issues)
```

The database must be running before starting a server instance — tables are created automatically on first startup.

## CI

`.github/workflows/build.yml` builds and validates the mod on every push/PR. It also carries Modrinth/CurseForge publish steps inherited from upstream PlayerSync, gated on tag pushes — these aren't wired up with credentials for this fork and are effectively inert.

## License

[GNU General Public License v3.0](LICENSE)
