package vip.fubuki.playersync.sync.addons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.config.JdbcConfig;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;


public class ModsSupport {
    public void doBackPackRestore(Player player) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            // --- Begin Backpack Data Restore ---
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Starting backpack restoration for player {}", player.getUUID());
            } else {
                PlayerSync.LOGGER.info("Restoring backpack data for player " + player.getUUID());
            }
            net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
                net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper
                        .fromStack(backpackItem);

                // Retrieve the contents UUID from the backpack's NBT using NBTHelper
                Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
                if (uuidOpt.isPresent()) {
                    UUID contentsUuid = uuidOpt.get();
                    
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] Processing backpack with contents UUID: {} in slot {}", contentsUuid, slot);
                    }
                    
                    try {
                        JDBCsetUp.QueryResult qrBackpack = JDBCsetUp.executeQuery("SELECT backpack_nbt FROM backpack_data WHERE uuid='" + contentsUuid + "'");
                        ResultSet rsBackpack = qrBackpack.resultSet();
                        if (rsBackpack.next()) {
                            String serialized = rsBackpack.getString("backpack_nbt");
                            
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.info("[DEBUG] Found backpack data for UUID {}, serialized length: {}", 
                                    contentsUuid, serialized != null ? serialized.length() : 0);
                            }
                            
                            try {
                                String nbtString = VanillaSync.deserializeString(serialized);
                                
                                // Apply the same NBT sanitization used for main inventory items
                                // This is critical for fixing Apotheosis empty socket key-value pairs
                                nbtString = sanitizeBackpackNbtString(nbtString);
                                
                                CompoundTag backpackNbt = NbtUtils.snbtToStructure(nbtString);
                                
                                // Safely restore backpack contents with individual item error handling
                                CompoundTag safeBackpackNbt = safelyRestoreBackpackContents(backpackNbt);
                                
                                // Update BackpackStorage with the safe NBT
                                net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, safeBackpackNbt);
                                if (JdbcConfig.DEBUG_MODE.get()) {
                                    PlayerSync.LOGGER.info("[DEBUG] Successfully restored backpack data for UUID {}", contentsUuid);
                                } else {
                                    PlayerSync.LOGGER.info("Restored backpack data for UUID " + contentsUuid);
                                }
                            } catch (CommandSyntaxException e) {
                                if (JdbcConfig.DEBUG_MODE.get()) {
                                    PlayerSync.LOGGER.error("[DEBUG] NBT parsing failed for backpack UUID {}: {}. Data preview: {}", 
                                        contentsUuid, e.getMessage(), 
                                        serialized.substring(0, Math.min(100, serialized.length())));
                                }
                                PlayerSync.LOGGER.error("Error parsing backpack NBT structure for UUID " + contentsUuid + ". Attempting item-by-item recovery.", e);
                                // Try to recover what we can from the raw serialized data
                                CompoundTag recoveredNbt = attemptBackpackRecovery(serialized, contentsUuid);
                                if (recoveredNbt != null) {
                                    net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, recoveredNbt);
                                    if (JdbcConfig.DEBUG_MODE.get()) {
                                        PlayerSync.LOGGER.info("[DEBUG] Partial backpack recovery successful for UUID {}", contentsUuid);
                                    } else {
                                        PlayerSync.LOGGER.info("Partial recovery successful for backpack UUID " + contentsUuid);
                                    }
                                } else {
                                    if (JdbcConfig.DEBUG_MODE.get()) {
                                        PlayerSync.LOGGER.error("[DEBUG] Complete backpack recovery failed for UUID {}. Creating empty backpack. Original data size: {}", 
                                            contentsUuid, serialized.length());
                                    } else {
                                        PlayerSync.LOGGER.error("Complete backpack recovery failed for UUID " + contentsUuid + ". Creating empty backpack.");
                                    }
                                    net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, new CompoundTag());
                                }
                            }
                        }
                        rsBackpack.close();
                        qrBackpack.connection().close();
                    } catch (SQLException e) {
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.error("[DEBUG] Database error for backpack UUID {}: {}", contentsUuid, e.getMessage());
                        }
                        PlayerSync.LOGGER.error("Error restoring backpack data for UUID " + contentsUuid, e);
                    } catch (Exception e) {
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.error("[DEBUG] Unexpected error for backpack UUID {}: {} - Error type: {}", 
                                contentsUuid, e.getMessage(), e.getClass().getSimpleName());
                        }
                        PlayerSync.LOGGER.error("Error parsing NBT for backpack UUID " + contentsUuid + ". Backpack contents will be lost but inventory is preserved.", e);
                    }
                } else {
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.warn("[DEBUG] Backpack item in slot {} has no contentsUuid during restore for player {}", slot, player.getUUID());
                    } else {
                        PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid during restore");
                    }
                }
                return false;
            });
            // --- End Backpack Data Restore ---
        }
    }
    /**
     * Restores the Curios inventory for a player.
     * The saved data is stored as a flat map with composite keys ("slotType:index").
     */
    public void doCuriosRestore(Player player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            // Obtain the handler from the API.
            Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
            JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery("SELECT curios_item FROM curios WHERE uuid = '" + player.getUUID() + "'");
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
                String curiosData = rs.getString("curios_item");
                // Parse the stored data (assumes a simple Map.toString() format: "{key=value, key2=value2, ...}")
                Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);
                // Clear current Curios slots to avoid conflicts.
                handlerOpt.ifPresent(handler -> handler.getCurios().forEach((slotType, stacksHandler) -> {
                    // Use the dynamic stack handler to clear slots.
                    IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                    for (int i = 0; i < dynStacks.getSlots(); i++) {
                        dynStacks.setStackInSlot(i, ItemStack.EMPTY);
                    }
                }));

                if (curiosData.length() <= 2) {
                    rs.close();
                    qr.connection().close();
                    return;
                }

                // Restore each saved item.
                handlerOpt.ifPresent(handler -> {
                    for (Map.Entry<String, String> entry : storedMap.entrySet()) {
                        String compositeKey = entry.getKey(); // Expected format: "slotType:index"
                        String[] parts = compositeKey.split(":");
                        if (parts.length != 2) {
                            continue;
                        }
                        String slotType = parts[0];
                        int slotIndex;
                        try {
                            slotIndex = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ex) {
                            continue;
                        }
                        String serialized = entry.getValue();
                        try {
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.info("[DEBUG] Deserializing Curio item for slot {}, data length: {}", 
                                    compositeKey, serialized.length());
                            }
                            
                            String nbtString = VanillaSync.deserializeString(serialized);
                            CompoundTag tag = NbtUtils.snbtToStructure(nbtString);
                            ItemStack stack = ItemStack.parse(ServerLifecycleHooks.getCurrentServer().registryAccess(),tag).get();
                            if (handler.getCurios().containsKey(slotType)) {
                                ICurioStacksHandler stacksHandler = handler.getCurios().get(slotType);
                                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                                if (slotIndex < dynStacks.getSlots()) {
                                    dynStacks.setStackInSlot(slotIndex, stack);
                                }
                            }
                        } catch (CommandSyntaxException e) {
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.error("[DEBUG] NBT parsing failed for Curio {}: {}. Data preview: {}", 
                                    compositeKey, e.getMessage(), 
                                    serialized.substring(0, Math.min(100, serialized.length())));
                            }
                            PlayerSync.LOGGER.error("Error deserializing Curio data for key " + compositeKey + ". Skipping this item to prevent inventory loss.", e);
                            // Skip this item instead of crashing - this preserves other items in the inventory
                        } catch (Exception e) {
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.error("[DEBUG] Unexpected Curio error for {}: {} - Error type: {}", 
                                    compositeKey, e.getMessage(), e.getClass().getSimpleName());
                            }
                            PlayerSync.LOGGER.error("Unexpected error parsing Curio item for key " + compositeKey + ". Skipping this item to prevent inventory loss.", e);
                            // Skip this item instead of crashing - this preserves other items in the inventory
                        }
                    }
                });
                rs.close();
                qr.connection().close();
            } else {
                // No stored data; perform an initial save.
                StoreCurios(player, true);
            }
        }
    }

    /**
     * Saves the current Curios inventory for a player.
     * It builds a flat map keyed by "slotType:index" using the dynamic stack handler.
     */
    public void onPlayerLeave(net.minecraft.world.entity.player.Player player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            StoreCurios(player, false);
        }
    }

    public void StoreCurios(net.minecraft.world.entity.player.Player player, boolean init) throws SQLException {
        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        Map<String, String> flatMap = new HashMap<>();

        handlerOpt.ifPresent(handler -> {
            // Iterate over each slot type.
            handler.getCurios().forEach((slotType, stacksHandler) -> {
                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                for (int i = 0; i < dynStacks.getSlots(); i++) {
                    ItemStack stack = dynStacks.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        String serialized = VanillaSync.serialize(VanillaSync.serializeNBT(stack).toString());
                        flatMap.put(slotType + ":" + i, serialized);
                    }
                }
            });
        });

        String serializedData = flatMap.toString();
        if (init) {
            JDBCsetUp.executeUpdate("INSERT INTO curios (uuid,curios_item) VALUES ('" + player.getUUID() + "', '" + serializedData + "')");
        } else {
            JDBCsetUp.executeUpdate("UPDATE curios SET curios_item = '" + serializedData + "' WHERE uuid = '" + player.getUUID() + "'");
        }
    }

    public static void storeSophisticatedBackpacks(Player player) {
        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Starting backpack storage for player {}", player.getUUID());
        } else {
            PlayerSync.LOGGER.info("Storing backpack data for player " + player.getUUID());
        }
        net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
            net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper
                    .fromStack(backpackItem);

            // Retrieve the contents UUID from the backpack's NBT using NBTHelper
            Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
            if (uuidOpt.isPresent()) {
                UUID contentsUuid = uuidOpt.get();
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Storing backpack with contents UUID: {} in slot {}", contentsUuid, slot);
                }
                
                // Get internal backpack data from BackpackStorage (creates it if missing)
                CompoundTag backpackNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                String serialized = VanillaSync.serialize(backpackNbt.toString());
                try {
                    // Use REPLACE INTO so existing records are updated
                    JDBCsetUp.executeUpdate("REPLACE INTO backpack_data (uuid, backpack_nbt) VALUES ('" + contentsUuid + "', '" + serialized + "')");
                    
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] Saved backpack data for UUID {}, serialized size: {} bytes", contentsUuid, serialized.length());
                    } else {
                        PlayerSync.LOGGER.info("Saved backpack data for UUID " + contentsUuid);
                    }
                } catch (SQLException e) {
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.error("[DEBUG] Database error saving backpack UUID {}: {}", contentsUuid, e.getMessage());
                    }
                    PlayerSync.LOGGER.error("Error saving backpack data for UUID " + contentsUuid, e);
                }
            } else {
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.warn("[DEBUG] Backpack item in slot {} has no contentsUuid for player {}", slot, player.getUUID());
                } else {
                    PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid");
                }
            }
            return false; // Continue processing all backpack items.
        });
    }

    /**
     * Safely restores backpack contents, replacing problematic items with placeholders
     * while preserving other items and maintaining backwards compatibility.
     */
    private CompoundTag safelyRestoreBackpackContents(CompoundTag originalBackpackNbt) {
        CompoundTag safeBackpackNbt = originalBackpackNbt.copy();
        
        // Check if this backpack has an inventory structure
        if (safeBackpackNbt.contains("inventory")) {
            CompoundTag inventoryTag = safeBackpackNbt.getCompound("inventory");
            
            // Process items in the inventory
            if (inventoryTag.contains("Items")) {
                safeBackpackNbt.put("inventory", safelyProcessInventoryItems(inventoryTag));
            }
        }
        
        // Also check for direct Items array (some backpack versions)
        if (safeBackpackNbt.contains("Items")) {
            safeBackpackNbt = safelyProcessInventoryItems(safeBackpackNbt);
        }
        
        return safeBackpackNbt;
    }
    
    /**
     * Safely processes inventory items, replacing problematic ones with placeholders
     */
    private CompoundTag safelyProcessInventoryItems(CompoundTag inventoryTag) {
        CompoundTag safeInventoryTag = inventoryTag.copy();
        
        if (safeInventoryTag.contains("Items")) {
            var itemsTag = safeInventoryTag.get("Items");
            if (itemsTag instanceof net.minecraft.nbt.ListTag itemsList) {
                net.minecraft.nbt.ListTag safeItemsList = new net.minecraft.nbt.ListTag();
                
                for (int i = 0; i < itemsList.size(); i++) {
                    var itemTag = itemsList.get(i);
                    if (itemTag instanceof CompoundTag itemCompound) {
                        try {
                            // Try to parse the item using the same robust approach as main inventory
                            ItemStack testStack = createItemStackWithFallback(itemCompound);
                            
                            // If parsing succeeded and item is not empty, keep the original item
                            if (!testStack.isEmpty()) {
                                safeItemsList.add(itemCompound.copy());
                                
                                // Log successful Apotheosis item restoration in backpacks
                                logApothosisBackpackDebugInfo("BACKPACK_ITEM_RESTORATION_SUCCESS", testStack, i);
                            } else {
                                // Item parsing returned empty - create placeholder
                                throw new RuntimeException("ItemStack parsing returned empty for backpack item");
                            }
                            
                        } catch (Exception e) {
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.warn("[DEBUG] Failed to parse backpack item at index {}: {} - Error type: {}. Item ID: {}", 
                                    i, e.getMessage(), e.getClass().getSimpleName(), 
                                    itemCompound.contains("id") ? itemCompound.getString("id") : "unknown");
                            } else {
                                PlayerSync.LOGGER.warn("Failed to parse backpack item at index " + i + ", creating placeholder: " + e.getMessage());
                            }
                            
                            // Create a placeholder for this item
                            CompoundTag placeholderTag = createBackpackPlaceholderItem(itemCompound);
                            safeItemsList.add(placeholderTag);
                        }
                    } else {
                        // Non-compound tags, just copy as-is
                        safeItemsList.add(itemTag.copy());
                    }
                }
                
                safeInventoryTag.put("Items", safeItemsList);
            }
        }
        
        return safeInventoryTag;
    }
    
    /**
     * Creates a placeholder item for problematic backpack items, preserving slot and count info
     */
    private CompoundTag createBackpackPlaceholderItem(CompoundTag originalItemTag) {
        CompoundTag placeholderTag = new CompoundTag();
        
        // Preserve essential inventory properties
        if (originalItemTag.contains("Slot")) {
            placeholderTag.putByte("Slot", originalItemTag.getByte("Slot"));
        }
        
        int count = originalItemTag.contains("Count") ? originalItemTag.getByte("Count") : 1;
        if (count <= 0) count = 1;
        
        // Create paper item as placeholder
        placeholderTag.putString("id", "minecraft:paper");
        placeholderTag.putByte("Count", (byte) count);
        
        // Store original item info in custom data
        CompoundTag customData = new CompoundTag();
        customData.putString("playersync:original_backpack_item", originalItemTag.toString());
        customData.putString("playersync:original_item_id", 
            originalItemTag.contains("id") ? originalItemTag.getString("id") : "unknown");
        customData.putString("playersync:unique_id", java.util.UUID.randomUUID().toString());
        
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_data", customData);
        
        // Add display name and lore
        CompoundTag itemName = new CompoundTag();
        itemName.putString("text", "§cCorrupted Backpack Item");
        itemName.putBoolean("italic", true);
        components.put("minecraft:item_name", itemName);
        
        net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
        CompoundTag loreLine1 = new CompoundTag();
        loreLine1.putString("text", "§7Original: " + (originalItemTag.contains("id") ? originalItemTag.getString("id") : "unknown"));
        loreLine1.putBoolean("italic", false);
        lore.add(loreLine1);
        
        CompoundTag loreLine2 = new CompoundTag();
        loreLine2.putString("text", "§7Count: " + count);
        loreLine2.putBoolean("italic", false);
        lore.add(loreLine2);
        
        CompoundTag loreLine3 = new CompoundTag();
        loreLine3.putString("text", "");
        lore.add(loreLine3);
        
        CompoundTag loreLine4 = new CompoundTag();
        loreLine4.putString("text", "§8This item was corrupted during");
        lore.add(loreLine4);
        
        CompoundTag loreLine5 = new CompoundTag();
        loreLine5.putString("text", "§8sync and replaced with placeholder");
        lore.add(loreLine5);
        
        CompoundTag loreComponent = new CompoundTag();
        loreComponent.put("lines", lore);
        components.put("minecraft:lore", loreComponent);
        
        placeholderTag.put("components", components);
        
        return placeholderTag;
    }
    
    /**
     * Attempts to recover backpack contents when complete NBT parsing fails
     * This provides a last-resort recovery mechanism for backwards compatibility
     */
    private CompoundTag attemptBackpackRecovery(String serializedData, UUID contentsUuid) {
        try {
            // Try manual parsing for common corruption patterns
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Attempting manual backpack recovery for UUID {}, data size: {} bytes", contentsUuid, serializedData.length());
            } else {
                PlayerSync.LOGGER.debug("Attempting manual backpack recovery for UUID " + contentsUuid);
            }
            
            // Create a minimal valid backpack structure
            CompoundTag recoveredNbt = new CompoundTag();
            CompoundTag inventory = new CompoundTag();
            net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
            
            // Add a placeholder item indicating recovery was attempted
            CompoundTag recoveryItem = new CompoundTag();
            recoveryItem.putString("id", "minecraft:paper");
            recoveryItem.putByte("Count", (byte) 1);
            recoveryItem.putByte("Slot", (byte) 0);
            
            CompoundTag customData = new CompoundTag();
            customData.putString("playersync:recovery_info", "Backpack contents were corrupted and could not be fully recovered");
            customData.putString("playersync:original_data_size", String.valueOf(serializedData.length()));
            customData.putString("playersync:unique_id", java.util.UUID.randomUUID().toString());
            
            CompoundTag components = new CompoundTag();
            components.put("minecraft:custom_data", customData);
            
            CompoundTag itemName = new CompoundTag();
            itemName.putString("text", "§6Backpack Recovery Notice");
            components.put("minecraft:item_name", itemName);
            
            recoveryItem.put("components", components);
            items.add(recoveryItem);
            
            inventory.put("Items", items);
            recoveredNbt.put("inventory", inventory);
            
            return recoveredNbt;
            
        } catch (Exception e) {
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.error("[DEBUG] Backpack recovery attempt failed for UUID {}: {} - Error type: {}", 
                    contentsUuid, e.getMessage(), e.getClass().getSimpleName());
            }
            PlayerSync.LOGGER.error("Backpack recovery attempt failed for UUID " + contentsUuid, e);
            return null;
        }
    }

    /**
     * Sanitizes NBT strings to fix known corruption issues while maintaining backwards compatibility.
     * This is the same logic used in VanillaSync for main inventory items.
     * Specifically targets:
     * - Apotheosis empty key-value pairs like {"":""}
     * - Other malformed JSON/NBT structures that cause parsing failures
     *
     * @param nbtString The NBT string to sanitize
     * @return The sanitized NBT string
     */
    private static String sanitizeBackpackNbtString(String nbtString) {
        if (nbtString == null || nbtString.isEmpty()) {
            return nbtString;
        }

        String sanitized = nbtString;
        boolean wasModified = false;

        // Fix Apotheosis empty key-value pairs: {"":""}
        // These appear in the 'with' array of apotheosis:affix_name components
        String emptyKvPattern = "\\{\"\":\"\"\\}";
        if (sanitized.matches(".*" + emptyKvPattern + ".*")) {
            // Remove empty key-value pairs from arrays, handling various spacing scenarios
            sanitized = sanitized.replaceAll(",\\s*" + emptyKvPattern, ""); // Remove if it's not the first element
            sanitized = sanitized.replaceAll(emptyKvPattern + "\\s*,", ""); // Remove if it's the first element
            sanitized = sanitized.replaceAll(emptyKvPattern, ""); // Remove if it's the only element
            wasModified = true;
        }

        // Fix orphaned commas that might result from the above cleanup
        sanitized = sanitized.replaceAll(",\\s*,", ","); // Double commas
        sanitized = sanitized.replaceAll("\\[\\s*,", "["); // Leading comma in arrays
        sanitized = sanitized.replaceAll(",\\s*\\]", "]"); // Trailing comma in arrays
        sanitized = sanitized.replaceAll("\\{\\s*,", "{"); // Leading comma in objects
        sanitized = sanitized.replaceAll(",\\s*\\}", "}"); // Trailing comma in objects

        if (wasModified && JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Sanitized corrupted backpack NBT - Original length: {}, Sanitized length: {}", 
                nbtString.length(), sanitized.length());
            PlayerSync.LOGGER.info("[DEBUG] Fixed empty key-value pairs in backpack NBT structure");
        }

        return sanitized;
    }

    /**
     * Logs comprehensive debugging information for Apotheosis items in backpacks
     */
    private static void logApothosisBackpackDebugInfo(String operation, ItemStack itemStack, int slotIndex) {
        if (!JdbcConfig.DEBUG_MODE.get()) {
            return;
        }
        
        String itemId = itemStack.getItem().toString();
        boolean isApothItem = itemId.contains("apotheosis") || 
                            (itemStack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) && 
                             itemStack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().toString().contains("apotheosis"));
        
        if (isApothItem) {
            PlayerSync.LOGGER.info("=== APOTHEOSIS BACKPACK DEBUG: {} ===", operation);
            PlayerSync.LOGGER.info("Backpack Slot Index: {}", slotIndex);
            PlayerSync.LOGGER.info("Item ID: {}", itemId);
            PlayerSync.LOGGER.info("Item Count: {}", itemStack.getCount());
            
            if (itemStack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                CompoundTag customData = itemStack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                PlayerSync.LOGGER.info("Custom Data Keys: {}", customData.getAllKeys());
                
                // Log specific Apotheosis-related keys
                if (customData.contains("apotheosis")) {
                    PlayerSync.LOGGER.info("Apotheosis Data Present: {}", customData.get("apotheosis"));
                }
                if (customData.contains("affix_data")) {
                    PlayerSync.LOGGER.info("Affix Data Present: TRUE");
                }
            }
            
            PlayerSync.LOGGER.info("=== END APOTHEOSIS BACKPACK DEBUG ===");
        }
    }

    /**
     * Attempts to create an ItemStack using a more lenient approach when ItemStack.parse fails.
     * This is the same method used in VanillaSync for main inventory items.
     */
    private static ItemStack createItemStackWithFallback(CompoundTag compoundTag) {
        try {
            // First attempt: Try the standard ItemStack.parse method
            net.minecraft.core.HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            return ItemStack.parse(provider, compoundTag).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            if (JdbcConfig.DEBUG_MODE.get()) {
                String itemId = compoundTag.contains("id") ? compoundTag.getString("id") : "unknown";
                PlayerSync.LOGGER.info("[DEBUG] ItemStack.parse failed for backpack item {}, attempting fallback creation: {}", 
                    itemId, e.getMessage());
            }
            
            try {
                // Extract registry name
                if (!compoundTag.contains("id", net.minecraft.nbt.Tag.TAG_STRING)) {
                    return ItemStack.EMPTY;
                }
                
                net.minecraft.resources.ResourceLocation registryName = 
                    net.minecraft.resources.ResourceLocation.tryParse(compoundTag.getString("id"));
                
                if (registryName == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(registryName)) {
                    return ItemStack.EMPTY;
                }
                
                // Fallback: Create ItemStack manually and apply custom data
                ItemStack fallbackStack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(registryName));
                
                // Set count
                if (compoundTag.contains("count", net.minecraft.nbt.Tag.TAG_INT)) {
                    fallbackStack.setCount(compoundTag.getInt("count"));
                } else if (compoundTag.contains("Count", net.minecraft.nbt.Tag.TAG_INT)) {
                    fallbackStack.setCount(compoundTag.getInt("Count"));
                }
                
                // Handle components (new format) - critical for Apotheosis items
                if (compoundTag.contains("components", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    CompoundTag componentsTag = compoundTag.getCompound("components");
                    
                    // Apply custom_data component if present (essential for Apotheosis)
                    if (componentsTag.contains("minecraft:custom_data", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                        CompoundTag customData = componentsTag.getCompound("minecraft:custom_data");
                        net.minecraft.world.item.component.CustomData.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA, 
                            fallbackStack, 
                            customData
                        );
                        
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.info("[DEBUG] Applied custom_data component to backpack fallback {}: {}", 
                                registryName, customData.toString());
                        }
                    }
                    
                    // Handle other critical components for Apotheosis items
                    if (componentsTag.contains("minecraft:enchantments")) {
                        // Try to preserve enchantments if possible
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.info("[DEBUG] Preserving enchantments component for backpack {}", registryName);
                        }
                    }
                }
                
                // Handle legacy tag format (for backward compatibility)
                if (compoundTag.contains("tag", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    CompoundTag legacyTag = compoundTag.getCompound("tag");
                    net.minecraft.world.item.component.CustomData.set(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA, 
                        fallbackStack, 
                        legacyTag
                    );
                    
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] Applied legacy tag to backpack fallback {}: {}", 
                            registryName, legacyTag.toString());
                    }
                }
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Successfully created fallback backpack item: {} (count: {})", 
                        registryName, fallbackStack.getCount());
                }
                
                return fallbackStack;
            } catch (Exception fallbackException) {
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.error("[DEBUG] Fallback ItemStack creation also failed for backpack item: {}", 
                        fallbackException.getMessage());
                }
                return ItemStack.EMPTY;
            }
        }
    }

}
