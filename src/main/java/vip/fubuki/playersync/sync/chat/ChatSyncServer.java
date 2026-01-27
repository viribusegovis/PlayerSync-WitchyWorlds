package vip.fubuki.playersync.sync.chat;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChatSyncServer {
    static ServerSocket serverSocket;
    static final Set<Socket> SocketList = ConcurrentHashMap.newKeySet();
    static final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public void run() throws IOException {
        PlayerSync.LOGGER.info("=== CHAT SERVER STARTUP ===");
        PlayerSync.LOGGER.info("Attempting to bind to port: {}", JdbcConfig.CHAT_SERVER_PORT.get());
        
        try {
            serverSocket = new ServerSocket(JdbcConfig.CHAT_SERVER_PORT.get());
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(1000); // 1 second timeout for accept() to allow clean shutdown
            
            PlayerSync.LOGGER.info("✓ Chat server socket created successfully");
            PlayerSync.LOGGER.info("✓ Chat server listening on port {}", JdbcConfig.CHAT_SERVER_PORT.get());
            PlayerSync.LOGGER.info("✓ Server socket address: {}", serverSocket.getLocalSocketAddress());
            PlayerSync.LOGGER.info("=== CHAT SERVER READY ===");

            int connectionAttempts = 0;
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    if (JdbcConfig.DEBUG_MODE.get() && connectionAttempts % 30 == 0) { // Log every 30 seconds in debug
                        PlayerSync.LOGGER.info("[DEBUG] Chat server waiting for connections... (active clients: {})", SocketList.size());
                    }
                    
                    Socket newSocket = serverSocket.accept();
                    connectionAttempts = 0; // Reset counter when we get a connection
                    
                    // Configure the client socket
                    newSocket.setSoTimeout(0); // No timeout for client read operations
                    newSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
                    newSocket.setKeepAlive(true); // Enable TCP keep-alive
                    
                    SocketList.add(newSocket);
                    
                    String clientInfo = newSocket.getInetAddress().getHostAddress() + ":" + newSocket.getPort();
                    PlayerSync.LOGGER.info("✓ New client connected from {}", clientInfo);
                    PlayerSync.LOGGER.info("  Total active clients: {}", SocketList.size());
                    
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] Client socket details:");
                        PlayerSync.LOGGER.info("[DEBUG]   Remote: {}", newSocket.getRemoteSocketAddress());
                        PlayerSync.LOGGER.info("[DEBUG]   Local: {}", newSocket.getLocalSocketAddress());
                        PlayerSync.LOGGER.info("[DEBUG]   Keep-alive: {}", newSocket.getKeepAlive());
                        PlayerSync.LOGGER.info("[DEBUG]   TCP no delay: {}", newSocket.getTcpNoDelay());
                    }
                    
                    executorService.submit(() -> handleClient(newSocket));
                    
                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout, continue loop
                    connectionAttempts++;
                } catch (IOException e) {
                    if (running) {
                        PlayerSync.LOGGER.error("Error accepting client connection: {} ({})", e.getMessage(), e.getClass().getSimpleName());
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.error("[DEBUG] Full stack trace:", e);
                        }
                    } else {
                        PlayerSync.LOGGER.info("Chat server stopped accepting connections (shutdown in progress)");
                    }
                }
            }
        } catch (IOException e) {
            PlayerSync.LOGGER.error("✗ Failed to start chat server on port {}: {}", 
                JdbcConfig.CHAT_SERVER_PORT.get(), e.getMessage());
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.error("[DEBUG] Chat server startup error details:", e);
            }
            throw e;
        } finally {
            PlayerSync.LOGGER.info("Chat server main loop ended, initiating shutdown");
            shutdown();
        }
    }

    private void handleClient(Socket socket) {
        String clientInfo = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        PlayerSync.LOGGER.info("[CLIENT-{}] Starting client handler thread", clientInfo);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {

            PlayerSync.LOGGER.info("[CLIENT-{}] ✓ Client input stream established", clientInfo);
            int messageCount = 0;
            
            String message;
            while (running && !socket.isClosed() && (message = reader.readLine()) != null) {
                messageCount++;
                
                if (message.trim().isEmpty()) {
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.warn("[DEBUG] [CLIENT-{}] Received empty message, skipping", clientInfo);
                    }
                    continue;
                }
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] [CLIENT-{}] Message #{}: '{}'", clientInfo, messageCount, message);
                } else {
                    PlayerSync.LOGGER.info("[CLIENT-{}] Broadcasting message: '{}'", clientInfo, message);
                }
                
                broadcastMessage(socket, message);
            }
            
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] [CLIENT-{}] Client disconnected normally after {} messages", clientInfo, messageCount);
            } else {
                PlayerSync.LOGGER.info("[CLIENT-{}] Client disconnected normally", clientInfo);
            }

        } catch (SocketTimeoutException e) {
            PlayerSync.LOGGER.warn("[CLIENT-{}] ✗ Client connection timeout", clientInfo);
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.warn("[DEBUG] [CLIENT-{}] Timeout details: {}", clientInfo, e.getMessage());
            }
        } catch (IOException e) {
            if (running) {
                PlayerSync.LOGGER.error("[CLIENT-{}] ✗ Error reading from client: {} ({})", clientInfo, e.getMessage(), e.getClass().getSimpleName());
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.error("[DEBUG] [CLIENT-{}] Full error details:", clientInfo, e);
                }
            } else {
                PlayerSync.LOGGER.info("[CLIENT-{}] Client disconnected during shutdown", clientInfo);
            }
        } finally {
            // Clean up this client
            boolean wasInList = SocketList.remove(socket);
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.error("[DEBUG] [CLIENT-{}] Error closing socket: {}", clientInfo, e.getMessage());
                }
            }
            
            PlayerSync.LOGGER.info("[CLIENT-{}] ✓ Client cleanup completed (was in list: {}), remaining clients: {}", 
                clientInfo, wasInList, SocketList.size());
        }
    }

    private void broadcastMessage(Socket sender, String message) {
        String senderInfo = sender.getInetAddress().getHostAddress() + ":" + sender.getPort();
        int totalClients = SocketList.size();
        int successfulBroadcasts = 0;
        int failedBroadcasts = 0;
        
        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] [BROADCAST] Starting broadcast from {} to {} clients: '{}'", 
                senderInfo, totalClients - 1, message);
        }
        
        Iterator<Socket> iterator = SocketList.iterator();
        while (iterator.hasNext()) {
            Socket socket = iterator.next();
            
            // Don't send back to sender
            if (socket.equals(sender)) {
                continue;
            }
            
            if (socket.isClosed()) {
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.warn("[DEBUG] [BROADCAST] Removing closed socket from list");
                }
                iterator.remove();
                failedBroadcasts++;
                continue;
            }
            
            try {
                String targetInfo = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
                
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                writer.println(message);
                
                // Check if the write was successful
                if (writer.checkError()) {
                    throw new IOException("PrintWriter error detected");
                }
                
                successfulBroadcasts++;
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] [BROADCAST] ✓ Message sent to {}", targetInfo);
                }
                
            } catch (IOException e) {
                String targetInfo = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
                PlayerSync.LOGGER.error("[BROADCAST] ✗ Failed to send to {}: {} ({})", 
                    targetInfo, e.getMessage(), e.getClass().getSimpleName());
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.error("[DEBUG] [BROADCAST] Removing failed client {}", targetInfo);
                }
                
                iterator.remove();
                failedBroadcasts++;
                
                try {
                    socket.close();
                } catch (IOException ex) {
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.error("[DEBUG] [BROADCAST] Error closing failed socket: {}", ex.getMessage());
                    }
                }
            }
        }
        
        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] [BROADCAST] Broadcast complete: {} successful, {} failed, {} remaining clients", 
                successfulBroadcasts, failedBroadcasts, SocketList.size());
        } else if (successfulBroadcasts > 0) {
            PlayerSync.LOGGER.info("[BROADCAST] Message sent to {} client(s)", successfulBroadcasts);
        }
        
        if (failedBroadcasts > 0) {
            PlayerSync.LOGGER.warn("[BROADCAST] {} client(s) removed due to send failures", failedBroadcasts);
        }
    }

    public void shutdown() {
        PlayerSync.LOGGER.info("=== CHAT SERVER SHUTDOWN ===");
        running = false;
        
        // Close server socket first to stop accepting new connections
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                PlayerSync.LOGGER.info("Closing server socket on port {}", JdbcConfig.CHAT_SERVER_PORT.get());
                serverSocket.close();
                PlayerSync.LOGGER.info("✓ Server socket closed");
            }
        } catch (IOException e) {
            PlayerSync.LOGGER.error("✗ Error closing server socket: {}", e.getMessage());
        }

        // Close all client connections
        int clientCount = SocketList.size();
        if (clientCount > 0) {
            PlayerSync.LOGGER.info("Closing {} client connection(s)", clientCount);
            
            int closedCount = 0;
            for (Socket socket : SocketList) {
                try {
                    if (!socket.isClosed()) {
                        String clientInfo = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
                        socket.close();
                        closedCount++;
                        
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.info("[DEBUG] ✓ Closed client connection: {}", clientInfo);
                        }
                    }
                } catch (IOException e) {
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.error("[DEBUG] Error closing client socket: {}", e.getMessage());
                    }
                }
            }
            PlayerSync.LOGGER.info("✓ Closed {} client connection(s)", closedCount);
        }
        SocketList.clear();

        // Shutdown executor service
        PlayerSync.LOGGER.info("Shutting down client handler thread pool");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                PlayerSync.LOGGER.warn("Executor service did not terminate gracefully, forcing shutdown");
                executorService.shutdownNow();
                
                if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                    PlayerSync.LOGGER.error("Executor service did not terminate after forced shutdown");
                }
            } else {
                PlayerSync.LOGGER.info("✓ Executor service terminated gracefully");
            }
        } catch (InterruptedException e) {
            PlayerSync.LOGGER.warn("Shutdown interrupted, forcing executor service shutdown");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        PlayerSync.LOGGER.info("=== CHAT SERVER SHUTDOWN COMPLETE ===");
    }
}
