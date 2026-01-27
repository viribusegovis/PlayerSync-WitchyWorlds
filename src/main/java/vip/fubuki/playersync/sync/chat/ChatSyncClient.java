package vip.fubuki.playersync.sync.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.*;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;

public class ChatSyncClient {
    static PlayerList playerList;
    static Socket clientSocket;
    static PrintWriter out;

    private static volatile boolean running = true;
    private static final int RECONNECT_DELAY = 5000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    public void run() {
        PlayerSync.LOGGER.info("=== CHAT CLIENT STARTUP ===");
        int reconnectAttempts = 0;

        while (running && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            try {
                PlayerSync.LOGGER.info("[ATTEMPT {}] Connecting to chat server {}:{}", 
                    reconnectAttempts + 1,
                    JdbcConfig.CHAT_SERVER_IP.get(),
                    JdbcConfig.CHAT_SERVER_PORT.get());

                // Create and configure socket
                clientSocket = new Socket();
                clientSocket.setReuseAddress(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setTcpNoDelay(true);
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Socket created with settings:");
                    PlayerSync.LOGGER.info("[DEBUG]   ReuseAddress: {}", clientSocket.getReuseAddress());
                    PlayerSync.LOGGER.info("[DEBUG]   KeepAlive: {}", clientSocket.getKeepAlive());
                    PlayerSync.LOGGER.info("[DEBUG]   TcpNoDelay: {}", clientSocket.getTcpNoDelay());
                }

                // Attempt connection with timeout
                long connectStartTime = System.currentTimeMillis();
                InetSocketAddress serverAddress = new InetSocketAddress(
                    JdbcConfig.CHAT_SERVER_IP.get(),
                    JdbcConfig.CHAT_SERVER_PORT.get()
                );
                
                PlayerSync.LOGGER.info("[ATTEMPT {}] Attempting connection to {} (timeout: 15s)", 
                    reconnectAttempts + 1, serverAddress);
                
                clientSocket.connect(serverAddress, 15000);
                
                long connectDuration = System.currentTimeMillis() - connectStartTime;
                PlayerSync.LOGGER.info("✓ Connection established in {}ms", connectDuration);

                clientSocket.setSoTimeout(0); // No timeout for reads

                // Set up streams
                out = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(clientSocket.getOutputStream())), true);

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()));

                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] ✓ Input/output streams established");
                    PlayerSync.LOGGER.info("[DEBUG] Local address: {}", clientSocket.getLocalSocketAddress());
                    PlayerSync.LOGGER.info("[DEBUG] Remote address: {}", clientSocket.getRemoteSocketAddress());
                }

                PlayerSync.LOGGER.info("✓ Successfully connected to chat server {}:{}", 
                    JdbcConfig.CHAT_SERVER_IP.get(), 
                    JdbcConfig.CHAT_SERVER_PORT.get());
                reconnectAttempts = 0;

                // Main message reading loop
                String serverMessage;
                int messageCount = 0;
                long lastHeartbeat = System.currentTimeMillis();
                
                while (running && !clientSocket.isClosed() && (serverMessage = in.readLine()) != null) {
                    messageCount++;
                    
                    if (serverMessage.trim().isEmpty()) {
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.warn("[DEBUG] Received empty message #{}, skipping", messageCount);
                        }
                        continue;
                    }
                    
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] [MSG #{}] Received: '{}'", messageCount, serverMessage);
                    }
                    
                    // Process the message
                    try {
                        Component textComponents = Component.nullToEmpty(serverMessage);
                        final String finalServerMessage = serverMessage; // Make effectively final for lambda
                        
                        if (playerList != null) {
                            // Send to all players on this server
                            playerList.getServer().execute(() -> {
                                try {
                                    playerList.broadcastSystemMessage(textComponents, false);
                                    
                                    if (JdbcConfig.DEBUG_MODE.get()) {
                                        PlayerSync.LOGGER.info("[DEBUG] ✓ Message broadcast to {} players", 
                                            playerList.getPlayers().size());
                                    } else {
                                        PlayerSync.LOGGER.info("✓ Broadcast message to {} players: '{}'", 
                                            playerList.getPlayers().size(), finalServerMessage);
                                    }
                                } catch (Exception e) {
                                    PlayerSync.LOGGER.error("✗ Error broadcasting message to players: {}", e.getMessage());
                                    if (JdbcConfig.DEBUG_MODE.get()) {
                                        PlayerSync.LOGGER.error("[DEBUG] Broadcast error details:", e);
                                    }
                                }
                            });
                        } else {
                            PlayerSync.LOGGER.info("✓ Received message (no players online): '{}'", serverMessage);
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.info("[DEBUG] PlayerList is null - server may not be fully initialized");
                            }
                        }
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("✗ Error processing message: {}", e.getMessage());
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.error("[DEBUG] Message processing error details:", e);
                        }
                    }
                    
                    lastHeartbeat = System.currentTimeMillis();
                }
                
                // Connection ended
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Connection ended normally after {} messages", messageCount);
                } else {
                    PlayerSync.LOGGER.info("Chat server connection ended normally (received {} messages)", messageCount);
                }

            } catch (SocketTimeoutException e) {
                PlayerSync.LOGGER.warn("✗ Connection timeout after 15 seconds (attempt {}/{})", 
                    reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS);
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.warn("[DEBUG] Timeout details: {}", e.getMessage());
                }
            } catch (ConnectException e) {
                PlayerSync.LOGGER.warn("✗ Connection refused (attempt {}/{}): {}", 
                    reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS, e.getMessage());
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.warn("[DEBUG] Connection refused details:", e);
                }
            } catch (java.net.UnknownHostException e) {
                PlayerSync.LOGGER.error("✗ Unknown host '{}' (attempt {}/{})", 
                    JdbcConfig.CHAT_SERVER_IP.get(), reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS);
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.error("[DEBUG] Host resolution error details:", e);
                }
            } catch (IOException e) {
                if (running) {
                    PlayerSync.LOGGER.error("✗ Connection error (attempt {}/{}): {} ({})", 
                        reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS, e.getMessage(), e.getClass().getSimpleName());
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.error("[DEBUG] Connection error details:", e);
                    }
                } else {
                    PlayerSync.LOGGER.info("Chat client disconnected during shutdown");
                }
            } finally {
                closeConnection();
            }

            // Reconnection logic
            if (running && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                
                if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    PlayerSync.LOGGER.error("✗ Maximum reconnection attempts ({}) reached. Chat sync will not retry.", 
                        MAX_RECONNECT_ATTEMPTS);
                    break;
                }
                
                long delay = Math.min(RECONNECT_DELAY * (long)Math.pow(2, reconnectAttempts-1), 60000);
                PlayerSync.LOGGER.warn("⟳ Reconnecting in {}ms (attempt {}/{})", 
                    delay, reconnectAttempts + 1, MAX_RECONNECT_ATTEMPTS);

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    PlayerSync.LOGGER.warn("Reconnection sleep interrupted, stopping chat client");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            PlayerSync.LOGGER.error("=== CHAT CLIENT FAILED - MAX RETRIES EXCEEDED ===");
        } else {
            PlayerSync.LOGGER.info("=== CHAT CLIENT STOPPED ===");
        }
    }

    private void closeConnection() {
        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Closing chat client connection");
        }
        
        try {
            if (out != null) {
                out.close();
                out = null;
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] ✓ Output stream closed");
                }
            }
            if (clientSocket != null && !clientSocket.isClosed()) {
                String remoteAddress = clientSocket.getRemoteSocketAddress() != null ? 
                    clientSocket.getRemoteSocketAddress().toString() : "unknown";
                clientSocket.close();
                clientSocket = null;
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] ✓ Socket closed (was connected to {})", remoteAddress);
                }
            }
        } catch (IOException e) {
            PlayerSync.LOGGER.error("✗ Error closing connection: {}", e.getMessage());
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.error("[DEBUG] Connection close error details:", e);
            }
        }
    }

    public void shutdown() {
        PlayerSync.LOGGER.info("Shutting down chat client");
        running = false;
        closeConnection();
        PlayerSync.LOGGER.info("✓ Chat client shutdown complete");
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        String playerName = event.getUsername();
        String messageText = event.getMessage().getString();
        String formattedMessage = "<" + playerName + "> " + messageText;
        
        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] [CHAT EVENT] Player '{}' sent message: '{}'", playerName, messageText);
            PlayerSync.LOGGER.info("[DEBUG] [CHAT EVENT] Formatted for sync: '{}'", formattedMessage);
        }
        
        if (out != null) {
            try {
                out.println(formattedMessage);
                
                // Check if the write was successful
                if (out.checkError()) {
                    PlayerSync.LOGGER.error("✗ Failed to send chat message to sync server (PrintWriter error)");
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.error("[DEBUG] [CHAT EVENT] PrintWriter error detected for message: '{}'", formattedMessage);
                    }
                } else {
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] [CHAT EVENT] ✓ Message sent to sync server");
                    } else {
                        PlayerSync.LOGGER.info("✓ Chat message sent to sync server: '{}'", formattedMessage);
                    }
                }
            } catch (Exception e) {
                PlayerSync.LOGGER.error("✗ Error sending chat message to sync server: {}", e.getMessage());
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.error("[DEBUG] [CHAT EVENT] Send error details:", e);
                }
            }
        } else {
            PlayerSync.LOGGER.warn("✗ Cannot send chat message - not connected to sync server");
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.warn("[DEBUG] [CHAT EVENT] Output stream is null, message: '{}'", formattedMessage);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event){
        try {
            playerList = Objects.requireNonNull(event.getEntity().getServer()).getPlayerList();
            String playerName = event.getEntity().getName().getString();
            int totalPlayers = playerList.getPlayers().size();
            
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] [PLAYER JOIN] Player '{}' joined, total players: {}", playerName, totalPlayers);
                PlayerSync.LOGGER.info("[DEBUG] [PLAYER JOIN] PlayerList updated for chat sync");
            } else {
                PlayerSync.LOGGER.info("Player '{}' joined (total: {}), chat sync ready", playerName, totalPlayers);
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("✗ Error handling player join event: {}", e.getMessage());
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.error("[DEBUG] [PLAYER JOIN] Event handling error details:", e);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event){
        try {
            playerList = Objects.requireNonNull(event.getEntity().getServer()).getPlayerList();
            String playerName = event.getEntity().getName().getString();
            int totalPlayers = playerList.getPlayers().size();
            
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] [PLAYER LEAVE] Player '{}' left, remaining players: {}", playerName, totalPlayers);
                PlayerSync.LOGGER.info("[DEBUG] [PLAYER LEAVE] PlayerList updated for chat sync");
            } else {
                PlayerSync.LOGGER.info("Player '{}' left (remaining: {})", playerName, totalPlayers);
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("✗ Error handling player leave event: {}", e.getMessage());
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.error("[DEBUG] [PLAYER LEAVE] Event handling error details:", e);
            }
        }
    }
}
