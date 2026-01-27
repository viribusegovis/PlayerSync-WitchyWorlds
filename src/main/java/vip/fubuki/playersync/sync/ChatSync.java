package vip.fubuki.playersync.sync;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.chat.ChatSyncClient;
import vip.fubuki.playersync.sync.chat.ChatSyncServer;

import java.io.IOException;

public class ChatSync {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static ChatSyncServer chatSyncServer;
    private static ChatSyncClient chatSyncClient;

    public static void register(){
        LOGGER.info("=== CHAT SYNC INITIALIZATION ===");
        
        // Validate configuration first
        if (!validateChatConfig()) {
            LOGGER.error("Chat sync configuration is invalid. Chat sync will not start.");
            return;
        }
        
        LOGGER.info("Chat sync configuration validated successfully");
        LOGGER.info("IS_CHAT_SERVER: {}", JdbcConfig.IS_CHAT_SERVER.get());
        LOGGER.info("CHAT_SERVER_IP: {}", JdbcConfig.CHAT_SERVER_IP.get());
        LOGGER.info("CHAT_SERVER_PORT: {}", JdbcConfig.CHAT_SERVER_PORT.get());
        
        if(JdbcConfig.IS_CHAT_SERVER.get()) {
            LOGGER.info("Starting chat server thread on port {}", JdbcConfig.CHAT_SERVER_PORT.get());
            Thread serverThread = new Thread(()->{
                try {
                    LOGGER.info("[SERVER THREAD] Chat server thread started");
                    chatSyncServer = new ChatSyncServer();
                    chatSyncServer.run();
                } catch (IOException e) {
                    LOGGER.error("[SERVER THREAD] Chat server failed to start", e);
                } catch (Exception e) {
                    LOGGER.error("[SERVER THREAD] Unexpected error in chat server", e);
                } finally {
                    LOGGER.info("[SERVER THREAD] Chat server thread ended");
                }
            }, "ChatSync-Server");
            
            serverThread.setDaemon(false);
            serverThread.start();
            LOGGER.info("Chat server thread launched");
        } else {
            LOGGER.info("This server is not configured as a chat server");
        }

        // Always start client (connects to chat server for syncing)
        LOGGER.info("Starting chat client thread to connect to {}:{}", 
            JdbcConfig.CHAT_SERVER_IP.get(), JdbcConfig.CHAT_SERVER_PORT.get());
            
        Thread clientThread = new Thread(()->{
            try {
                LOGGER.info("[CLIENT THREAD] Chat client thread started, waiting 3 seconds before connecting");
                Thread.sleep(3000); // Give server time to start if both are on same machine
                
                LOGGER.info("[CLIENT THREAD] Attempting to connect to chat server");
                chatSyncClient = new ChatSyncClient();
                chatSyncClient.run();
            } catch (InterruptedException e) {
                LOGGER.warn("[CLIENT THREAD] Chat client thread was interrupted during startup delay");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[CLIENT THREAD] Unexpected error in chat client", e);
            } finally {
                LOGGER.info("[CLIENT THREAD] Chat client thread ended");
            }
        }, "ChatSync-Client");
        
        clientThread.setDaemon(false);
        clientThread.start();
        LOGGER.info("Chat client thread launched");
        
        // Register event handlers
        NeoForge.EVENT_BUS.register(ChatSyncClient.class);
        LOGGER.info("Chat sync event handlers registered");
        LOGGER.info("=== CHAT SYNC INITIALIZATION COMPLETE ===");
    }
    
    private static boolean validateChatConfig() {
        String serverIP = JdbcConfig.CHAT_SERVER_IP.get();
        int serverPort = JdbcConfig.CHAT_SERVER_PORT.get();
        
        LOGGER.info("Validating chat sync configuration:");
        LOGGER.info("  Server IP: '{}'", serverIP);
        LOGGER.info("  Server Port: {}", serverPort);
        LOGGER.info("  Is Chat Server: {}", JdbcConfig.IS_CHAT_SERVER.get());
        
        if (serverIP == null || serverIP.trim().isEmpty()) {
            LOGGER.error("Chat server IP is null or empty");
            return false;
        }
        
        if (serverPort <= 0 || serverPort > 65535) {
            LOGGER.error("Chat server port {} is invalid (must be 1-65535)", serverPort);
            return false;
        }
        
        // Basic IP validation
        if (!serverIP.equals("localhost") && !serverIP.equals("127.0.0.1")) {
            String[] parts = serverIP.split("\\.");
            if (parts.length == 4) {
                try {
                    for (String part : parts) {
                        int octet = Integer.parseInt(part);
                        if (octet < 0 || octet > 255) {
                            LOGGER.error("Invalid IP address format: {}", serverIP);
                            return false;
                        }
                    }
                } catch (NumberFormatException e) {
                    LOGGER.error("Invalid IP address format: {}", serverIP);
                    return false;
                }
            } else if (!serverIP.matches("^[a-zA-Z0-9.-]+$")) {
                LOGGER.error("Invalid server address format: {}", serverIP);
                return false;
            }
        }
        
        LOGGER.info("Configuration validation passed");
        return true;
    }

    public static void shutdown() {
        if (chatSyncServer != null) {
            chatSyncServer.shutdown();
        }
        if (chatSyncClient != null) {
            chatSyncClient.shutdown();
        }
    }
}
