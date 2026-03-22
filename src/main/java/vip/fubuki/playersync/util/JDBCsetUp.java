package vip.fubuki.playersync.util;

import com.mojang.logging.LogUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;

public class JDBCsetUp {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ConcurrentLinkedQueue<Connection> connectionPool = new ConcurrentLinkedQueue<>();
   private static final AtomicInteger activeConnections = new AtomicInteger(0);

   public static Connection getPooledConnection(boolean selectDatabase) throws SQLException {
      // Compressed connection pool logging
      if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_CONNECTION_POOL.get()) {
         LOGGER.info("[POOL] REQ sel={} pool={} active={}/{}",
            selectDatabase, connectionPool.size(), activeConnections.get(), JdbcConfig.CONNECTION_POOL_MAX_SIZE.get());
      }

      int maxPoolSize = JdbcConfig.CONNECTION_POOL_MAX_SIZE.get();
      int poolTimeout = JdbcConfig.CONNECTION_POOL_TIMEOUT.get();

      Connection conn = connectionPool.poll();
      if (conn == null || conn.isClosed()) {
         if (activeConnections.get() >= maxPoolSize) {
            LOGGER.warn("Connection pool exhausted (Active: {}/{}), waiting {}ms for available connection...",
               activeConnections.get(), maxPoolSize, poolTimeout);

            try {
               Thread.sleep(poolTimeout);
               conn = connectionPool.poll();

               if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_CONNECTION_POOL.get()) {
                  LOGGER.info("[POOL] WAIT {}ms pool={} active={}", poolTimeout, connectionPool.size(), activeConnections.get());
               }
            } catch (InterruptedException var3) {
               Thread.currentThread().interrupt();
            }
         }

         if (conn == null || conn.isClosed()) {
            conn = createNewConnection(selectDatabase);
            activeConnections.incrementAndGet();
            LOGGER.debug("Created new pooled connection. Active: {}", activeConnections.get());

            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_CONNECTION_POOL.get()) {
               LOGGER.info("[POOL] NEW active={}", activeConnections.get());
            }
         }
      } else if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_CONNECTION_POOL.get()) {
         LOGGER.info("[POOL] REUSE remaining={}", connectionPool.size());
      }

      return conn;
   }

   public static void returnConnection(Connection conn) {
      if (conn != null) {
         int maxPoolSize = JdbcConfig.CONNECTION_POOL_MAX_SIZE.get();
         try {
            if (!conn.isClosed() && connectionPool.size() < maxPoolSize) {
               connectionPool.offer(conn);

               if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_CONNECTION_POOL.get()) {
                  LOGGER.info("[POOL] RET pool={} active={}", connectionPool.size(), activeConnections.get());
               }
            } else {
               conn.close();
               activeConnections.decrementAndGet();

               if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_CONNECTION_POOL.get()) {
                  LOGGER.info("[POOL] CLOSE active={}", activeConnections.get());
               }
            }
         } catch (SQLException var2) {
            LOGGER.warn("Error returning connection to pool", var2);
            activeConnections.decrementAndGet();
         }
      }
   }

   private static Connection createNewConnection(boolean selectDatabase) throws SQLException {
      String dbName = (String)JdbcConfig.DATABASE_NAME.get();
      String url = "jdbc:mysql://" + (String)JdbcConfig.HOST.get() + ":" + JdbcConfig.PORT.get();
      if (selectDatabase && !dbName.isEmpty()) {
         url = url + "/" + dbName;
      }

      url = url + "?useUnicode=true&characterEncoding=utf-8&useSSL=" + JdbcConfig.USE_SSL.get() + "&serverTimezone=UTC&allowPublicKeyRetrieval=true";
      Connection conn = DriverManager.getConnection(url, (String)JdbcConfig.USERNAME.get(), (String)JdbcConfig.PASSWORD.get());
      if (selectDatabase && !dbName.isEmpty()) {
         try (Statement st = conn.createStatement()) {
            st.execute("USE " + dbName);
         }
      }

      return conn;
   }

   public static Connection getConnection(boolean selectDatabase) throws SQLException {
      return getPooledConnection(selectDatabase);
   }

   public static Connection getConnection() throws SQLException {
      return getConnection(true);
   }

   public static QueryResult executeQuery(String sqlFormatString, Object... args) throws SQLException {
      String sql = sqlFormatString;
      LOGGER.trace(sql);

      // Debug mode logging
      if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
         LOGGER.info("[SQL] QUERY: {}", sql);
      }

      Connection connection = getConnection();
      PreparedStatement queryStatement = connection.prepareStatement(sql);
      ResultSet resultSet = queryStatement.executeQuery();
      return new QueryResult(connection, queryStatement, resultSet);
   }

   private static void executeUpdate(boolean selectDatabase, String sqlFormatString, Object... args) throws SQLException {
      String sql = sqlFormatString;
      LOGGER.trace(sql);

      // Debug mode logging
      if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
         LOGGER.info("[SQL] UPDATE sel={}: {}", selectDatabase, sql);
      }

      Connection connection = null;

      try {
         connection = getConnection(selectDatabase);

         try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            updateStatement.executeUpdate();
         }
      } finally {
         returnConnection(connection);
      }
   }

   public static void executeUpdate(String sqlFormatString, Object... args) throws SQLException {
      executeUpdate(true, sqlFormatString, args);
   }

   public static void executeUpdate(String sql, int dummy) throws SQLException {
      LOGGER.trace(sql);
      Connection connection = null;

      try {
         connection = getConnection(false);

         try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            updateStatement.executeUpdate();
         }
      } finally {
         returnConnection(connection);
      }
   }

   public static void update(String sql, String... argument) throws SQLException {
      LOGGER.trace(sql);
      Connection connection = null;

      try {
         connection = getConnection();

         try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            for (int i = 0; i < argument.length; i++) {
               updateStatement.setString(i + 1, argument[i]);
            }

            updateStatement.executeUpdate();
         }
      } finally {
         returnConnection(connection);
      }
   }

   public static void executePreparedUpdate(String sql, Object... parameters) throws SQLException {
      LOGGER.trace(sql);
      Connection connection = null;

      try {
         connection = getConnection();

         try (PreparedStatement updateStatement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
               Object param = parameters[i];
               if (param instanceof String) {
                  updateStatement.setString(i + 1, (String) param);
               } else if (param instanceof byte[]) {
                  updateStatement.setBytes(i + 1, (byte[]) param);
               } else if (param instanceof Integer) {
                  updateStatement.setInt(i + 1, (Integer) param);
               } else if (param instanceof Long) {
                  updateStatement.setLong(i + 1, (Long) param);
               } else if (param instanceof Boolean) {
                  updateStatement.setBoolean(i + 1, (Boolean) param);
               } else if (param == null) {
                  updateStatement.setNull(i + 1, java.sql.Types.NULL);
               } else {
                  updateStatement.setObject(i + 1, param);
               }
            }

            updateStatement.executeUpdate();
         }
      } finally {
         returnConnection(connection);
      }
   }

   public record QueryResult(Connection connection, PreparedStatement preparedStatement, ResultSet resultSet) implements AutoCloseable {
      @Override
      public void close() {
         if (this.resultSet != null) {
            try {
               this.resultSet.close();
            } catch (SQLException var3) {
               LOGGER.error("Error closing ResultSet", var3);
            }
         }

         if (this.preparedStatement != null) {
            try {
               this.preparedStatement.close();
            } catch (SQLException var2) {
               LOGGER.error("Error closing PreparedStatement", var2);
            }
         }

         if (this.connection != null) {
            returnConnection(this.connection);
         }
      }
   }
}