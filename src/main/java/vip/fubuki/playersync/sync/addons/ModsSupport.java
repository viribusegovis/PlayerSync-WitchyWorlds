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
    /**
     * Simple backpack restoration - let Sophisticated Backpacks handle its own item parsing
     * rather than trying to "fix" corrupted NBT. The mod has its own error handling.
     */
    public void doBackPackRestore(Player player) {
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            if (JdbcConfig.DEBUG_MODE.get()) {
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
                    
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[BACKPACK] PROC uuid={} slot={}", contentsUuid, slot);
                    }
                    
                    try {
                        JDBCsetUp.QueryResult qrBackpack = JDBCsetUp.executeQuery("SELECT backpack_nbt FROM backpack_data WHERE uuid='" + contentsUuid + "'");
                        ResultSet rsBackpack = qrBackpack.resultSet();
                        if (rsBackpack.next()) {
                            String serialized = rsBackpack.getString("backpack_nbt");
                            
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.info("[BACKPACK] FOUND uuid={} len={}", contentsUuid, serialized != null ? serialized.length() : 0);
                            }
                            
                            try {
                                String nbtString = VanillaSync.deserializeString(serialized);
                                
                                // CRITICAL: Sanitize the backpack NBT before parsing to fix common corruption issues
                                nbtString = sanitizeBackpackNbtString(nbtString);
                                
                                CompoundTag backpackNbt = NbtUtils.snbtToStructure(nbtString);
                                
                                // Enhanced approach: Process backpack contents with individual item protection
                                // This prevents single corrupted items from wiping entire backpack contents
                                CompoundTag safeBackpackNbt = processBackpackContentsWithIndividualItemProtection(backpackNbt);
                                
                                net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, safeBackpackNbt);
                                
                                if (JdbcConfig.DEBUG_MODE.get()) {
                                    PlayerSync.LOGGER.info("[BACKPACK] RESTORED uuid={} with item protection", contentsUuid);
                                } else {
                                    PlayerSync.LOGGER.info("Restored backpack data for UUID " + contentsUuid);
                                }
                                
                            } catch (CommandSyntaxException e) {
                                if (JdbcConfig.DEBUG_MODE.get()) {
                                    PlayerSync.LOGGER.error("[BACKPACK] NBT parse FAIL uuid={} err={} data={}", contentsUuid, e.getMessage(), serialized.substring(0, Math.min(100, serialized.length())));
                                }
                                PlayerSync.LOGGER.error("Error parsing backpack NBT for UUID " + contentsUuid + ". Attempting recovery with individual item processing.", e);
                                
                                // Try to recover individual items even when full NBT parsing fails
                                CompoundTag recoveredNbt = attemptBackpackItemRecovery(serialized, contentsUuid);
                                net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().setBackpackContents(contentsUuid, recoveredNbt);
                            }
                        }
                        rsBackpack.close();
                        qrBackpack.connection().close();
                    } catch (SQLException e) {
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            PlayerSync.LOGGER.error("[DEBUG] Database error for backpack UUID {}: {}", contentsUuid, e.getMessage());
                        }
                        PlayerSync.LOGGER.error("Error restoring backpack data for UUID " + contentsUuid, e);
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
        }
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
                            if (JdbcConfig.DEBUG_MODE.get()) {
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
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.error("[DEBUG] Curio deserialization error for {}: {} - Error type: {}", 
                                    compositeKey, e.getMessage(), e.getClass().getSimpleName());
                            }
                            PlayerSync.LOGGER.error("Error deserializing Curio data for key " + compositeKey + ". Skipping this item to prevent inventory loss.", e);
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
     * Processes backpack contents with individual item protection to prevent
     * single corrupted items from wiping entire backpack contents.
     */
    private CompoundTag processBackpackContentsWithIndividualItemProtection(CompoundTag originalBackpackNbt) {
        CompoundTag safeBackpackNbt = originalBackpackNbt.copy();
        
        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Processing backpack contents with individual item protection");
        }
        
        // Process inventory structure if it exists
        if (safeBackpackNbt.contains("inventory")) {
            CompoundTag inventoryTag = safeBackpackNbt.getCompound("inventory");
            safeBackpackNbt.put("inventory", processBackpackInventoryItems(inventoryTag));
        }
        
        // Also process direct Items array (some backpack versions)
        if (safeBackpackNbt.contains("Items")) {
            safeBackpackNbt = processBackpackInventoryItems(safeBackpackNbt);
        }
        
        return safeBackpackNbt;
    }
    
    /**
     * Safely processes individual items in backpack inventory, replacing corrupted ones with placeholders
     */
    private CompoundTag processBackpackInventoryItems(CompoundTag inventoryTag) {
        CompoundTag safeInventoryTag = inventoryTag.copy();
        
        if (safeInventoryTag.contains("Items")) {
            var itemsTag = safeInventoryTag.get("Items");
            if (itemsTag instanceof net.minecraft.nbt.ListTag itemsList) {
                net.minecraft.nbt.ListTag safeItemsList = new net.minecraft.nbt.ListTag();
                
                int successfulItems = 0;
                int placeholderItems = 0;
                
                for (int i = 0; i < itemsList.size(); i++) {
                    var itemTag = itemsList.get(i);
                    if (itemTag instanceof CompoundTag itemCompound) {
                        try {
                            // Use the same robust deserialization approach as main inventory
                            // This will either restore the item or create a placeholder
                            String serializedItemData = VanillaSync.serialize(itemCompound.toString());
                            ItemStack testStack = VanillaSync.deserializeAndCreatePlaceholderIfNeeded(serializedItemData);
                            
                            // Convert the resulting ItemStack back to NBT for storage
                            CompoundTag resultItemTag = (CompoundTag) testStack.saveOptional(
                                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess()
                            );
                            
                            // Preserve slot information from original
                            if (itemCompound.contains("Slot")) {
                                resultItemTag.putByte("Slot", itemCompound.getByte("Slot"));
                            }
                            
                            safeItemsList.add(resultItemTag);
                            
                            // Check if this became a placeholder
                            boolean isPlaceholder = resultItemTag.contains("components") && 
                                resultItemTag.getCompound("components").contains("minecraft:custom_data") &&
                                resultItemTag.getCompound("components").getCompound("minecraft:custom_data")
                                    .contains("playersync:original_item_nbt");
                            
                            if (isPlaceholder) {
                                placeholderItems++;
                                if (JdbcConfig.DEBUG_MODE.get()) {
                                    String originalId = itemCompound.contains("id") ? itemCompound.getString("id") : "unknown";
                                    PlayerSync.LOGGER.info("[DEBUG] Backpack item {} at index {} became placeholder due to corruption", originalId, i);
                                }
                            } else {
                                successfulItems++;
                            }
                            
                        } catch (Exception e) {
                            if (JdbcConfig.DEBUG_MODE.get()) {
                                PlayerSync.LOGGER.warn("[DEBUG] Failed to process backpack item at index {}: {}. Skipping item.", i, e.getMessage());
                            }
                            // Skip this item entirely if even placeholder creation fails
                            placeholderItems++;
                        }
                    } else {
                        // Non-compound tags, just copy as-is
                        safeItemsList.add(itemTag.copy());
                    }
                }
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Backpack processing complete: {} items restored, {} became placeholders", 
                        successfulItems, placeholderItems);
                }
                
                safeInventoryTag.put("Items", safeItemsList);
            }
        }
        
        return safeInventoryTag;
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

        if (JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[BACKPACK] SANITIZE input len={} preview={}", nbtString.length(), 
                nbtString.substring(0, Math.min(200, nbtString.length())) + (nbtString.length() > 200 ? "..." : ""));
        }

        // Fix Apotheosis empty key-value pairs: {"":""}
        String emptyKvPattern = "\\{\"\":\"\"\\}";
        if (sanitized.matches(".*" + emptyKvPattern + ".*")) {
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

        if (wasModified && JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[BACKPACK] SANITIZED len={} -> {} changes applied", 
                nbtString.length(), sanitized.length());
        }

        return sanitized;
    }

    /**
     * Attempts to recover individual items when full backpack NBT parsing fails
     */
    private CompoundTag attemptBackpackItemRecovery(String serializedData, UUID contentsUuid) {
        try {
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Attempting individual item recovery for backpack UUID {}", contentsUuid);
            }
            
            // Create minimal valid backpack structure
            CompoundTag recoveredNbt = new CompoundTag();
            CompoundTag inventory = new CompoundTag();
            net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
            
            // Create recovery notice item using proper ItemStack APIs
            ItemStack recoveryItemStack = new ItemStack(net.minecraft.world.item.Items.PAPER);
            
            // Set custom data
            CompoundTag customData = new CompoundTag();
            customData.putString("playersync:recovery_info", "Backpack had corrupted NBT structure");
            customData.putString("playersync:original_data_size", String.valueOf(serializedData.length()));
            customData.putString("playersync:unique_id", java.util.UUID.randomUUID().toString());
            net.minecraft.world.item.component.CustomData.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, recoveryItemStack, customData);
            
            // Set item name
            recoveryItemStack.set(net.minecraft.core.component.DataComponents.ITEM_NAME,
                net.minecraft.network.chat.Component.literal("§6Backpack Recovery Notice")
                    .setStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.GOLD).withItalic(true)));
            
            // Convert to NBT for backpack storage
            CompoundTag recoveryItem = (CompoundTag) recoveryItemStack.saveOptional(
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess()
            );
            recoveryItem.putByte("Slot", (byte) 0);
            
            items.add(recoveryItem);
            
            inventory.put("Items", items);
            recoveredNbt.put("inventory", inventory);
            
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Created recovery backpack with notice item for UUID {}", contentsUuid);
            }
            
            return recoveredNbt;
            
        } catch (Exception e) {
            if (JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.error("[DEBUG] Recovery attempt failed for UUID {}: {}", contentsUuid, e.getMessage());
            }
            // Return empty backpack as last resort
            CompoundTag emptyNbt = new CompoundTag();
            CompoundTag inventory = new CompoundTag();
            inventory.put("Items", new net.minecraft.nbt.ListTag());
            emptyNbt.put("inventory", inventory);
            return emptyNbt;
        }
    }

    /**
     * Simple backpack storage - just store what's there without trying to "fix" anything
     */
    public static void storeSophisticatedBackpacks(Player player) {
        if (JdbcConfig.DEBUG_MODE.get()) {
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
                
                if (JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Storing backpack with contents UUID: {} in slot {}", contentsUuid, slot);
                }
                
                // Simple storage: Get backpack data and store it directly
                CompoundTag backpackNbt = net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
                String serialized = VanillaSync.serialize(backpackNbt.toString());
                try {
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
            return false;
        });
    }
}