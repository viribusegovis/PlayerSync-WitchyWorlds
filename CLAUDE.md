# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PlayerSync is a Minecraft NeoForge mod that synchronizes player data across multiple servers using a MySQL/MariaDB backend. Players can maintain their inventory, equipment, experience, advancements, and more when moving between servers in a network.

**Key Version:** Minecraft 1.21.1, NeoForge 21.1.137

## Development Commands

### Database Setup
```bash
# Start MariaDB database for development
docker compose up -d

# Stop database
docker compose down

# Access Adminer web interface at http://localhost:8080
# Login: username=playersync, database=playersync, password=pleaseChangeThisPassword
```

### Building and Running
```bash
# Compile Java classes only
./gradlew compileJava

# Run dedicated server instance (requires database)
./gradlew runServer

# Run client instance
./gradlew runClient

# Build mod JAR (note: tests may fail due to Gradle configuration issues)
./gradlew build
```

### Development Notes
- Use `--no-daemon` flag if running into memory issues
- Database must be running before starting server instances
- The mod auto-creates database tables on first server startup

## Code Architecture

### Core Components

**Main Mod Class (`PlayerSync.java`)**
- Entry point and server lifecycle management
- Database initialization and table creation
- Mod loading detection for supported extensions (Curios, Sophisticated Backpacks, Cobblemon)

**Configuration (`JdbcConfig.java`)**
- All database connection and sync settings
- `USE_LEGACY_SERIALIZATION` controls backwards compatibility mode
- `KICK_WHEN_ALREADY_ONLINE` prevents multi-server login
- Placeholder item customization settings

**Data Synchronization (`sync/VanillaSync.java`)**
- Core player data sync logic (inventory, XP, health, effects, etc.)
- Item serialization with backwards compatibility (Base64 vs legacy format)
- Graceful error handling with placeholder items for corrupted data
- Auto-save system every 60 seconds for online players

**Mod Extensions (`sync/addons/ModsSupport.java`)**
- Curios API integration for cosmetic slots
- Sophisticated Backpacks integration with individual item error handling
- Cobblemon support via mixins

### Database Schema

**Tables created automatically:**
- `player_data` - Core player information (inventory, armor, XP, etc.)
- `server_info` - Server status and heartbeat tracking  
- `curios` - Curios API slot data (if Curios loaded)
- `backpack_data` - Sophisticated Backpacks contents (if mod loaded)
- `cobblemon` - Cobblemon data (if mod loaded)

### Critical Error Handling

**Item Serialization Robustness:**
- `deserializeAndCreatePlaceholderIfNeeded()` safely handles corrupted item NBT
- Creates paper placeholder items with original data stored in custom NBT
- `getNbtForStorage()` preserves original serialized data when saving placeholders
- Backpack contents get individual item-level protection via `safelyRestoreBackpackContents()`

**Backwards Compatibility:**
- `deserializeString()` handles both Base64 and legacy character-replacement formats
- `serialize()` respects `USE_LEGACY_SERIALIZATION` config for writing
- No database migration required - reads old and new formats seamlessly

### Threading and Performance

- Uses `PSThreadPoolFactory` for async operations
- Player login/logout operations run in thread pool to avoid blocking main thread
- Auto-save system with configurable intervals
- Connection pooling via `JDBCsetUp` utility

### Extension Points

**Adding New Mod Support:**
1. Check `ModList.get().isLoaded("modid")` in appropriate locations
2. Add table creation logic in `PlayerSync.onServerStarting()`
3. Implement save/restore logic in `ModsSupport.java`
4. Follow pattern of graceful error handling for individual items

**Critical Requirements:**
- Always maintain backwards compatibility with existing database data
- Use placeholder items for corrupted data instead of throwing exceptions  
- Preserve `serialize()`/`deserializeString()` format compatibility
- Handle `CommandSyntaxException` gracefully in NBT operations
- Test compilation with `./gradlew compileJava` after changes


# Conventional Commits 1.0.0

## Summary

