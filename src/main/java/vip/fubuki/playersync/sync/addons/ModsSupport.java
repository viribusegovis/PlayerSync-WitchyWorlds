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
import vip.fubuki.playersync.util.FailedItemLogger;
import vip.fubuki.playersync.config.JdbcConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ModsSupport {
    /**
     * Proper backpack restoration using Sophisticated Backpacks inventory handler APIs.
     * This approach works WITH the stack upgrade system instead of bypassing it.
     */
    public void doBackPackRestore(Player player) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.info("[BACKPACK] START restore player={}", player.getUUID());
            } else {
                PlayerSync.LOGGER.info("Restoring backpack data for player " + player.getUUID());
            }
            
            net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
                net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper
                        .fromStack(backpackItem);

                Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
                if (uuidOpt.isPresent()) {
                    UUID contentsUuid = uuidOpt.get();
                    
                    if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                        PlayerSync.LOGGER.info("[BACKPACK] PROC uuid={} slot={}", contentsUuid, slot);
                    }
                    
                    try {
                        JDBCsetUp.QueryResult qrBackpack = JDBCsetUp.executeQuery("SELECT backpack_nbt FROM backpack_data WHERE uuid='" + contentsUuid + "'");
                        ResultSet rsBackpack = qrBackpack.resultSet();
                        if (rsBackpack.next()) {
                            String serialized = rsBackpack.getString("backpack_nbt");
                            
                            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                                PlayerSync.LOGGER.info("[BACKPACK] FOUND uuid={} len={}", contentsUuid, serialized != null ? serialized.length() : 0);
                            }
                            
                            // New approach: restore items using proper inventory handler APIs
                            restoreBackpackItemsUsingInventoryHandler(backpackWrapper, serialized, contentsUuid, player.getUUID());
                        }
                        rsBackpack.close();
                        qrBackpack.connection().close();
                    } catch (SQLException e) {
                        if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                            PlayerSync.LOGGER.error("[BACKPACKS] Database error for backpack UUID {}: {}", contentsUuid, e.getMessage());
                        }
                        PlayerSync.LOGGER.error("Error restoring backpack data for UUID " + contentsUuid, e);
                    }
                } else {
                    if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                        PlayerSync.LOGGER.warn("[DEBUG] Backpack item in slot {} has no contentsUuid during restore for player {}", slot, player.getUUID());
                    } else {
                        PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid during restore");
                    }
                }
                return false;
            });
        }
    }

    /**
     * Restores backpack items using proper Sophisticated Backpacks inventory handler APIs.
     * This method works WITH stack upgrades instead of bypassing them via direct NBT manipulation.
     */
    private void restoreBackpackItemsUsingInventoryHandler(net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper, 
                                                         String serialized, UUID contentsUuid, UUID playerUuid) {
        try {
            String nbtString = VanillaSync.deserializeString(serialized);
            nbtString = sanitizeBackpackNbtString(nbtString);
            CompoundTag backpackNbt = NbtUtils.snbtToStructure(nbtString);
            
            // Get the proper inventory handler that respects stack upgrades
            net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler inventoryHandler = backpackWrapper.getInventoryHandler();
            
            // Get stack upgrade limit for this backpack
            int maxSlotLimit = getStackUpgradeLimit(backpackWrapper);
            
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.info("[BACKPACK] Using inventory handler with slot limit={} for uuid={}", maxSlotLimit, contentsUuid);
            }
            
            // Clear the backpack first to ensure clean restoration
            for (int slot = 0; slot < inventoryHandler.getSlots(); slot++) {
                inventoryHandler.setStackInSlot(slot, ItemStack.EMPTY);
            }
            
            // Extract and restore items using proper inventory handler
            restoreItemsFromBackpackNbt(backpackNbt, inventoryHandler, maxSlotLimit, contentsUuid, playerUuid);
            
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.info("[BACKPACK] RESTORED uuid={} using inventory handler", contentsUuid);
            } else {
                PlayerSync.LOGGER.info("Restored backpack data for UUID " + contentsUuid);
            }
            
        } catch (Exception e) {
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.error("[BACKPACK] Restore FAIL uuid={} err={}", contentsUuid, e.getMessage());
            }
            PlayerSync.LOGGER.error("Error restoring backpack using inventory handler for UUID " + contentsUuid, e);
            
            // Save failed backpack data for analysis
            FailedItemLogger.saveFailedBackpackItem(playerUuid, contentsUuid, serialized, 
                e.getClass().getSimpleName() + ": " + e.getMessage(), "Inventory handler restoration failed");
                
            // Create empty backpack as fallback
            createEmptyBackpackFallback(backpackWrapper, contentsUuid);
        }
    }

    /**
     * Gets the effective stack limit for this backpack considering installed stack upgrades.
     */
    private int getStackUpgradeLimit(net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper) {
        try {
            // Use reflection to access StackUpgradeItem.getInventorySlotLimit()
            Class<?> stackUpgradeItemClass = Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.stack.StackUpgradeItem");
            Method getInventorySlotLimitMethod = stackUpgradeItemClass.getMethod("getInventorySlotLimit", 
                net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper.class);
            
            Object slotLimit = getInventorySlotLimitMethod.invoke(null, backpackWrapper);
            
            if (slotLimit instanceof Integer) {
                int limit = (Integer) slotLimit;
                if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                    PlayerSync.LOGGER.info("[BACKPACK] Stack upgrade limit detected: {}", limit);
                }
                return limit;
            }
        } catch (Exception e) {
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.warn("[BACKPACK] Could not determine stack upgrade limit, using default: {}", e.getMessage());
            }
        }
        
        // Default Minecraft stack size if we can't determine upgrade limit
        return 64;
    }

    /**
     * Restores items from backpack NBT data using the proper inventory handler.
     * This respects stack upgrades and handles high counts properly.
     */
    private void restoreItemsFromBackpackNbt(CompoundTag backpackNbt, 
                                           net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler inventoryHandler,
                                           int maxSlotLimit, UUID contentsUuid, UUID playerUuid) {
        try {
            // Find the inventory data within the NBT structure
            CompoundTag inventoryTag = null;
            if (backpackNbt.contains("inventory")) {
                inventoryTag = backpackNbt.getCompound("inventory");
            } else if (backpackNbt.contains("Items")) {
                // Some versions store Items directly
                inventoryTag = backpackNbt;
            }
            
            if (inventoryTag == null) {
                if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                    PlayerSync.LOGGER.warn("[BACKPACK] No inventory data found in NBT for uuid={}", contentsUuid);
                }
                return;
            }
            
            var itemsTag = inventoryTag.get("Items");
            if (!(itemsTag instanceof net.minecraft.nbt.ListTag itemsList)) {
                if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                    PlayerSync.LOGGER.warn("[BACKPACK] Items tag is not a ListTag for uuid={}", contentsUuid);
                }
                return;
            }
            
            int successfulItems = 0;
            int failedItems = 0;
            int highStackItems = 0;
            
            for (int i = 0; i < itemsList.size(); i++) {
                var itemTag = itemsList.get(i);
                if (itemTag instanceof CompoundTag itemCompound) {
                    try {
                        // Get the target slot from NBT
                        int targetSlot = itemCompound.contains("Slot") ? itemCompound.getByte("Slot") : i;
                        
                        // Ensure slot is within bounds
                        if (targetSlot >= inventoryHandler.getSlots()) {
                            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                                PlayerSync.LOGGER.warn("[BACKPACK] Slot {} out of bounds, skipping item", targetSlot);
                            }
                            continue;
                        }
                        
                        // Deserialize the item using our robust method
                        String serializedItemData = VanillaSync.serialize(itemCompound.toString());
                        
                        ItemStack itemStack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(serializedItemData);
                        
                        if (!itemStack.isEmpty()) {
                            // Check if item count exceeds the stack upgrade limit
                            if (itemStack.getCount() > maxSlotLimit) {
                                highStackItems++;
                                
                                // CRITICAL: With proper inventory handler, high stack counts should be handled automatically
                                // The inventory handler respects stack upgrades, so we DON'T clamp the count
                                // Let the inventory handler deal with it properly
                            }
                            
                            // Use the inventory handler to set the item - this respects all upgrades
                            inventoryHandler.setStackInSlot(targetSlot, itemStack);
                            
                            successfulItems++;
                            
                        } else {
                            failedItems++;
                        }
                        
                    } catch (Exception e) {
                        if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                            PlayerSync.LOGGER.warn("[BACKPACK] Failed to restore item at index {}: {}", i, e.getMessage());
                        }
                        
                        // Log the failed item for analysis
                        String itemId = itemCompound.contains("id") ? itemCompound.getString("id") : "unknown";
                        FailedItemLogger.saveFailedBackpackItem(playerUuid, contentsUuid, itemCompound.toString(),
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "Individual item restoration failed - Index: " + i + ", Item ID: " + itemId);
                        
                        failedItems++;
                    }
                }
            }
            
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.info("[BACKPACK] Restoration complete: {} successful, {} failed, {} high-stack items", 
                    successfulItems, failedItems, highStackItems);
            }
            
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Error processing backpack NBT items for UUID " + contentsUuid, e);
            throw e;
        }
    }

    /**
     * Creates an empty backpack as fallback when restoration completely fails.
     */
    private void createEmptyBackpackFallback(net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper, UUID contentsUuid) {
        try {
            net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler inventoryHandler = backpackWrapper.getInventoryHandler();
            
            // Clear all slots
            for (int slot = 0; slot < inventoryHandler.getSlots(); slot++) {
                inventoryHandler.setStackInSlot(slot, ItemStack.EMPTY);
            }
            
            // Add a recovery notice item in the first slot
            ItemStack recoveryItem = new ItemStack(net.minecraft.world.item.Items.PAPER);
            recoveryItem.set(net.minecraft.core.component.DataComponents.ITEM_NAME,
                net.minecraft.network.chat.Component.literal("§6Backpack Recovery Notice")
                    .setStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.GOLD).withItalic(true)));
            
            CompoundTag customData = new CompoundTag();
            customData.putString("playersync:recovery_info", "Backpack restoration failed - empty backup created");
            customData.putString("playersync:original_uuid", contentsUuid.toString());
            net.minecraft.world.item.component.CustomData.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, recoveryItem, customData);
            
            inventoryHandler.setStackInSlot(0, recoveryItem);
            
            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                PlayerSync.LOGGER.info("[BACKPACK] Created empty fallback backpack for uuid={}", contentsUuid);
            }
            
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Failed to create empty backpack fallback for UUID " + contentsUuid, e);
        }
    }

    /**
     * Sanitizes backpack NBT string to fix common corruption issues that prevent parsing
     */
    private String sanitizeBackpackNbtString(String nbtString) {
        if (nbtString == null || nbtString.isEmpty()) {
            return nbtString;
        }

        String sanitized = nbtString;
        boolean wasModified = false;

        if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
            PlayerSync.LOGGER.info("[BACKPACK] SANITIZE input len={} preview={}", nbtString.length(), 
                nbtString.substring(0, Math.min(200, nbtString.length())) + (nbtString.length() > 200 ? "..." : ""));
        }

        // Fix Apotheosis empty key-value pairs: {"":""}
        String emptyKvPattern = "\\{\"\":\"\"\\}";
        if (sanitized.contains("{\"\":\"\"}")) {
            sanitized = sanitized.replaceAll(",\\s*" + emptyKvPattern, ""); // Remove if not first
            sanitized = sanitized.replaceAll(emptyKvPattern + "\\s*,", ""); // Remove if first  
            sanitized = sanitized.replaceAll(emptyKvPattern, ""); // Remove if only element
            wasModified = true;
        }

        // Fix Draconic Evolution config_properties corruption: {"": "DECIMAL"} -> {"type": "DECIMAL"}
        if (sanitized.contains("draconicevolution:config_properties")) {
            String draconicPattern = "\\{\"\":\\s*\"(DECIMAL|INTEGER|STRING)\"\\s*\\}";
            if (sanitized.matches(".*" + draconicPattern + ".*")) {
                sanitized = sanitized.replaceAll(draconicPattern, "{\"type\": \"$1\"}");
                wasModified = true;
            }
        }

        // Fix orphaned commas that might result from the cleanup
        sanitized = sanitized.replaceAll(",\\s*,", ","); // Double commas
        sanitized = sanitized.replaceAll("\\[\\s*,", "["); // Leading comma in arrays
        sanitized = sanitized.replaceAll(",\\s*\\]", "]"); // Trailing comma in arrays
        sanitized = sanitized.replaceAll("\\{\\s*,", "{"); // Leading comma in objects
        sanitized = sanitized.replaceAll(",\\s*\\}", "}"); // Trailing comma in objects

        if (wasModified && (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get())) {
            PlayerSync.LOGGER.info("[BACKPACK] SANITIZED len={} -> {} changes applied", 
                nbtString.length(), sanitized.length());
        }

        return sanitized;
    }

    /**
     * Restores the Curios inventory for a player.
     * Uses the same robust approach as main inventory - individual item error handling.
     */
    public void doCuriosRestore(Player player) throws SQLException {
        if (ModList.get().isLoaded("curios")) {
            Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
            JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery("SELECT curios_item FROM curios WHERE uuid = '" + player.getUUID() + "'");
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
                String curiosData = rs.getString("curios_item");
                Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);
                
                // Clear current Curios slots to avoid conflicts
                handlerOpt.ifPresent(handler -> handler.getCurios().forEach((slotType, stacksHandler) -> {
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

                // Restore each saved item using the same robust approach as main inventory
                handlerOpt.ifPresent(handler -> {
                    for (Map.Entry<String, String> entry : storedMap.entrySet()) {
                        String compositeKey = entry.getKey();
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
                            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_CURIOS.get()) {
                                PlayerSync.LOGGER.info("[DEBUG] Deserializing Curio item for slot {}, data length: {}", 
                                    compositeKey, serialized.length());
                            }
                            
                            String nbtString = VanillaSync.deserializeString(serialized);
                            CompoundTag tag = NbtUtils.snbtToStructure(nbtString);
                            
                            // Use the same robust deserialization as main inventory
                            ItemStack stack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(serialized);
                            
                            if (handler.getCurios().containsKey(slotType)) {
                                ICurioStacksHandler stacksHandler = handler.getCurios().get(slotType);
                                IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                                if (slotIndex < dynStacks.getSlots()) {
                                    dynStacks.setStackInSlot(slotIndex, stack);
                                }
                            }
                        } catch (Exception e) {
                            if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_CURIOS.get()) {
                                PlayerSync.LOGGER.error("[DEBUG] Curio deserialization error for {}: {} - Error type: {}", 
                                    compositeKey, e.getMessage(), e.getClass().getSimpleName());
                            }
                            PlayerSync.LOGGER.error("Error deserializing Curio data for key " + compositeKey + ". Skipping this item to prevent inventory loss.", e);
                            
                            // Save failed Curios data for analysis
                            FailedItemLogger.saveFailedCurioItem(player.getUUID(), slotType, slotIndex, serialized, 
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                            
                            // Skip this item instead of crashing - this preserves other items in the inventory
                        }
                    }
                });
                rs.close();
                qr.connection().close();
            } else {
                // No stored data; perform an initial save
                StoreCurios(player, true);
            }
        }
    }

    /**
     * Saves the current Curios inventory for a player.
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


    /**
     * Simple backpack storage - just store what's there without trying to "fix" anything
     */
    public static void storeSophisticatedBackpacks(Player player) {
        if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Starting backpack storage for player {}", player.getUUID());
        } else {
            PlayerSync.LOGGER.info("Storing backpack data for player " + player.getUUID());
        }
        
        net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider.get().runOnBackpacks(player, (ItemStack backpackItem, String handler, String identifier, int slot) -> {
            net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper backpackWrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper
                    .fromStack(backpackItem);

            Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
            if (uuidOpt.isPresent()) {
                UUID contentsUuid = uuidOpt.get();
                
                if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Storing backpack with contents UUID: {} in slot {}", contentsUuid, slot);
                }
                
                // Simple storage: Get backpack data and store it directly
                CompoundTag backpackNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                
                
                String serialized = VanillaSync.serialize(backpackNbt.toString());
                try {
                    JDBCsetUp.executeUpdate("REPLACE INTO backpack_data (uuid, backpack_nbt) VALUES ('" + contentsUuid + "', '" + serialized + "')");
                    
                    if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] Saved backpack data for UUID {}, serialized size: {} bytes", contentsUuid, serialized.length());
                    } else {
                        PlayerSync.LOGGER.info("Saved backpack data for UUID " + contentsUuid);
                    }
                } catch (SQLException e) {
                    if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                        PlayerSync.LOGGER.error("[DEBUG] Database error saving backpack UUID {}: {}", contentsUuid, e.getMessage());
                    }
                    PlayerSync.LOGGER.error("Error saving backpack data for UUID " + contentsUuid, e);
                }
            } else {
                if (JdbcConfig.DEBUG_MODE.get() || JdbcConfig.DEBUG_BACKPACKS.get()) {
                    PlayerSync.LOGGER.warn("[DEBUG] Backpack item in slot {} has no contentsUuid for player {}", slot, player.getUUID());
                } else {
                    PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid");
                }
            }
            return false;
        });
    }

    // FTB Quests integration temporarily removed - to be implemented in future version
}