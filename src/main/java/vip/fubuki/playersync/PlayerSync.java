package vip.fubuki.playersync;

import com.mojang.logging.LogUtils;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.ChatSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;

@Mod("playersync")
public class PlayerSync {
   public static final String MODID = "playersync";
   public static final Logger LOGGER = LogUtils.getLogger();

   public PlayerSync(IEventBus modEventBus, ModContainer modContainer) {
      modEventBus.addListener(this::commonSetup);
      NeoForge.EVENT_BUS.register(this);
      modContainer.registerConfig(Type.COMMON, JdbcConfig.COMMON_CONFIG);
   }

   private void commonSetup(FMLCommonSetupEvent event) {
      VanillaSync.register();
      event.enqueueWork(() -> {
         if ((Boolean)JdbcConfig.SYNC_CHAT.get()) {
            LOGGER.info("Chat sync enabled.");
            ChatSync.register();
         }
      });
   }

   @SubscribeEvent
   public void onServerStarting(ServerStartingEvent event) throws SQLException {
      String dbName = (String)JdbcConfig.DATABASE_NAME.get();
      JDBCsetUp.executeUpdate("CREATE DATABASE IF NOT EXISTS " + dbName, 1);

      try (
         Connection conn = JDBCsetUp.getConnection(false);
         Statement st = conn.createStatement();
      ) {
         st.execute("USE " + dbName);
      } catch (SQLException var16) {
         LOGGER.error("Error selecting database " + dbName, var16);
         throw var16;
      }

      // Use configurable column types for player data
      String inventoryType = (String)JdbcConfig.PLAYER_INVENTORY_TYPE.get();
      String advancementsType = (String)JdbcConfig.PLAYER_ADVANCEMENTS_TYPE.get();
      
      if (JdbcConfig.DEBUG_MODE.get()) {
         LOGGER.info("Creating player_data table with inventory: {}, advancements: {}", inventoryType, advancementsType);
      }
      
      JDBCsetUp.executeUpdate(
         "CREATE TABLE IF NOT EXISTS "
            + dbName
            + ".`player_data` (`uuid` char(36) NOT NULL,`inventory` " + inventoryType + ",`armor` blob,`advancements` " + advancementsType + ",`enderchest` mediumblob,`effects` blob,`left_hand` blob,`cursors` blob,`xp` int DEFAULT NULL,`food_level` int DEFAULT NULL,`score` int DEFAULT NULL,`health` int DEFAULT NULL,`online` tinyint(1) DEFAULT NULL,`last_server` int DEFAULT NULL,PRIMARY KEY (`uuid`));"
      );
      JDBCsetUp.QueryResult queryResult = JDBCsetUp.executeQuery(
         "SELECT COUNT(*) AS column_count FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + dbName + "' AND TABLE_NAME = 'player_data';"
      );
      ResultSet resultSet = queryResult.resultSet();
      int columnCount = 0;
      if (resultSet.next()) {
         columnCount = resultSet.getInt("column_count");
      }

      if (columnCount < 14) {
         JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".player_data ADD COLUMN left_hand blob, ADD COLUMN cursors blob;");
      }

      JDBCsetUp.executeUpdate(
         "CREATE TABLE IF NOT EXISTS "
            + dbName
            + ".server_info (`id` INT NOT NULL,`enable` boolean NOT NULL,`last_update` BIGINT NOT NULL,PRIMARY KEY (`id`));"
      );
      long current = System.currentTimeMillis();
      JDBCsetUp.executeUpdate(
         "INSERT INTO "
            + dbName
            + ".server_info(id,enable,last_update) VALUES("
            + JdbcConfig.SERVER_ID.get()
            + ",true,"
            + current
            + ") ON DUPLICATE KEY UPDATE id= "
            + JdbcConfig.SERVER_ID.get()
            + ",enable = 1,last_update="
            + current
            + ";"
      );
      JDBCsetUp.executeUpdate(
         "UPDATE " + dbName + ".server_info SET last_update=" + System.currentTimeMillis() + " WHERE id='" + JdbcConfig.SERVER_ID.get() + "'"
      );
      if (ModList.get().isLoaded("curios")) {
         JDBCsetUp.executeUpdate("CREATE TABLE IF NOT EXISTS " + dbName + ".curios (uuid CHAR(36) NOT NULL, curios_item BLOB, PRIMARY KEY (uuid))");
      }

      if (ModList.get().isLoaded("cobblemon")) {
         // Use configurable column types for Cobblemon data
         String pcType = (String)JdbcConfig.COBBLEMON_PC_TYPE.get();
         String pokedexType = (String)JdbcConfig.COBBLEMON_POKEDEX_TYPE.get();
         
         if (JdbcConfig.DEBUG_MODE.get()) {
            LOGGER.info("Creating Cobblemon table with pc: {}, pokedex: {}", pcType, pokedexType);
         }
         
         JDBCsetUp.executeUpdate(
            "CREATE TABLE IF NOT EXISTS " + dbName + ".cobblemon(uuid CHAR(36) NOT NULL,inv BLOB,pokedex " + pokedexType + ",pc " + pcType + ",general BLOB,PRIMARY KEY (uuid))"
         );
         
         // Upgrade existing Cobblemon columns for backward compatibility
         JDBCsetUp.QueryResult cobblemonCheck = JDBCsetUp.executeQuery(
            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = '" + dbName + "' AND TABLE_NAME = 'cobblemon'"
         );
         ResultSet rsCobblemon = cobblemonCheck.resultSet();
         boolean needsPcUpgrade = false;
         boolean needsPokedexUpgrade = false;
         
         while (rsCobblemon.next()) {
            String columnName = rsCobblemon.getString("COLUMN_NAME");
            String dataType = rsCobblemon.getString("DATA_TYPE");
            
            if ("pc".equals(columnName) && !pcType.equalsIgnoreCase(dataType)) {
               needsPcUpgrade = true;
            }
            if ("pokedex".equals(columnName) && !pokedexType.equalsIgnoreCase(dataType)) {
               needsPokedexUpgrade = true;
            }
         }
         
         if (needsPcUpgrade) {
            LOGGER.info("Upgrading Cobblemon PC column to {} for large Pokemon collections.", pcType);
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".cobblemon MODIFY COLUMN pc " + pcType, 1);
         }
         
