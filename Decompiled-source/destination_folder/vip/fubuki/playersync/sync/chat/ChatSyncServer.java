package vip.fubuki.playersync.sync.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

public class ChatSyncServer {
   static ServerSocket serverSocket;
   static final Set<Socket> SocketList = ConcurrentHashMap.newKeySet();
   static final ExecutorService executorService = Executors.newCachedThreadPool();
   private volatile boolean running = true;

   public void run() throws IOException {
      try {
         serverSocket = new ServerSocket((Integer)JdbcConfig.CHAT_SERVER_PORT.get());
         serverSocket.setReuseAddress(true);
         PlayerSync.LOGGER.info("Chat server started successfully on port {}", JdbcConfig.CHAT_SERVER_PORT.get());

         while (this.running && !Thread.currentThread().isInterrupted()) {
            try {
               Socket newSocket = serverSocket.accept();
               newSocket.setSoTimeout(0);
               SocketList.add(newSocket);
               executorService.submit(() -> this.handleClient(newSocket));
               PlayerSync.LOGGER.info("New client connected, total clients: {}", SocketList.size());
            } catch (IOException var5) {
               if (this.running) {
                  PlayerSync.LOGGER.error("Error accepting client connection: {}", var5.getMessage());
               }
            }
         }
      } finally {
         this.shutdown();
      }
   }

   private void handleClient(Socket socket) {
      String clientInfo = socket.getInetAddress() + ":" + socket.getPort();

      try {
         String message;
         try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            while (this.running && (message = reader.readLine()) != null) {
               this.broadcastMessage(socket, message);
            }
         } catch (SocketTimeoutException var20) {
            PlayerSync.LOGGER.warn("Client {} timeout", clientInfo);
         } catch (IOException var21) {
            PlayerSync.LOGGER.error("Error handling client {}: {}", clientInfo, var21.getMessage());
         }
      } finally {
         SocketList.remove(socket);

         try {
            if (!socket.isClosed()) {
               socket.close();
            }
         } catch (IOException var17) {
            PlayerSync.LOGGER.error("Error closing client socket: {}", var17.getMessage());
         }

         PlayerSync.LOGGER.info("Client disconnected, remaining clients: {}", SocketList.size());
      }
   }

   private void broadcastMessage(Socket sender, String message) {
      Iterator<Socket> iterator = SocketList.iterator();

      while (iterator.hasNext()) {
         Socket socket = iterator.next();
         if (!socket.equals(sender) && !socket.isClosed()) {
            try {
               PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
               writer.println(message);
            } catch (IOException var8) {
               PlayerSync.LOGGER.error("Error broadcasting to client, removing: {}", var8.getMessage());
               iterator.remove();

               try {
                  socket.close();
               } catch (IOException var7) {
               }
            }
         }
      }
   }

   public void shutdown() {
      this.running = false;

      try {
         if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
         }
      } catch (IOException var6) {
         PlayerSync.LOGGER.error("Error closing server socket: {}", var6.getMessage());
      }

      for (Socket socket : SocketList) {
         try {
            if (!socket.isClosed()) {
               socket.close();
            }
         } catch (IOException var5) {
         }
      }

      SocketList.clear();
      executorService.shutdown();

      try {
         if (!executorService.awaitTermination(5L, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
         }
      } catch (InterruptedException var4) {
         executorService.shutdownNow();
         Thread.currentThread().interrupt();
      }
   }
}