The Conventional Commits specification is a lightweight convention on top of commit messages.
It provides an easy set of rules for creating an explicit commit history;
which makes it easier to write automated tools on top of.
This convention dovetails with [SemVer](http://semver.org),
by describing the features, fixes, and breaking changes made in commit messages.

The commit message should be structured as follows:

---

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```
---

<br />
The commit contains the following structural elements, to communicate intent to the
consumers of your library:

1. **fix:** a commit of the _type_ `fix` patches a bug in your codebase (this correlates with [`PATCH`](http://semver.org/#summary) in Semantic Versioning).
1. **feat:** a commit of the _type_ `feat` introduces a new feature to the codebase (this correlates with [`MINOR`](http://semver.org/#summary) in Semantic Versioning).
1. **BREAKING CHANGE:** a commit that has a footer `BREAKING CHANGE:`, or appends a `!` after the type/scope, introduces a breaking API change (correlating with [`MAJOR`](http://semver.org/#summary) in Semantic Versioning).
   A BREAKING CHANGE can be part of commits of any _type_.
1. _types_ other than `fix:` and `feat:` are allowed, for example [@commitlint/config-conventional](https://github.com/conventional-changelog/commitlint/tree/master/%40commitlint/config-conventional) (based on the [Angular convention](https://github.com/angular/angular/blob/22b96b9/CONTRIBUTING.md#-commit-message-guidelines)) recommends `build:`, `chore:`,
   `ci:`, `docs:`, `style:`, `refactor:`, `perf:`, `test:`, and others.
1. _footers_ other than `BREAKING CHANGE: <description>` may be provided and follow a convention similar to
   [git trailer format](https://git-scm.com/docs/git-interpret-trailers).

Additional types are not mandated by the Conventional Commits specification, and have no implicit effect in Semantic Versioning (unless they include a BREAKING CHANGE).
<br /><br />
A scope may be provided to a commit's type, to provide additional contextual information and is contained within parenthesis, e.g., `feat(parser): add ability to parse arrays`.

## Examples

### Commit message with description and breaking change footer
```
feat: allow provided config object to extend other configs

BREAKING CHANGE: `extends` key in config file is now used for extending other config files
```

### Commit message with `!` to draw attention to breaking change
```
feat!: send an email to the customer when a product is shipped
```

### Commit message with scope and `!` to draw attention to breaking change
```
feat(api)!: send an email to the customer when a product is shipped
```

### Commit message with both `!` and BREAKING CHANGE footer
```
chore!: drop support for Node 6

BREAKING CHANGE: use JavaScript features not available in Node 6.
```

### Commit message with no body
```
docs: correct spelling of CHANGELOG
```

### Commit message with scope
```
feat(lang): add Polish language
```

### Commit message with multi-paragraph body and multiple footers
```
fix: prevent racing of requests

Introduce a request id and a reference to latest request. Dismiss
incoming responses other than from latest request.

Remove timeouts which were used to mitigate the racing issue but are
obsolete now.

Reviewed-by: Z
Refs: #123
```

## Specification

The key words “MUST”, “MUST NOT”, “REQUIRED”, “SHALL”, “SHALL NOT”, “SHOULD”, “SHOULD NOT”, “RECOMMENDED”, “MAY”, and “OPTIONAL” in this document are to be interpreted as described in [RFC 2119](https://www.ietf.org/rfc/rfc2119.txt).

1. Commits MUST be prefixed with a type, which consists of a noun, `feat`, `fix`, etc., followed
   by the OPTIONAL scope, OPTIONAL `!`, and REQUIRED terminal colon and space.
1. The type `feat` MUST be used when a commit adds a new feature to your application or library.
1. The type `fix` MUST be used when a commit represents a bug fix for your application.
1. A scope MAY be provided after a type. A scope MUST consist of a noun describing a
   section of the codebase surrounded by parenthesis, e.g., `fix(parser):`
1. A description MUST immediately follow the colon and space after the type/scope prefix.
   The description is a short summary of the code changes, e.g., _fix: array parsing issue when multiple spaces were contained in string_.
1. A longer commit body MAY be provided after the short description, providing additional contextual information about the code changes. The body MUST begin one blank line after the description.
1. A commit body is free-form and MAY consist of any number of newline separated paragraphs.
1. One or more footers MAY be provided one blank line after the body. Each footer MUST consist of
   a word token, followed by either a `:<space>` or `<space>#` separator, followed by a string value (this is inspired by the
   [git trailer convention](https://git-scm.com/docs/git-interpret-trailers)).
1. A footer's token MUST use `-` in place of whitespace characters, e.g., `Acked-by` (this helps differentiate
   the footer section from a multi-paragraph body). An exception is made for `BREAKING CHANGE`, which MAY also be used as a token.
1. A footer's value MAY contain spaces and newlines, and parsing MUST terminate when the next valid footer
   token/separator pair is observed.
1. Breaking changes MUST be indicated in the type/scope prefix of a commit, or as an entry in the
   footer.
1. If included as a footer, a breaking change MUST consist of the uppercase text BREAKING CHANGE, followed by a colon, space, and description, e.g.,
   _BREAKING CHANGE: environment variables now take precedence over config files_.
1. If included in the type/scope prefix, breaking changes MUST be indicated by a
   `!` immediately before the `:`. If `!` is used, `BREAKING CHANGE:` MAY be omitted from the footer section,
   and the commit description SHALL be used to describe the breaking change.
1. Types other than `feat` and `fix` MAY be used in your commit messages, e.g., _docs: update ref docs._
1. The units of information that make up Conventional Commits MUST NOT be treated as case sensitive by implementors, with the exception of BREAKING CHANGE which MUST be uppercase.
1. BREAKING-CHANGE MUST be synonymous with BREAKING CHANGE, when used as a token in a footer.
