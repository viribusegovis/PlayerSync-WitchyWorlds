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

      JDBCsetUp.executeUpdate(
         "CREATE TABLE IF NOT EXISTS "
            + dbName
            + ".`player_data` (`uuid` char(36) NOT NULL,`inventory` mediumblob,`armor` blob,`advancements` blob,`enderchest` mediumblob,`effects` blob,`left_hand` blob,`cursors` blob,`xp` int DEFAULT NULL,`food_level` int DEFAULT NULL,`score` int DEFAULT NULL,`health` int DEFAULT NULL,`online` tinyint(1) DEFAULT NULL,`last_server` int DEFAULT NULL,PRIMARY KEY (`uuid`));"
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
         JDBCsetUp.executeUpdate(
            "CREATE TABLE IF NOT EXISTS " + dbName + ".cobblemon(uuid CHAR(36) NOT NULL,inv BLOB,pokedex BLOB,pc BLOB,general BLOB,PRIMARY KEY (uuid))"
         );
      }

      if (ModList.get().isLoaded("sophisticatedbackpacks")) {
         JDBCsetUp.executeUpdate(
            "CREATE TABLE IF NOT EXISTS " + dbName + ".backpack_data (uuid CHAR(36) NOT NULL, backpack_nbt MEDIUMBLOB, PRIMARY KEY (uuid));", 1
         );
         JDBCsetUp.QueryResult backpackColCheck = JDBCsetUp.executeQuery(
            "SELECT COUNT(*) AS colCount FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '"
               + dbName
               + "' AND TABLE_NAME = 'backpack_data' AND COLUMN_NAME = 'uuid';"
         );
         ResultSet rsBackpackCol = backpackColCheck.resultSet();
         if (rsBackpackCol.next() && rsBackpackCol.getInt("colCount") == 0) {
            LOGGER.info("Altering backpack_data table to add missing 'uuid' column.");
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data ADD COLUMN uuid CHAR(36) NOT NULL", 1);
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".backpack_data ADD PRIMARY KEY (uuid)", 1);
         }

         rsBackpackCol.close();
         backpackColCheck.connection().close();
      }

      JDBCsetUp.QueryResult advColCheck = JDBCsetUp.executeQuery(
         "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '"
            + dbName
            + "' AND TABLE_NAME = 'player_data' AND COLUMN_NAME = 'advancements';"
      );
      ResultSet rsAdvCol = advColCheck.resultSet();
      if (rsAdvCol.next()) {
         String dataType = rsAdvCol.getString("DATA_TYPE");
         if (!"mediumblob".equalsIgnoreCase(dataType)) {
            LOGGER.info("Altering player_data table to modify 'advancements' column to MEDIUMBLOB.");
            JDBCsetUp.executeUpdate("ALTER TABLE " + dbName + ".player_data MODIFY COLUMN advancements MEDIUMBLOB", 1);
         }
      }

      rsAdvCol.close();

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
   }
}