         if (needsPokedexUpgrade) {
            LOGGER.info("Upgrading Cobblemon Pokedex column to {} for complete Pokemon data.", pokedexType);
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".cobblemon MODIFY COLUMN pokedex " + pokedexType, 1);
         }
         
         rsCobblemon.close();
         cobblemonCheck.connection().close();
      }

      if (ModList.get().isLoaded("sophisticatedbackpacks")) {
         // Use configurable column type for backpack data
         String backpackType = (String)JdbcConfig.BACKPACK_NBT_TYPE.get();
         
         if (JdbcConfig.DEBUG_MODE.get()) {
            LOGGER.info("Creating Sophisticated Backpacks table with nbt type: {}", backpackType);
         }
         
         JDBCsetUp.executeUpdate(
            "CREATE TABLE IF NOT EXISTS " + dbName + ".backpack_data (uuid CHAR(36) NOT NULL, backpack_nbt " + backpackType + ", PRIMARY KEY (uuid));", 1
         );
         
         // Check for existing columns and upgrade both missing uuid and small nbt columns
         JDBCsetUp.QueryResult backpackCheck = JDBCsetUp.executeQuery(
            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = '" + dbName + "' AND TABLE_NAME = 'backpack_data'"
         );
         ResultSet rsBackpack = backpackCheck.resultSet();
         boolean hasUuid = false;
         boolean needsNbtUpgrade = false;
         
         while (rsBackpack.next()) {
            String columnName = rsBackpack.getString("COLUMN_NAME");
            String dataType = rsBackpack.getString("DATA_TYPE");
            
            if ("uuid".equals(columnName)) {
               hasUuid = true;
            }
            if ("backpack_nbt".equals(columnName) && !backpackType.equalsIgnoreCase(dataType)) {
               needsNbtUpgrade = true;
            }
         }
         
         if (!hasUuid) {
            LOGGER.info("Adding missing 'uuid' column to backpack_data table.");
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data ADD COLUMN uuid CHAR(36) NOT NULL", 1);
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data ADD PRIMARY KEY (uuid)", 1);
         }
         
         if (needsNbtUpgrade) {
            LOGGER.info("Upgrading backpack_data nbt column to {} for nested backpack storage.", backpackType);
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data MODIFY COLUMN backpack_nbt " + backpackType, 1);
         }

         rsBackpack.close();
         backpackCheck.connection().close();
      }

      // Upgrade player_data columns for scale and backward compatibility
      JDBCsetUp.QueryResult playerDataCheck = JDBCsetUp.executeQuery(
         "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
         "WHERE TABLE_SCHEMA = '" + dbName + "' AND TABLE_NAME = 'player_data' " +
         "AND COLUMN_NAME IN ('advancements', 'inventory')"
      );
      ResultSet rsPlayerData = playerDataCheck.resultSet();
      boolean needsAdvancementsUpgrade = false;
      boolean needsInventoryUpgrade = false;
      
      while (rsPlayerData.next()) {
         String columnName = rsPlayerData.getString("COLUMN_NAME");
         String dataType = rsPlayerData.getString("DATA_TYPE");
         
         if ("advancements".equals(columnName) && !advancementsType.equalsIgnoreCase(dataType)) {
            needsAdvancementsUpgrade = true;
         }
         if ("inventory".equals(columnName) && !inventoryType.equalsIgnoreCase(dataType)) {
            needsInventoryUpgrade = true;
         }
      }
      
      if (needsAdvancementsUpgrade) {
         LOGGER.info("Upgrading player_data advancements column to {} for extensive modded progression.", advancementsType);
         JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".player_data MODIFY COLUMN advancements " + advancementsType, 1);
      }
      
      if (needsInventoryUpgrade) {
         LOGGER.info("Upgrading player_data inventory column to {} for large modded inventories.", inventoryType);
         JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".player_data MODIFY COLUMN inventory " + inventoryType, 1);
      }

      rsPlayerData.close();
      playerDataCheck.connection().close();

      try {
         JDBCsetUp.executeUpdate("UPDATE player_data SET online=0 WHERE last_server=" + JdbcConfig.SERVER_ID.get() + " AND online=1 LIMIT 1000");
      } catch (Exception var13) {
         LOGGER.error("An exception occurred while trying change wrong player-status\n" + var13.getMessage());
      }

      LOGGER.info("PlayerSync is ready!");
   }

   @SubscribeEvent
   public void onServerStopping(ServerStoppingEvent event) {
      ChatSync.shutdown();
      VanillaSync.shutdown();
   }
}