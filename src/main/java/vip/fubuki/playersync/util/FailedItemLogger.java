package vip.fubuki.playersync.util;

import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Utility class for saving failed item data to debug files for analysis
 */
public class FailedItemLogger {
    
    private static final String DEBUG_FOLDER = "debug";
    private static final String FAILED_ITEMS_FOLDER = "failed_items";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    /**
     * Saves failed item data to a debug file for later analysis
     * 
     * @param playerUuid The UUID of the player who owns the item
     * @param itemType The type of item (inventory, backpack, curios, etc.)
     * @param serializedData The original serialized item data that failed to parse
     * @param errorMessage The error message from the parsing failure
     * @param additionalInfo Any additional context information
     */
    public static void saveFailedItem(UUID playerUuid, String itemType, String serializedData, 
                                    String errorMessage, String additionalInfo) {
        
        if (!JdbcConfig.SAVE_FAILED_ITEMS.get()) {
            return; // Feature disabled
        }
        
        try {
            // Create debug directories if they don't exist
            Path debugDir = Paths.get(DEBUG_FOLDER);
            Path failedItemsDir = debugDir.resolve(FAILED_ITEMS_FOLDER);
            Files.createDirectories(failedItemsDir);
            
            // Generate unique filename with timestamp
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String filename = String.format("failed_%s_%s_%s_%d.txt", 
                itemType, playerUuid.toString().substring(0, 8), timestamp, System.currentTimeMillis() % 10000);
            
            Path filePath = failedItemsDir.resolve(filename);
            
            // Build file content with metadata and data
            StringBuilder content = new StringBuilder();
            content.append("=== FAILED ITEM DATA ANALYSIS ===\n");
            content.append("Timestamp: ").append(LocalDateTime.now()).append("\n");
            content.append("Player UUID: ").append(playerUuid).append("\n");
            content.append("Item Type: ").append(itemType).append("\n");
            content.append("Error Message: ").append(errorMessage).append("\n");
            content.append("Data Length: ").append(serializedData != null ? serializedData.length() : 0).append(" characters\n");
            
            if (additionalInfo != null && !additionalInfo.isEmpty()) {
                content.append("Additional Info: ").append(additionalInfo).append("\n");
            }
            
            content.append("\n=== SERIALIZED DATA ===\n");
            if (serializedData != null) {
                content.append(serializedData);
            } else {
                content.append("NULL");
            }
            
            content.append("\n\n=== DATA PREVIEW (first 500 chars) ===\n");
            if (serializedData != null && !serializedData.isEmpty()) {
                String preview = serializedData.substring(0, Math.min(500, serializedData.length()));
                content.append(preview);
                if (serializedData.length() > 500) {
                    content.append("...\n[DATA TRUNCATED FOR PREVIEW]");
                }
            } else {
                content.append("No data to preview");
            }
            
            // Write to file
            Files.write(filePath, content.toString().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            
            PlayerSync.LOGGER.info("[FAILED-ITEM] Saved failed item data to: {}", filePath.toString());
            
        } catch (IOException e) {
            PlayerSync.LOGGER.error("[FAILED-ITEM] Could not save failed item data: {}", e.getMessage());
        }
    }
    
    /**
     * Convenience method for saving failed inventory items
     */
    public static void saveFailedInventoryItem(UUID playerUuid, String serializedData, String errorMessage, int slotIndex) {
        saveFailedItem(playerUuid, "inventory", serializedData, errorMessage, "Slot: " + slotIndex);
    }
    
    /**
     * Convenience method for saving failed backpack items
     */
    public static void saveFailedBackpackItem(UUID playerUuid, UUID backpackUuid, String serializedData, String errorMessage, String context) {
        saveFailedItem(playerUuid, "backpack", serializedData, errorMessage, 
            "Backpack UUID: " + backpackUuid + ", Context: " + context);
    }
    
    /**
     * Convenience method for saving failed Curios items
     */
    public static void saveFailedCurioItem(UUID playerUuid, String slotType, int slotIndex, String serializedData, String errorMessage) {
        saveFailedItem(playerUuid, "curios", serializedData, errorMessage, 
            "Slot Type: " + slotType + ", Slot Index: " + slotIndex);
    }
    
    /**
     * Creates a summary file listing all failed items for a player
     */
    public static void createPlayerSummary(UUID playerUuid) {
        if (!JdbcConfig.SAVE_FAILED_ITEMS.get()) {
            return;
        }
        
        try {
            Path failedItemsDir = Paths.get(DEBUG_FOLDER).resolve(FAILED_ITEMS_FOLDER);
            if (!Files.exists(failedItemsDir)) {
                return;
            }
            
            String playerPrefix = "failed_" + playerUuid.toString().substring(0, 8);
            long fileCount = Files.list(failedItemsDir)
                .filter(path -> path.getFileName().toString().startsWith(playerPrefix))
                .count();
            
            if (fileCount > 0) {
                String summaryFilename = "SUMMARY_" + playerUuid.toString().substring(0, 8) + "_" + 
                    LocalDateTime.now().format(TIMESTAMP_FORMAT) + ".txt";
                Path summaryPath = failedItemsDir.resolve(summaryFilename);
                
                StringBuilder summary = new StringBuilder();
                summary.append("=== FAILED ITEMS SUMMARY ===\n");
                summary.append("Player UUID: ").append(playerUuid).append("\n");
                summary.append("Total Failed Items: ").append(fileCount).append("\n");
                summary.append("Generated: ").append(LocalDateTime.now()).append("\n");
                summary.append("\nRecommendation: Review individual failed item files to identify patterns in corruption.\n");
                summary.append("Common issues: Draconic Evolution config_properties, Apotheosis empty values, mod compatibility.\n");
                
                Files.write(summaryPath, summary.toString().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                
                PlayerSync.LOGGER.info("[FAILED-ITEM] Created summary for player {}: {} failed items", 
                    playerUuid.toString().substring(0, 8), fileCount);
            }
            
        } catch (IOException e) {
            PlayerSync.LOGGER.error("[FAILED-ITEM] Could not create player summary: {}", e.getMessage());
        }
    }
}