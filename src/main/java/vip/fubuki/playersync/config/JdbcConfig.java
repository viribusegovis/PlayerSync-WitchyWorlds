package vip.fubuki.playersync.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

public class JdbcConfig {
   public static ModConfigSpec COMMON_CONFIG;
   public static ConfigValue<String> HOST;
   public static IntValue PORT;
   public static ConfigValue<String> USERNAME;
   public static ConfigValue<String> PASSWORD;
   public static ConfigValue<String> DATABASE_NAME;
   public static ConfigValue<List<String>> SYNC_WORLD;
   public static BooleanValue SYNC_ADVANCEMENTS;
   public static BooleanValue USE_SSL;
   public static BooleanValue SYNC_CHAT;
   public static BooleanValue IS_CHAT_SERVER;
   public static BooleanValue KICK_WHEN_ALREADY_ONLINE;
   public static final ConfigValue<String> ITEM_PLACEHOLDER_TITLE_OVERRIDE;
   public static final ConfigValue<String> ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE;
   public static ConfigValue<String> CHAT_SERVER_IP;
   public static IntValue CHAT_SERVER_PORT;
   public static BooleanValue USE_LEGACY_SERIALIZATION;
   public static ConfigValue<Integer> SERVER_ID;
   
   // Database Schema Configuration
   public static ConfigValue<String> PLAYER_INVENTORY_TYPE;
   public static ConfigValue<String> PLAYER_ADVANCEMENTS_TYPE;
   public static ConfigValue<String> COBBLEMON_PC_TYPE;
   public static ConfigValue<String> COBBLEMON_POKEDEX_TYPE;
   public static ConfigValue<String> BACKPACK_NBT_TYPE;
   
   // Debug Configuration
   public static BooleanValue DEBUG_MODE;
   public static BooleanValue DEBUG_CONNECTION_POOL;
   public static BooleanValue DEBUG_ACHIEVEMENTS;
   public static BooleanValue DEBUG_FTB_QUESTS;
   public static BooleanValue DEBUG_BACKPACKS;
   public static BooleanValue DEBUG_COBBLEMON;
   public static BooleanValue DEBUG_CURIOS;
   public static BooleanValue SAVE_FAILED_ITEMS;
   
   // FTB Quests Configuration
   public static ConfigValue<String> FTB_QUESTS_DATA_TYPE;
   
   // Connection Pool Configuration
   public static IntValue CONNECTION_POOL_MAX_SIZE;
   public static IntValue CONNECTION_POOL_TIMEOUT;
   public static IntValue CONNECTION_POOL_RETRY_ATTEMPTS;

   static {
      Builder COMMON_BUILDER = new Builder();
      COMMON_BUILDER.comment("General settings").push("general");
      HOST = COMMON_BUILDER.comment("The host of the database").define("host", "localhost");
      PORT = COMMON_BUILDER.comment("database port").defineInRange("db_port", 3306, 0, 65535);
      USE_SSL = COMMON_BUILDER.comment("whether use SSL").define("use_ssl", false);
      USERNAME = COMMON_BUILDER.comment("username").define("user_name", "playersync");
      PASSWORD = COMMON_BUILDER.comment("password").define("password", "pleaseChangeThisPassword");
      DATABASE_NAME = COMMON_BUILDER.comment("database name").define("db_name", "playersync");
      SERVER_ID = COMMON_BUILDER.comment("the server id should be unique").define("Server_id", new Random().nextInt(1, 2147483646));
      SYNC_WORLD = COMMON_BUILDER.comment("The worlds that will be synchronized. If running on a server, leave array empty.")
         .define("sync_world", new ArrayList());
      SYNC_ADVANCEMENTS = COMMON_BUILDER.comment("Whether to sync advancements between servers").define("sync_advancements", true);
      SYNC_CHAT = COMMON_BUILDER.comment("Whether synchronize chat").define("sync_chat", false);
      IS_CHAT_SERVER = COMMON_BUILDER.comment("Whether recieve messages from other servers as host").define("IsChatServer", false);
      KICK_WHEN_ALREADY_ONLINE = COMMON_BUILDER.comment("Whether to kick player when already online on another server")
         .define("kick_when_already_online", true);
      CHAT_SERVER_IP = COMMON_BUILDER.define("ChatServerIP", "127.0.0.1");
      CHAT_SERVER_PORT = COMMON_BUILDER.defineInRange("ChatServerPort", 7900, 0, 65535);
      USE_LEGACY_SERIALIZATION = COMMON_BUILDER.comment(
            new String[]{
               "Use the old (pre-Base64) serialization format for writing data to the database.",
               "Set to true ONLY if you have older mod versions reading the same database.",
               "This only affects writing data, the mod can read both Base64 and pre-Base64 serialization.",
               "New installations should leave this as 'false'."
            }
         )
         .define("use_legacy_serialization", false);
      ITEM_PLACEHOLDER_TITLE_OVERRIDE = COMMON_BUILDER.comment("Override the title of placeholder items which are unavailable on the current server.")
         .define("item_placeholder_title_override", "");
      ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE = COMMON_BUILDER.comment(
            "Override the description of placeholder items which are unavailable on the current server."
         )
         .define("item_placeholder_description_override", "");

      COMMON_BUILDER.pop();

      // Database Schema Configuration
      COMMON_BUILDER.comment("Database column types for large data storage").push("database_schema");
      PLAYER_INVENTORY_TYPE = COMMON_BUILDER.comment(
            new String[]{
               "Database column type for player inventory data",
               "Options: BLOB (64KB), MEDIUMBLOB (16MB), LONGBLOB (4GB)",
               "LONGBLOB recommended for large modded inventories"
            }
         ).define("player_inventory_type", "LONGBLOB");
      
      PLAYER_ADVANCEMENTS_TYPE = COMMON_BUILDER.comment(
            new String[]{
               "Database column type for player advancements data",
               "Options: BLOB (64KB), MEDIUMBLOB (16MB), LONGBLOB (4GB)", 
               "LONGBLOB recommended for extensive modded progression"
            }
         ).define("player_advancements_type", "LONGBLOB");
      
      COBBLEMON_PC_TYPE = COMMON_BUILDER.comment(
            new String[]{
               "Database column type for Cobblemon PC storage",
               "Options: BLOB (64KB), MEDIUMBLOB (16MB), LONGBLOB (4GB)",
               "LONGBLOB recommended for large Pokemon collections"
            }
         ).define("cobblemon_pc_type", "LONGBLOB");
      
      COBBLEMON_POKEDEX_TYPE = COMMON_BUILDER.comment(
            new String[]{
               "Database column type for Cobblemon Pokedex data", 
               "Options: BLOB (64KB), MEDIUMBLOB (16MB), LONGBLOB (4GB)",
               "MEDIUMBLOB usually sufficient for Pokedex data"
            }
         ).define("cobblemon_pokedex_type", "MEDIUMBLOB");
      
      BACKPACK_NBT_TYPE = COMMON_BUILDER.comment(
            new String[]{
               "Database column type for Sophisticated Backpacks NBT data",
               "Options: BLOB (64KB), MEDIUMBLOB (16MB), LONGBLOB (4GB)",
               "LONGBLOB recommended for nested backpack storage"
            }
         ).define("backpack_nbt_type", "LONGBLOB");
      
      FTB_QUESTS_DATA_TYPE = COMMON_BUILDER.comment(
            new String[]{
               "Database column type for FTB Quests data storage",
               "Options: BLOB (64KB), MEDIUMBLOB (16MB), LONGBLOB (4GB)",
               "LONGBLOB recommended for large modpacks with extensive quest books",
               "Change requires server restart and may need database migration"
            }
         ).define("quest_data_type", "LONGBLOB");

      COMMON_BUILDER.pop();

      // Debug Configuration  
      COMMON_BUILDER.comment("Debug and development settings").push("debug");
      DEBUG_MODE = COMMON_BUILDER.comment(
            new String[]{
               "Global debug mode - enables ALL debug logging when true",
               "Shows detailed operations across all PlayerSync features",
               "WARNING: May generate large log files - use only for troubleshooting",
               "Individual debug flags below can be enabled independently of this setting"
            }
         ).define("debug_mode", false);
      
      COMMON_BUILDER.comment("Granular debug settings - each can be enabled independently").push("granular");
      DEBUG_CONNECTION_POOL = COMMON_BUILDER.comment(
            new String[]{
               "Enable debug logging for database connection pool operations",
               "Shows connection requests, pool status, and connection lifecycle",
               "Enabled when: debug_mode=true OR debug_connection_pool=true"
            }
         ).define("debug_connection_pool", false);
      DEBUG_ACHIEVEMENTS = COMMON_BUILDER.comment(
            new String[]{
               "Enable debug logging for advancement/achievement synchronization",
               "Shows detailed advancement processing and JSON parsing operations",
               "Enabled when: debug_mode=true OR debug_achievements=true"
            }
         ).define("debug_achievements", false);
      DEBUG_FTB_QUESTS = COMMON_BUILDER.comment(
            new String[]{
               "Enable debug logging for FTB Quests synchronization",
               "Shows detailed quest data extraction, restoration, and API discovery",
               "Enabled when: debug_mode=true OR debug_ftb_quests=true"
            }
         ).define("debug_ftb_quests", false);
      DEBUG_BACKPACKS = COMMON_BUILDER.comment(
            new String[]{
               "Enable debug logging for Sophisticated Backpacks synchronization",
               "Shows detailed backpack NBT processing and item recovery operations",
               "Enabled when: debug_mode=true OR debug_backpacks=true"
            }
         ).define("debug_backpacks", false);
      DEBUG_COBBLEMON = COMMON_BUILDER.comment(
            new String[]{
               "Enable debug logging for Cobblemon synchronization",
               "Shows detailed Pokemon PC and Pokedex data processing",
               "Enabled when: debug_mode=true OR debug_cobblemon=true"
            }
         ).define("debug_cobblemon", false);
      DEBUG_CURIOS = COMMON_BUILDER.comment(
            new String[]{
               "Enable debug logging for Curios API synchronization",
               "Shows detailed cosmetic slot processing and NBT operations",
               "Enabled when: debug_mode=true OR debug_curios=true"
            }
         ).define("debug_curios", false);
      SAVE_FAILED_ITEMS = COMMON_BUILDER.comment(
            new String[]{
               "Save failed item data to debug/failed_items/ folder for analysis",
               "Creates individual files with corrupted NBT data and metadata",
               "Useful for identifying specific items causing corruption issues",
               "Files include timestamp, player UUID, and original serialized data"
            }
         ).define("save_failed_items", false);
      COMMON_BUILDER.pop();

      // Connection Pool Configuration
      COMMON_BUILDER.comment("Database connection pool settings").push("connection_pool");
      CONNECTION_POOL_MAX_SIZE = COMMON_BUILDER.comment(
            new String[]{
               "Maximum number of database connections in the pool",
               "Higher values allow more concurrent operations but use more memory",
               "10 connections is usually sufficient for most servers"
            }
         ).defineInRange("max_pool_size", 10, 1, 50);
      
      CONNECTION_POOL_TIMEOUT = COMMON_BUILDER.comment(
            new String[]{
               "Time to wait (in milliseconds) when connection pool is exhausted",
               "100ms provides good balance between responsiveness and server load",
               "Lower values = more responsive but higher CPU usage"
            }
         ).defineInRange("pool_timeout_ms", 100, 50, 5000);
      
      CONNECTION_POOL_RETRY_ATTEMPTS = COMMON_BUILDER.comment(
            new String[]{
               "Number of retry attempts for failed database connections",
               "Higher values improve reliability but may cause delays during outages",
               "3 attempts is recommended for most setups"
            }
         ).defineInRange("retry_attempts", 3, 1, 10);
      COMMON_BUILDER.pop();

      COMMON_CONFIG = COMMON_BUILDER.build();
   }
}