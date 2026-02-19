package vip.fubuki.playersync.sync;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;
import vip.fubuki.playersync.sync.addons.CuriosCache;
import vip.fubuki.playersync.sync.addons.ModsSupport;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;
import vip.fubuki.playersync.util.PSThreadPoolFactory;
import vip.fubuki.playersync.util.FailedItemLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@EventBusSubscriber(modid = PlayerSync.MODID)
public class VanillaSync {

    public static void register() {}

    static ExecutorService executorService = Executors.newCachedThreadPool(new PSThreadPoolFactory("PlayerSync"));
    private static volatile boolean isShuttingDown = false;

    @SubscribeEvent
    public static void onDataPackSyncEvent(OnDatapackSyncEvent event) throws SQLException, IOException {
        if (!JdbcConfig.SYNC_ADVANCEMENTS.get())
            return; // advancement sync disabled

        final ServerPlayer serverPlayer = event.getPlayer();
        if (serverPlayer == null) {
            PlayerSync.LOGGER.debug("No player joining");
            return;
        }

        final String player_uuid = serverPlayer.getUUID().toString();
        PlayerSync.LOGGER.info("Player entity joining level " + player_uuid);

        JDBCsetUp.QueryResult advancementsQuery = JDBCsetUp
                .executeQuery("SELECT advancements FROM player_data WHERE uuid='" + player_uuid + "'");
        ResultSet advancementsResultSet = advancementsQuery.resultSet();

        if (!advancementsResultSet.next()) {
            PlayerSync.LOGGER.debug("No advancements found for player " + player_uuid);
            advancementsResultSet.close();
            return;
        }

        // Restore Advancements
        Path path = serverPlayer.getServer().getServerDirectory().resolve(getSyncWorldForServer());
        File gameDir = path.toFile();

        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server.isDedicatedServer()) {
            if (JdbcConfig.DEBUG_ACHIEVEMENTS.get()) {
                PlayerSync.LOGGER.info("[ADVANCE] Writing dedicated server advancement file");
            }
            File advancements = new File(gameDir,
                    "/advancements" + "/" + player_uuid + ".json");
            byte[] bytes = advancementsResultSet.getString("advancements").getBytes();
            advancementsResultSet.close();

            // only create advancements file if at least "{}" has been stored in the field
            if (bytes.length < 2) {
                if (JdbcConfig.DEBUG_ACHIEVEMENTS.get()) {
                PlayerSync.LOGGER.info("[ADVANCE] Skip writing advancements for player " + player_uuid);
            }
                return;
            }

            File advancementsDir = advancements.getParentFile();
            if (advancementsDir != null && !advancementsDir.exists()) {
                PlayerSync.LOGGER.info("Creating advancements directory " + advancementsDir.getPath());
                boolean createdDir = advancementsDir.mkdirs();
                if (!createdDir) {
                    PlayerSync.LOGGER.error("Aborting advancements sync. Failed to create advancements "
                            + "directory at " + advancementsDir.getPath());
                    return;
                }
            }

            if (!advancements.exists()) {
                try {
                    if (JdbcConfig.DEBUG_ACHIEVEMENTS.get()) {
                        PlayerSync.LOGGER.info("[ADVANCE] Creating new advancement file for player " + player_uuid);
                    }
                    advancements.createNewFile();
                } catch (IOException e) {
                    PlayerSync.LOGGER.error("Aborting advancements sync. Failed to create advancements file at "
                            + advancements.getAbsolutePath(), e);
                    return;
                }
            }
            if (JdbcConfig.DEBUG_ACHIEVEMENTS.get()) {
                PlayerSync.LOGGER.info("[ADVANCE] Writing advancement file {} for player {}", advancements.toPath(), player_uuid);
                PlayerSync.LOGGER.info("[ADVANCE] Data: {}", new String(bytes, StandardCharsets.UTF_8));
            }
            Files.write(advancements.toPath(), bytes);

            // reload the json files on the server after updating them
            PlayerAdvancements playeradvancements = serverPlayer.getAdvancements();
            playeradvancements.reload(server.getAdvancements());

        } else {
            if (JdbcConfig.DEBUG_ACHIEVEMENTS.get()) {
                PlayerSync.LOGGER.info("[ADVANCE] Writing non-dedicated server advancement files");
            }
            File[] files = scanAdvancementsFile(player_uuid, gameDir);
            for (File file : files) {
                if (file == null)
                    continue;
                byte[] bytes = advancementsResultSet.getString("advancements").getBytes();
                Files.write(file.toPath(), bytes);
            }
            advancementsResultSet.close();
        }
    }

    public static void doPlayerConnect(PlayerNegotiationEvent event) {
        try {
            String player_uuid = event.getProfile().getId().toString();
            PlayerSync.LOGGER.info("Detected connection from player" + player_uuid + ",starting checking");
            boolean online;
            int lastServer;

            // First query: check basic player data and check whether player can join into server.
            JDBCsetUp.QueryResult qr1 = JDBCsetUp.executeQuery("SELECT online, last_server FROM player_data WHERE uuid='" + player_uuid + "'");

            try (ResultSet rs1 = qr1.resultSet()) {
                if (!rs1.next()) {
                    PlayerSync.LOGGER.info("A new-player connection detected");
                    qr1.connection().close();
                    return;
                }
                online = rs1.getBoolean("online");
                lastServer = rs1.getInt("last_server");
                qr1.connection().close();
            }

            // Second query: Check if player is already online on another server
            if (JdbcConfig.KICK_WHEN_ALREADY_ONLINE.get() && online && lastServer != JdbcConfig.SERVER_ID.get()) {
                JDBCsetUp.QueryResult qr2 = JDBCsetUp.executeQuery("SELECT last_update,enable FROM server_info WHERE id='" + lastServer + "'");
                try (ResultSet rs2 = qr2.resultSet()) {
                    if (rs2.next()) {
                        long last_update = rs2.getLong("last_update");
                        boolean enable = rs2.getBoolean("enable");
                        if (enable && System.currentTimeMillis() < last_update + 300000.0) {
                            event.getConnection().disconnect(Component.translatableWithFallback("playersync.already_online","You can't join more than one synchronization server at the same time."));
                            qr2.connection().close();
                            return;
                        }
                        JDBCsetUp.executeUpdate("UPDATE server_info SET enable= '0' WHERE id=" + lastServer);
                    }
                    qr2.connection().close();
                }
            }
        } catch (Exception e) {
            PlayerSync.LOGGER.error("SqlException detected!", e);
            event.getConnection().disconnect(Component.translatableWithFallback("playersync.sqlexception","SqlException detected!Connection lost,please contact with your admin."));
        }
    }

    // Use string uuid as key
    public static Set<String> deadPlayerWhileLogging = ConcurrentHashMap.newKeySet();
    public static Set<String> syncNotCompletedPlayer = ConcurrentHashMap.newKeySet();

    public static void doPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer joinedPlayer = (ServerPlayer) event.getEntity();
        String player_uuid = joinedPlayer.getUUID().toString();
        if (joinedPlayer.isDeadOrDying()) {
            deadPlayerWhileLogging.add(player_uuid);
            joinedPlayer.removeTag("player_synced");

            // Simulate normal death behavior
            MinecraftServer server = joinedPlayer.getServer();
            if (server != null) {
                ResourceKey<Level> respawnLevel = joinedPlayer.getRespawnDimension();
                BlockPos respawnPos = joinedPlayer.getRespawnPosition();
                double respawnX;
                double respawnY;
                double respawnZ;
                if (respawnPos != null && respawnLevel != null) {
                    ServerLevel level = server.getLevel(respawnLevel);
                    respawnX = respawnPos.getX();
                    respawnY = respawnPos.getY();
                    respawnZ = respawnPos.getZ();
                    if (level != null) {
                        joinedPlayer.teleportTo(level, respawnX, respawnY + 1, respawnZ, 0, 0);
                    }
                } else {
                    PlayerSync.LOGGER.debug("Player " + player_uuid + " has no respawn point");
                }
            } else {
                PlayerSync.LOGGER.warn("Trying to get server,but got a null");
            }

            joinedPlayer.setHealth(1);
            try {
                JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
                JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");
            } catch (SQLException e) {
                PlayerSync.LOGGER.error("An error occurred while trying to execute a dead or dying player" + e.getMessage());
            }
            joinedPlayer.connection.disconnect(Component.translatableWithFallback("playersync.wrong_entity_status","An error occurred while creating playerEntity in the world,please login again."));
            return;
        }

        try {
            PlayerSync.LOGGER.info("Starting synchronization for player " + player_uuid);

            // First query: check basic player data
            syncNotCompletedPlayer.add(player_uuid);
            JDBCsetUp.QueryResult qr1 = JDBCsetUp.executeQuery("SELECT online, last_server FROM player_data WHERE uuid='" + player_uuid + "'");
            ResultSet rs1 = qr1.resultSet();
            ServerPlayer serverPlayer = (ServerPlayer) event.getEntity();

            // Mod support - restore addon data early to avoid conflicts
            ModsSupport modsSupport = new ModsSupport();
            modsSupport.doCuriosRestore(serverPlayer);

            if (!rs1.next()) {
                store(event.getEntity(), true);
                JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
                JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");
                rs1.close();
                qr1.close();
                PlayerSync.LOGGER.info("New player detected,init completed.");
                syncNotCompletedPlayer.remove(player_uuid);
                return;
            }

            // Second query: retrieve full player data
            JDBCsetUp.QueryResult qr2 = JDBCsetUp.executeQuery("SELECT * FROM player_data WHERE uuid='" + player_uuid + "'");
            ResultSet rs2 = qr2.resultSet();

            JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
            JDBCsetUp.executeUpdate("UPDATE player_data SET online= '1',last_server=" + JdbcConfig.SERVER_ID.get() + " WHERE uuid='" + player_uuid + "'");

            if (rs2.next()) {
                // Restore basic attributes
                int health = rs2.getInt("health");
                if (health <= 0) {
                    serverPlayer.setHealth(1);
                } else {
                    serverPlayer.setHealth(health);
                }
                serverPlayer.getFoodData().setFoodLevel(rs2.getInt("food_level"));

                setXpForPlayer(serverPlayer, rs2.getInt("xp"));
                serverPlayer.setScore(rs2.getInt("score"));

                // Restore left-hand item
                String leftHandEncoded = rs2.getString("left_hand");
                ItemStack leftHandItem = deserializeAndCreatePlaceholderIfNeeded(leftHandEncoded);
                if (leftHandItem.is(Items.PAPER) && leftHandItem.getComponents().has(DataComponents.CUSTOM_DATA)
                        && leftHandItem.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
                    // Log failed item restoration
                    String originalItemId = leftHandItem.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_id");
                    FailedItemLogger.saveFailedItem(java.util.UUID.fromString(player_uuid), "left_hand", leftHandEncoded, "Item deserialization failed - created placeholder", "Original item: " + originalItemId);
                }
                serverPlayer.setItemInHand(InteractionHand.OFF_HAND, leftHandItem);

                // Restore cursor item
                String cursorsEncoded = rs2.getString("cursors");
                ItemStack cursorItem = deserializeAndCreatePlaceholderIfNeeded(cursorsEncoded);
                if (cursorItem.is(Items.PAPER) && cursorItem.getComponents().has(DataComponents.CUSTOM_DATA)
                        && cursorItem.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
                    // Log failed item restoration
                    String originalItemId = cursorItem.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_id");
                    FailedItemLogger.saveFailedItem(java.util.UUID.fromString(player_uuid), "cursor", cursorsEncoded, "Item deserialization failed - created placeholder", "Original item: " + originalItemId);
                }
                serverPlayer.containerMenu.setCarried(cursorItem);

                // Restore armor
                String armor_data = rs2.getString("armor");
                if (armor_data.length() > 2) {
                    Map<Integer, String> equipment = LocalJsonUtil.StringToEntryMap(armor_data);
                    for (Map.Entry<Integer, String> entry : equipment.entrySet()) {
                        ItemStack armorItem = deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                        if (armorItem.is(Items.PAPER) && armorItem.getComponents().has(DataComponents.CUSTOM_DATA)
                                && armorItem.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
                            // Log failed item restoration
                            String originalItemId = armorItem.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_id");
                            FailedItemLogger.saveFailedItem(java.util.UUID.fromString(player_uuid), "armor", entry.getValue(), "Item deserialization failed - created placeholder", "Armor slot: " + entry.getKey() + ", Original item: " + originalItemId);
                        }
                        serverPlayer.getInventory().armor.set(entry.getKey(), armorItem);
                    }
                }

                // Restore inventory
                Map<Integer, String> inventory = LocalJsonUtil.StringToEntryMap(rs2.getString("inventory"));
                for (Map.Entry<Integer, String> entry : inventory.entrySet()) {
                    ItemStack inventoryItem = deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                    if (inventoryItem.is(Items.PAPER) && inventoryItem.getComponents().has(DataComponents.CUSTOM_DATA)
                            && inventoryItem.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
                        // Log failed item restoration
                        String originalItemId = inventoryItem.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_id");
                        FailedItemLogger.saveFailedInventoryItem(java.util.UUID.fromString(player_uuid), entry.getValue(), "Item deserialization failed - created placeholder", entry.getKey());
                    }
                    serverPlayer.getInventory().setItem(entry.getKey(), inventoryItem);
                }

                // Restore Ender Chest
                Map<Integer, String> ender_chest = LocalJsonUtil.StringToEntryMap(rs2.getString("enderchest"));
                for (Map.Entry<Integer, String> entry : ender_chest.entrySet()) {
                    ItemStack enderItem = deserializeAndCreatePlaceholderIfNeeded(entry.getValue());
                    if (enderItem.is(Items.PAPER) && enderItem.getComponents().has(DataComponents.CUSTOM_DATA)
                            && enderItem.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
                        // Log failed item restoration
                        String originalItemId = enderItem.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_id");
                        FailedItemLogger.saveFailedItem(java.util.UUID.fromString(player_uuid), "enderchest", entry.getValue(), "Item deserialization failed - created placeholder", "Slot: " + entry.getKey() + ", Original item: " + originalItemId);
                    }
                    serverPlayer.getEnderChestInventory().setItem(entry.getKey(), enderItem);
                }

                // Restore Effects
                String effectData = rs2.getString("effects");
                if (effectData.length() > 2) {
                    serverPlayer.removeAllEffects();
                    Map<Integer, String> effects = LocalJsonUtil.StringToEntryMap(effectData);
                    for (Map.Entry<Integer, String> entry : effects.entrySet()) {
                        CompoundTag effectTag = NbtUtils.snbtToStructure(deserializeString(entry.getValue()));
                        MobEffectInstance mobEffectInstance = MobEffectInstance.load(effectTag);
                        if (mobEffectInstance != null) {
                            serverPlayer.addEffect(mobEffectInstance);
                        }
                    }
                }
            }

            modsSupport.doBackPackRestore(serverPlayer);

            serverPlayer.addTag("player_synced");

            rs2.close();
            qr2.close();
            rs1.close();
            qr1.close();
            PlayerSync.LOGGER.info("Sync data for player {} completed.", player_uuid);
            
            // Create summary of any failed items for this player
            FailedItemLogger.createPlayerSummary(java.util.UUID.fromString(player_uuid));
            
            syncNotCompletedPlayer.remove(player_uuid);
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Internal Exception detected!", e);
            syncNotCompletedPlayer.remove(player_uuid);
        }
    }

    @SubscribeEvent
    public static void onPlayerConnect(PlayerNegotiationEvent event) {
        executorService.submit(() -> {
            try {
                doPlayerConnect(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        executorService.submit(() -> {
            try {
                doPlayerJoin(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // deserialize item and potentially create placeholders
    public static ItemStack deserializeAndCreatePlaceholderIfNeeded(String serializedNbt) {
        if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[ITEM] DESER len={} data={}", serializedNbt.length(), serializedNbt.substring(0, Math.min(100, serializedNbt.length())) + (serializedNbt.length() > 100 ? "..." : ""));
        }
        
        if (serializedNbt == null || serializedNbt.isEmpty() || serializedNbt.equals("B64:e30=")) {
            // Check for empty NBT (Base64 encoded '{}')
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[ITEM] EMPTY nbt, return ItemStack.EMPTY");
            }
            return ItemStack.EMPTY;
        }

        String nbtString;
        CompoundTag compoundTag;
        try {
            nbtString = deserializeString(serializedNbt);
            
            // Sanitize NBT string to fix known corruption issues (especially Apotheosis empty key-value pairs)
            nbtString = sanitizeNbtString(nbtString);
            
            compoundTag = NbtUtils.snbtToStructure(nbtString);
            
            // Extract count and other information from parsed NBT for debugging
            int parsedCount = 1;
            if (compoundTag.contains("count")) {
                parsedCount = compoundTag.getInt("count");
            } else if (compoundTag.contains("Count")) {
                parsedCount = compoundTag.getInt("Count");
            }
            
            String itemId = compoundTag.contains("id") ? compoundTag.getString("id") : "NO_ID";
            
            // Check for Forbidden Arcanus items during deserialization
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                boolean isForbiddenArcanus = itemId.contains("forbidden") || itemId.contains("arcanus");
                if (isForbiddenArcanus) {
                    PlayerSync.LOGGER.info("[STELLA_DEBUG] Deserializing F&A item: {} count: {}", itemId, parsedCount);
                    
                    // Check if unbreakable is present in the parsed NBT
                    String fullNbtString = compoundTag.toString();
                    boolean hasUnbreakableInParsedNbt = fullNbtString.contains("\"minecraft:unbreakable\"") || fullNbtString.contains("Unbreakable");
                    PlayerSync.LOGGER.info("[STELLA_DEBUG] Parsed NBT contains unbreakable: {}", hasUnbreakableInParsedNbt);
                }
            }
            
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[ITEM] NBT parsed OK, id={}", compoundTag.contains("id") ? compoundTag.getString("id") : "NO_ID");
            }
        } catch (CommandSyntaxException e) {
            PlayerSync.LOGGER.error("Failed to parse NBT structure from serialized data: {}. Creating placeholder item.", serializedNbt, e);
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[ITEM] NBT parse FAIL - Error: {} Data: {}", e.getMessage(), serializedNbt.substring(0, Math.min(200, serializedNbt.length())));
            }
            return createPlaceholderItem(serializedNbt, "unknown", 1);
        }

        if (compoundTag.isEmpty() || !compoundTag.contains("id", Tag.TAG_STRING)) {
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] CompoundTag is empty or missing 'id' field, returning ItemStack.EMPTY");
            }
            return ItemStack.EMPTY; // Invalid or empty tag
        }

        ResourceLocation registryName = ResourceLocation.tryParse(compoundTag.getString("id"));

        if (registryName == null) {
            PlayerSync.LOGGER.warn("Failed to parse registry name from NBT: {}", nbtString);
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Registry name parse failure for item ID: {}", compoundTag.getString("id"));
            }
            return ItemStack.EMPTY; // Cannot determine item type
        }

        if (BuiltInRegistries.ITEM.containsKey(registryName)) {
            // Item exists (could be vanilla or a loaded mod item), restore normally
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Item {} found in registry, attempting restoration", registryName);
            }
            
            // Try to create the ItemStack with fallback handling
            ItemStack restoredItem = createItemStackWithFallback(compoundTag, registryName);
            
            // Only return the restored item if it's not unexpectedly empty
            if (!restoredItem.isEmpty() || compoundTag.isEmpty()
                    || registryName.equals(ResourceLocation.tryParse("air"))) {
                if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Successfully restored item: {} (count: {})", 
                        registryName, restoredItem.getCount());
                }
                
                // Log detailed Apotheosis item info
                logApothosisDebugInfo("ITEM_DESERIALIZATION_SUCCESS", restoredItem, 
                    "Restored from NBT: " + nbtString.substring(0, Math.min(100, nbtString.length())));
                
                // Enhanced debugging for final restored ItemStack, especially for Forbidden Arcanus
                if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                    String finalItemId = restoredItem.getItem().toString();
                    boolean isForbiddenArcanus = finalItemId.contains("forbidden") || finalItemId.contains("arcanus");
                    
                    if (isForbiddenArcanus) {
                        PlayerSync.LOGGER.info("[STELLA_DEBUG] Final restored F&A ItemStack: item={}, count={}", 
                            restoredItem.getItem(), restoredItem.getCount());
                        
                        // Check if the final item still has unbreakable component
                        boolean hasFinalUnbreakable = restoredItem.has(DataComponents.UNBREAKABLE);
                        PlayerSync.LOGGER.info("[STELLA_DEBUG] Final ItemStack has unbreakable component: {}", hasFinalUnbreakable);
                        
                        // Check custom data component in final item
                        if (restoredItem.has(DataComponents.CUSTOM_DATA)) {
                            CompoundTag finalCustomData = restoredItem.get(DataComponents.CUSTOM_DATA).copyTag();
                            String customDataStr = finalCustomData.toString();
                            boolean hasFACustomData = customDataStr.contains("forbidden") || 
                                                    customDataStr.contains("arcanus") || 
                                                    customDataStr.contains("stella") || 
                                                    customDataStr.contains("eternal");
                            PlayerSync.LOGGER.info("[STELLA_DEBUG] Final ItemStack has F&A custom data: {} (data: {})", 
                                hasFACustomData, customDataStr.length() > 100 ? customDataStr.substring(0, 100) + "..." : customDataStr);
                        } else {
                            PlayerSync.LOGGER.warn("[STELLA_DEBUG] Final F&A ItemStack has NO custom data component!");
                        }
                    }
                }
                
                return restoredItem;
            }
            
            // ItemStack creation unexpectedly returned empty for a known, non-air item.
            PlayerSync.LOGGER.warn(
                    "ItemStack creation returned EMPTY for known item {} with NBT: {}. Creating placeholder as fallback.",
                    registryName, nbtString);
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Known item {} unexpectedly returned empty - creating placeholder", registryName);
            }
        }

        // Create placeholder
        PlayerSync.LOGGER.debug("Item {} not found in registry. Creating placeholder.", registryName);
        if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Item {} not found in registry, mod may be missing. Creating placeholder item.", registryName);
        }
        int placeholderItemAmount = compoundTag.getInt("Count");
        if (placeholderItemAmount <= 0) placeholderItemAmount = 1; // Default to 1 if count is invalid
        return createPlaceholderItem(serializedNbt, registryName.toString(), placeholderItemAmount);
    }

    /**
     * Attempts to create an ItemStack using a more lenient approach when ItemStack.parse fails
     */
    private static ItemStack createItemStackWithFallback(CompoundTag compoundTag, ResourceLocation registryName) {
        // Extract count info for debugging
        int nbtCount = 1;
        if (compoundTag.contains("count")) {
            nbtCount = compoundTag.getInt("count");
        } else if (compoundTag.contains("Count")) {
            nbtCount = compoundTag.getInt("Count");
        }
        
        
        // CRITICAL FIX: Check if count exceeds vanilla max stack size
        // If so, bypass ItemStack.parse() which silently resets high counts to 1
        Item item = BuiltInRegistries.ITEM.get(registryName);
        if (nbtCount > item.getDefaultMaxStackSize()) {
            // Skip ItemStack.parse() entirely and create manually to preserve high counts
            // This will go directly to the fallback creation
        } else {
            try {
                // First attempt: Try the standard ItemStack.parse method for normal counts
                HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
                ItemStack parsed = ItemStack.parse(provider, compoundTag).orElse(ItemStack.EMPTY);
                
                
                return parsed;
            } catch (Exception e) {
                // ItemStack.parse failed, fall back to manual creation
            }
        }
        
        // If we reach here, either count was too high or ItemStack.parse() failed
        // Use manual fallback creation
        
        try {
            // Fallback: Create ItemStack manually and apply custom data
            ItemStack fallbackStack = new ItemStack(BuiltInRegistries.ITEM.get(registryName));
            
            // Set count
            if (compoundTag.contains("count", Tag.TAG_INT)) {
                int countValue = compoundTag.getInt("count");
                fallbackStack.setCount(countValue);
            } else if (compoundTag.contains("Count", Tag.TAG_INT)) {
                int countValue = compoundTag.getInt("Count");
                fallbackStack.setCount(countValue);
            }
            
            // Handle components (new format)
            if (compoundTag.contains("components", Tag.TAG_COMPOUND)) {
                CompoundTag componentsTag = compoundTag.getCompound("components");
                
                // Apply custom_data component if present
                if (componentsTag.contains("minecraft:custom_data", Tag.TAG_COMPOUND)) {
                    CompoundTag customData = componentsTag.getCompound("minecraft:custom_data");
                    CustomData.set(DataComponents.CUSTOM_DATA, fallbackStack, customData);
                    
                    if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                        PlayerSync.LOGGER.info("[DEBUG] Applied custom_data component to fallback {}: {}", 
                            registryName, customData.toString());
                    }
                }
            }
            
            // Handle legacy tag format (for backward compatibility)
            if (compoundTag.contains("tag", Tag.TAG_COMPOUND)) {
                CompoundTag legacyTag = compoundTag.getCompound("tag");
                CustomData.set(DataComponents.CUSTOM_DATA, fallbackStack, legacyTag);
                
                if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[DEBUG] Applied legacy tag to fallback {}: {}", 
                        registryName, legacyTag.toString());
                }
            }
            
            // Enhanced debugging for fallback ItemStack, especially for Forbidden Arcanus
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                String fallbackItemId = fallbackStack.getItem().toString();
                boolean isForbiddenArcanus = fallbackItemId.contains("forbidden") || fallbackItemId.contains("arcanus");
                
                if (isForbiddenArcanus) {
                    PlayerSync.LOGGER.info("[STELLA_DEBUG] Fallback F&A ItemStack: item={}, count={}", 
                        fallbackStack.getItem(), fallbackStack.getCount());
                    
                    // Check if the fallback item has unbreakable component
                    boolean hasFallbackUnbreakable = fallbackStack.has(DataComponents.UNBREAKABLE);
                    PlayerSync.LOGGER.info("[STELLA_DEBUG] Fallback ItemStack has unbreakable component: {}", hasFallbackUnbreakable);
                    
                    // Check custom data component in fallback item
                    if (fallbackStack.has(DataComponents.CUSTOM_DATA)) {
                        CompoundTag fallbackCustomData = fallbackStack.get(DataComponents.CUSTOM_DATA).copyTag();
                        String customDataStr = fallbackCustomData.toString();
                        boolean hasFACustomData = customDataStr.contains("forbidden") || 
                                                customDataStr.contains("arcanus") || 
                                                customDataStr.contains("stella") || 
                                                customDataStr.contains("eternal");
                        PlayerSync.LOGGER.info("[STELLA_DEBUG] Fallback ItemStack has F&A custom data: {} (data: {})", 
                            hasFACustomData, customDataStr.length() > 100 ? customDataStr.substring(0, 100) + "..." : customDataStr);
                    } else {
                        PlayerSync.LOGGER.warn("[STELLA_DEBUG] Fallback F&A ItemStack has NO custom data component!");
                    }
                }
            }
            
            return fallbackStack;
        } catch (Exception fallbackException) {
            PlayerSync.LOGGER.error("Fallback ItemStack creation also failed for {}: {}", 
                registryName, fallbackException.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /**
     * Creates a placeholder item when NBT parsing fails or item is not found
     */
    private static ItemStack createPlaceholderItem(String serializedNbt, String itemId, int count) {
        ItemStack placeholder = new ItemStack(Items.PAPER);
        
        // Set the correct count on the placeholder item to preserve original stack size
        placeholder.setCount(Math.max(count, 1));

        CompoundTag placeholderNbt = new CompoundTag();
        placeholderNbt.putString("playersync:original_item_nbt", serializedNbt);
        placeholderNbt.putString("playersync:original_item_id", itemId);
        placeholderNbt.putInt("playersync:original_count", count); // Store original count for restoration
        placeholderNbt.putUUID("playersync:unique_id", UUID.randomUUID());

        CustomData.set(DataComponents.CUSTOM_DATA, placeholder, placeholderNbt);

        String placeholderItemTitleOverride = JdbcConfig.ITEM_PLACEHOLDER_TITLE_OVERRIDE.get();
        placeholder.set(DataComponents.ITEM_NAME,
                Component
                        .literal(placeholderItemTitleOverride != null && !placeholderItemTitleOverride.isBlank()
                                ? placeholderItemTitleOverride
                                : Component.translatable("playersync.item_placeholder_title").getString())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withItalic(true)));

        List<Component> loreList = new ArrayList<>();
        String placeholderItemDetails = count > 1 ? count + "x " + itemId : itemId;

        loreList.add(
                Component.literal(placeholderItemDetails)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)));
        loreList.add(Component.literal(""));

        String placeholderItemDescriptionOverride = JdbcConfig.ITEM_PLACEHOLDER_DESCRIPTION_OVERRIDE.get();
        String placeholderItemDescriptionLines = placeholderItemDescriptionOverride != null && !placeholderItemDescriptionOverride.isBlank()
                ? placeholderItemDescriptionOverride
                : Component.translatable("playersync.item_placeholder_description").getString();

        for (String descriptionLine : placeholderItemDescriptionLines.split("\n")) {
            loreList.add(
                    Component.literal(descriptionLine)
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }

        placeholder.set(DataComponents.LORE, new ItemLore(loreList));
        return placeholder;
    }

    /**
     * Logs comprehensive debugging information for Apotheosis items
     */
    private static void logApothosisDebugInfo(String operation, ItemStack itemStack, String additionalInfo) {
        if (!vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
            return;
        }
        
        String itemId = itemStack.getItem().toString();
        boolean isApothItem = itemId.contains("apotheosis") || 
                            (itemStack.has(DataComponents.CUSTOM_DATA) && 
                             itemStack.get(DataComponents.CUSTOM_DATA).copyTag().toString().contains("apotheosis"));
        
        if (isApothItem) {
            PlayerSync.LOGGER.info("=== APOTHEOSIS DEBUG: {} ===", operation);
            PlayerSync.LOGGER.info("Item ID: {}", itemId);
            PlayerSync.LOGGER.info("Item Count: {}", itemStack.getCount());
            PlayerSync.LOGGER.info("Has Custom Data: {}", itemStack.has(DataComponents.CUSTOM_DATA));
            
            if (itemStack.has(DataComponents.CUSTOM_DATA)) {
                CompoundTag customData = itemStack.get(DataComponents.CUSTOM_DATA).copyTag();
                PlayerSync.LOGGER.info("Custom Data: {}", customData.toString());
                
                // Check for specific Apotheosis keys
                if (customData.contains("apotheosis:rarity")) {
                    PlayerSync.LOGGER.info("Apotheosis Rarity: {}", customData.getString("apotheosis:rarity"));
                }
                if (customData.contains("apotheosis:affix_data")) {
                    PlayerSync.LOGGER.info("Apotheosis Affix Data: {}", customData.getCompound("apotheosis:affix_data"));
                }
                // Check for legacy format
                if (customData.contains("affix_data")) {
                    PlayerSync.LOGGER.info("Legacy Affix Data: {}", customData.getCompound("affix_data"));
                }
            }
            
            if (additionalInfo != null && !additionalInfo.isEmpty()) {
                PlayerSync.LOGGER.info("Additional Info: {}", additionalInfo);
            }
            PlayerSync.LOGGER.info("=== END APOTHEOSIS DEBUG ===");
        }
    }

    /**
     * Sanitizes NBT strings to fix known corruption issues while maintaining backwards compatibility.
     * Specifically targets:
     * - Apotheosis empty key-value pairs like {"":""}
     * - Other malformed JSON/NBT structures that cause parsing failures
     *
     * @param nbtString The NBT string to sanitize
     * @return The sanitized NBT string
     */
    private static String sanitizeNbtString(String nbtString) {
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

        // Fix Draconic Evolution config_properties corruption: {"": "DECIMAL"} -> {"type": "DECIMAL"}
        // These appear as the first element in draconicevolution:config_properties arrays
        if (sanitized.contains("draconicevolution:config_properties")) {
            String draconicPattern = "\\{\"\":\\s*\"(DECIMAL|INTEGER|STRING)\"\\s*\\}";
            if (sanitized.matches(".*" + draconicPattern + ".*")) {
                // Replace empty key with "type" key to fix the structure
                sanitized = sanitized.replaceAll(draconicPattern, "{\"type\": \"$1\"}");
                wasModified = true;
                
                if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                    PlayerSync.LOGGER.info("[ITEM] Fixed Draconic Evolution config_properties corruption -> type key");
                }
            }
        }

        // Fix orphaned commas that might result from the above cleanup
        sanitized = sanitized.replaceAll(",\\s*,", ","); // Double commas
        sanitized = sanitized.replaceAll("\\[\\s*,", "["); // Leading comma in arrays
        sanitized = sanitized.replaceAll(",\\s*\\]", "]"); // Trailing comma in arrays
        sanitized = sanitized.replaceAll("\\{\\s*,", "{"); // Leading comma in objects
        sanitized = sanitized.replaceAll(",\\s*\\}", "}"); // Trailing comma in objects

        if (wasModified && vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
            PlayerSync.LOGGER.info("[DEBUG] Sanitized corrupted NBT - Original length: {}, Sanitized length: {}", 
                nbtString.length(), sanitized.length());
            PlayerSync.LOGGER.info("[DEBUG] Fixed empty key-value pairs in NBT structure");
        }

        return sanitized;
    }

    /**
     * Deserializes a string from the database back into an NBT string.
     * Handles both the new Base64 format (prefixed with "B64:") and the old custom format.
     *
     * @param encoded The string retrieved from the database.
     * @return The deserialized NBT string.
     */
    public static String deserializeString(String encoded) {
        if (encoded.startsWith("B64:")) {
            String base64 = encoded.substring(4);
            try {
                return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                PlayerSync.LOGGER.error("Base64 decoding failed for data: " + encoded, ex);
                // fallback to legacy decoding below
            }
        }
        // Legacy fallback using custom replacement
        return encoded.replace("|", ",")
                .replace("^", "\"")
                .replace("<", "{")
                .replace(">", "}")
                .replace("~", "'");
    }

    /**
     * Serializes an NBT string for database storage.
     * Uses Base64 encoding by default (prefixed with "B64:").
     * If USE_LEGACY_SERIALIZATION config is true, uses the old custom replacement format.
     *
     * @param object The NBT string to serialize.
     * @return The serialized string.
     */
    public static String serialize(String object) {
        // Check the config option for backwards compatibility during writing
        if (JdbcConfig.USE_LEGACY_SERIALIZATION.get()) {
            // Use old custom replacement logic
            return object.replace(",", "|")
                         .replace("\"", "^")
                         .replace("{", "<")
                         .replace("}", ">")
                         .replace("'", "~");
        }

        // Base64 encode with a "B64:" marker for new data
        return "B64:" + Base64.getEncoder().encodeToString(object.getBytes(StandardCharsets.UTF_8));
    }

    public static void doPlayerSaveToFile(PlayerEvent.SaveToFile event) throws SQLException, IOException {
        JDBCsetUp.executeUpdate("UPDATE server_info SET last_update=" + System.currentTimeMillis() + " WHERE id=" + JdbcConfig.SERVER_ID.get());
        if (!event.getEntity().getTags().contains("player_synced")) return;
        store(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onPlayerSaveToFile(PlayerEvent.SaveToFile event) {
        // Check if we're shutting down or if executor is shutdown
        if (isShuttingDown || executorService.isShutdown()) {
            PlayerSync.LOGGER.warn("Skipping player save task for {} - server is shutting down", 
                event.getEntity().getName().getString());
            return;
        }
        
        try {
            executorService.submit(() -> {
                try {
                    doPlayerSaveToFile(event);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Error in player save task: {}", e.getMessage());
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (Exception e) {
            // Handle RejectedExecutionException and other potential exceptions
            PlayerSync.LOGGER.warn("Failed to submit player save task for {} - executor may be shutdown: {}", 
                event.getEntity().getName().getString(), e.getMessage());
            
            // Try to save synchronously as fallback
            try {
                PlayerSync.LOGGER.info("Attempting synchronous save for {} as fallback", 
                    event.getEntity().getName().getString());
                doPlayerSaveToFile(event);
            } catch (Exception fallbackException) {
                PlayerSync.LOGGER.error("Synchronous save fallback failed for {}: {}", 
                    event.getEntity().getName().getString(), fallbackException.getMessage());
            }
        }
    }

    @SubscribeEvent
    public static void onServerShutdown(ServerStoppedEvent event) throws SQLException {
        PlayerSync.LOGGER.info("Server shutting down, initiating graceful PlayerSync shutdown...");
        
        // Mark as shutting down to prevent new task submissions
        isShuttingDown = true;
        
        // Update server status in database
        JDBCsetUp.executeUpdate("UPDATE server_info SET enable= '0' WHERE id=" + JdbcConfig.SERVER_ID.get());
        
        // Shutdown executor service gracefully
        if (executorService != null && !executorService.isShutdown()) {
            PlayerSync.LOGGER.info("Shutting down PlayerSync thread pool...");
            executorService.shutdown(); // Disable new tasks from being submitted
            
            try {
                // Wait a while for existing tasks to terminate
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    PlayerSync.LOGGER.warn("Thread pool did not terminate gracefully within 30 seconds, forcing shutdown...");
                    executorService.shutdownNow(); // Cancel currently executing tasks
                    
                    // Wait a bit more for tasks to respond to being cancelled
                    if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                        PlayerSync.LOGGER.error("Thread pool did not terminate after forced shutdown");
                    } else {
                        PlayerSync.LOGGER.info("Thread pool terminated successfully after forced shutdown");
                    }
                } else {
                    PlayerSync.LOGGER.info("PlayerSync thread pool shutdown completed gracefully");
                }
            } catch (InterruptedException ie) {
                PlayerSync.LOGGER.warn("Thread pool shutdown interrupted, forcing immediate shutdown...");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        PlayerSync.LOGGER.info("PlayerSync shutdown complete");
    }

    public static void doPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException, IOException {
        String player_uuid = event.getEntity().getUUID().toString();
        JDBCsetUp.executeUpdate("UPDATE player_data SET online= '0' WHERE uuid='" + player_uuid + "'");
        store(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) throws SQLException {
        String player_uuid = event.getEntity().getUUID().toString();
        if (deadPlayerWhileLogging.contains(player_uuid)) {
            PlayerSync.LOGGER.warn("A dead or dying player was kicked,which uuid is:{}", player_uuid);
            JDBCsetUp.executeUpdate("UPDATE player_data SET online= '0' WHERE uuid='" + player_uuid + "'");
            deadPlayerWhileLogging.remove(player_uuid);
        } else if (syncNotCompletedPlayer.contains(player_uuid)) {
            PlayerSync.LOGGER.warn("A player logged out with uncompleted sync data,which uuid is:{}.For the safety,the new data won't be saved", player_uuid);
            JDBCsetUp.executeUpdate("UPDATE player_data SET online= '0' WHERE uuid='" + player_uuid + "'");
            syncNotCompletedPlayer.remove(player_uuid);
        } else {
            // Mod support
            ModsSupport modsSupport = new ModsSupport();
            modsSupport.onPlayerLeave(event.getEntity());
            
            // Check if we're shutting down or if executor is shutdown
            if (isShuttingDown || executorService.isShutdown()) {
                PlayerSync.LOGGER.warn("Skipping player logout task for {} - server is shutting down, executing synchronously", 
                    event.getEntity().getName().getString());
                
                // Execute synchronously during shutdown to ensure player data is saved
                try {
                    doPlayerLogout(event);
                    PlayerSync.LOGGER.info("Successfully saved player data synchronously for {} during shutdown", 
                        event.getEntity().getName().getString());
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Failed to save player data synchronously for {} during shutdown: {}", 
                        event.getEntity().getName().getString(), e.getMessage());
                    if (JdbcConfig.DEBUG_MODE.get()) {
                        e.printStackTrace();
                    }
                }
                return;
            }
            
            try {
                executorService.submit(() -> {
                    try {
                        doPlayerLogout(event);
                    } catch (Exception e) {
                        PlayerSync.LOGGER.error("Error in player logout task: {}", e.getMessage());
                        if (JdbcConfig.DEBUG_MODE.get()) {
                            e.printStackTrace();
                        }
                    }
                });
            } catch (Exception e) {
                // Handle RejectedExecutionException and other potential exceptions
                PlayerSync.LOGGER.warn("Failed to submit player logout task for {} - executor may be shutdown: {}", 
                    event.getEntity().getName().getString(), e.getMessage());
                
                // Try to save synchronously as fallback
                try {
                    PlayerSync.LOGGER.info("Attempting synchronous logout save for {} as fallback", 
                        event.getEntity().getName().getString());
                    doPlayerLogout(event);
                } catch (Exception fallbackException) {
                    PlayerSync.LOGGER.error("Synchronous logout save fallback failed for {}: {}", 
                        event.getEntity().getName().getString(), fallbackException.getMessage());
                }
            }
        }
    }

    // Helper function to get the NBT string to be saved
    // If item is a placeholder, get original NBT; otherwise, get current NBT
    private static String getNbtForStorage(ItemStack itemStack) {
        if (itemStack.is(Items.PAPER) && itemStack.getComponents().has(DataComponents.CUSTOM_DATA)
                && itemStack.getComponents().get(DataComponents.CUSTOM_DATA).contains("playersync:original_item_nbt")) {
            // It's our placeholder, retrieve the original NBT string
            String originalNbt = itemStack.getComponents().get(DataComponents.CUSTOM_DATA).copyTag().getString("playersync:original_item_nbt");
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                PlayerSync.LOGGER.info("[DEBUG] Storing placeholder item with original NBT (length: {})", originalNbt.length());
            }
            return originalNbt;
        } else {
            // It's a normal item or empty, serialize its current NBT
            try {
                String serializedNbt = serialize(serializeNBT(itemStack).toString());
                
                // Enhanced debugging for Apotheosis and Forbidden Arcanus items
                if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                    String itemId = itemStack.getItem().toString();
                    boolean isApothItem = itemId.contains("apotheosis") || 
                                        (itemStack.has(DataComponents.CUSTOM_DATA) && 
                                         itemStack.get(DataComponents.CUSTOM_DATA).copyTag().toString().contains("apotheosis"));
                    boolean isForbiddenArcanus = itemId.contains("forbidden") || itemId.contains("arcanus") ||
                                               (itemStack.has(DataComponents.CUSTOM_DATA) && 
                                                itemStack.get(DataComponents.CUSTOM_DATA).copyTag().toString().contains("forbidden")) ||
                                               itemStack.has(DataComponents.UNBREAKABLE);
                    
                    if (isApothItem) {
                        PlayerSync.LOGGER.info("[DEBUG] Storing Apotheosis item: {} -> serialized length: {}", 
                            itemId, serializedNbt.length());
                        
                        // Log custom data component details if present
                        if (itemStack.has(DataComponents.CUSTOM_DATA)) {
                            CompoundTag customData = itemStack.get(DataComponents.CUSTOM_DATA).copyTag();
                            PlayerSync.LOGGER.info("[DEBUG] Custom data component: {}", customData.toString());
                        }
                    }
                    
                    if (isForbiddenArcanus) {
                        PlayerSync.LOGGER.info("[STELLA_DEBUG] Storing F&A item: {} -> serialized length: {}", 
                            itemId, serializedNbt.length());
                        
                        // Log unbreakable status
                        boolean hasUnbreakable = itemStack.has(DataComponents.UNBREAKABLE);
                        PlayerSync.LOGGER.info("[STELLA_DEBUG] Item has vanilla unbreakable: {}", hasUnbreakable);
                        
                        // Log custom data component details if present
                        if (itemStack.has(DataComponents.CUSTOM_DATA)) {
                            CompoundTag customData = itemStack.get(DataComponents.CUSTOM_DATA).copyTag();
                            PlayerSync.LOGGER.info("[STELLA_DEBUG] Custom data component: {}", customData.toString());
                            
                            // Check for Forbidden Arcanus specific NBT tags
                            if (customData.toString().contains("forbidden") || 
                                customData.toString().contains("arcanus") ||
                                customData.toString().contains("stella") ||
                                customData.toString().contains("eternal")) {
                                PlayerSync.LOGGER.info("[STELLA_DEBUG] Found F&A specific NBT data in custom component");
                            }
                        }
                    }
                }
                
                return serializedNbt;
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Failed to serialize ItemStack for storage: {} - Error: {}", 
                    itemStack.getItem().toString(), e.getMessage(), e);
                // Return a basic serialized empty tag to prevent database corruption
                return serialize("{}");
            }
        }
    }

    public static Tag serializeNBT(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return new CompoundTag();
        }
        
        try {
            // Serialize the ItemStack to NBT using Data Components API
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            Tag compoundTag = itemStack.save(provider);
            
            if (vip.fubuki.playersync.config.JdbcConfig.DEBUG_MODE.get()) {
                String itemId = itemStack.getItem().toString();
                boolean isApothItem = itemId.contains("apotheosis") || 
                                    (itemStack.has(DataComponents.CUSTOM_DATA) && 
                                     itemStack.get(DataComponents.CUSTOM_DATA).copyTag().toString().contains("apotheosis"));
                boolean isForbiddenArcanus = itemId.contains("forbidden") || itemId.contains("arcanus") ||
                                           (itemStack.has(DataComponents.CUSTOM_DATA) && 
                                            itemStack.get(DataComponents.CUSTOM_DATA).copyTag().toString().contains("forbidden")) ||
                                           itemStack.has(DataComponents.UNBREAKABLE);
                
                if (isApothItem) {
                    PlayerSync.LOGGER.info("[DEBUG] Serializing Apotheosis item: {} -> NBT: {}", 
                        itemId, compoundTag.toString());
                }
                
                if (isForbiddenArcanus) {
                    PlayerSync.LOGGER.info("[STELLA_DEBUG] Serializing F&A item: {} -> NBT: {}", 
                        itemId, compoundTag.toString());
                    
                    // Check if unbreakable component is preserved in serialized NBT
                    String nbtString = compoundTag.toString();
                    boolean hasUnbreakableInNbt = nbtString.contains("\"minecraft:unbreakable\"") || nbtString.contains("Unbreakable");
                    PlayerSync.LOGGER.info("[STELLA_DEBUG] Serialized NBT contains unbreakable: {}", hasUnbreakableInNbt);
                }
            }
            
            return compoundTag;
        } catch (Exception e) {
            PlayerSync.LOGGER.error("Failed to serialize ItemStack: {} - Error: {}", 
                itemStack.getItem().toString(), e.getMessage(), e);
            // Return basic NBT with just the item ID to prevent complete data loss
            CompoundTag fallbackTag = new CompoundTag();
            fallbackTag.putString("id", BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString());
            fallbackTag.putInt("count", itemStack.getCount());
            return fallbackTag;
        }
    }


    public static void store(Player player, boolean init) throws SQLException, IOException {
        String player_uuid = player.getUUID().toString();
        PlayerSync.LOGGER.info("Storing data for player " + player_uuid + " (init=" + init + ")");

        // Note: High-count items are handled during individual item serialization
        // to avoid modifying the actual player inventory

        // Basic Attributes
        int XP = getTotalExperience(player);
        int score = player.getScore();
        int food_level = player.getFoodData().getFoodLevel();
        int health = (int) player.getHealth();
        // Left Hand
        String left_hand = getNbtForStorage(player.getItemInHand(InteractionHand.OFF_HAND));

        // Cursor
        String cursors = getNbtForStorage(player.containerMenu.getCarried());

        // Equipment (Armor)
        Map<Integer, String> equipment = new HashMap<>();
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            ItemStack itemStack = player.getInventory().armor.get(i);
            equipment.put(i, getNbtForStorage(itemStack));
        }
        // Inventory
        Inventory inventory = player.getInventory();
        Map<Integer, String> inventoryMap = new HashMap<>();
        for (int i = 0; i < inventory.items.size(); i++) {
            inventoryMap.put(i, getNbtForStorage(inventory.items.get(i)));
        }
        // Ender Chest
        Map<Integer, String> ender_chest = new HashMap<>();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ender_chest.put(i, getNbtForStorage(player.getEnderChestInventory().getItem(i)));
        }

        if(ModList.get().isLoaded("sophisticatedbackpacks")){
            ModsSupport.storeSophisticatedBackpacks(player);
        }

        // Effects
        Map<Holder<MobEffect>, MobEffectInstance> effects = player.getActiveEffectsMap();
        Map<Integer, String> effectMap = new HashMap<>();
        for (Map.Entry<Holder<MobEffect>, MobEffectInstance> entry : effects.entrySet()) {
            Tag effectTag = entry.getValue().save();
            effectMap.put(BuiltInRegistries.MOB_EFFECT.getId(entry.getKey().value()), serialize(effectTag.toString()));
        }

        // Advancements
        File advancements = null;
        byte[] advancementBytes = new byte[0];
        if (JdbcConfig.SYNC_ADVANCEMENTS.get()) {
            Path path = player.getServer().getServerDirectory().resolve(getSyncWorldForServer());
            File gameDir = path.toFile();
            final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isDedicatedServer()) {
                PlayerSync.LOGGER.trace("Reading dedicated server advancements");
                advancements = new File(gameDir,"/advancements" + "/" + player_uuid + ".json");
            } else {
                gameDir = Objects.requireNonNull(player.getServer()).getServerDirectory().toFile();
                PlayerSync.LOGGER.debug("Reading non-dedicated server advancements");
                File[] files = scanAdvancementsFile(player_uuid, gameDir);
                long latestModifiedDate = 0;
                for (File file : files) {
                    if (file == null) continue;
                    if (file.lastModified() > latestModifiedDate) {
                        latestModifiedDate = file.lastModified();
                        advancements = file;
                    }
                }
            }
            if (!advancements.exists()) {
                PlayerSync.LOGGER.warn("Advancements file for " + player_uuid + " does not exist (yet).");
            }

            if (advancements.exists()) {
                PlayerSync.LOGGER.debug("Storing advancements for " + player_uuid + " from " + advancements.toPath());
                advancementBytes = Files.readAllBytes(advancements.toPath());
            } else {
                PlayerSync.LOGGER.error("Unable to save advancements for player " + player_uuid);
            }
        }
        String json = new String(advancementBytes, StandardCharsets.UTF_8);
        if (JdbcConfig.DEBUG_ACHIEVEMENTS.get()) {
            PlayerSync.LOGGER.info("[ADVANCE] Storing advancements for player {}: {}", player_uuid, json);
        }

        // SQL Operation for player data
        if (init) {
            JDBCsetUp.executeUpdate("INSERT INTO player_data (uuid,armor,inventory,enderchest,advancements,effects,xp,food_level,health,score,left_hand,cursors,online) VALUES ('" + player_uuid + "','" + equipment + "','" + inventoryMap + "','" + ender_chest + "','" + json + "','" + effectMap + "','" + XP + "','" + food_level + "','" + health + "','" + score + "','" + left_hand + "','" + cursors + "',online=true)");
        } else {
            JDBCsetUp.executeUpdate("UPDATE player_data SET inventory = '" + inventoryMap + "',armor='" + equipment + "' ,xp='" + XP + "',effects='" + effectMap + "',enderchest='" + ender_chest + "',score='" + score + "',food_level='" + food_level + "',health='" + health + "',advancements='" + json + "',left_hand='" + left_hand + "',cursors='" + cursors + "' WHERE uuid = '" + player_uuid + "'");
        }
    }

    private static String getSyncWorldForServer() {
        if (!JdbcConfig.SYNC_WORLD.get().isEmpty()) {
            PlayerSync.LOGGER.warn("Using configuration 'sync_world' on servers is deprecated. Please leave the array empty. Falling back to first entry.");
            return JdbcConfig.SYNC_WORLD.get().get(0);
        }

        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PlayerSync.LOGGER.error("Unable to get current server. Assuming default level-name 'world'.");
            return "world";
        }

        final WorldData worldData = server.getWorldData();
        final String levelName = worldData.getLevelName();
        PlayerSync.LOGGER.debug("Using server level-name: " + levelName);

        return levelName;
    }

    private static File[] scanAdvancementsFile(String player_uuid, File gameDir) {
        File[] files = new File[JdbcConfig.SYNC_WORLD.get().size()];
        for (int i = 0; i < JdbcConfig.SYNC_WORLD.get().size(); i++) {
            File advanceFile = new File(gameDir, "saves/" + JdbcConfig.SYNC_WORLD.get().get(i) + "/advancements" + "/" + player_uuid + ".json");
            if (!advanceFile.exists()) continue;
            files[i] = advanceFile;
        }
        return files;
    }

    static int tick = 0;

    @SubscribeEvent
    public static void onUpdate(LevelTickEvent.Post event) throws SQLException {
        tick++;
        if (tick == 1800) {
            tick = 0;
            long current = System.currentTimeMillis();
            JDBCsetUp.executeUpdate("UPDATE server_info SET last_update =" + current + " WHERE id= " + JdbcConfig.SERVER_ID.get());
        }
    }


    // New fields for auto-save
    private static int autoSaveTickCounter = 0;
    private static final int AUTO_SAVE_INTERVAL_TICKS = 1200; // Every Minute
    private static int autoCleanCuriosCacheTickCounter = 0;
    private static final int AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS = 36000; // Every 30 min

    //AutoSave
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Run at the end phase to avoid interfering with game logic
        autoSaveTickCounter++;
        autoCleanCuriosCacheTickCounter++;
        if (autoSaveTickCounter >= AUTO_SAVE_INTERVAL_TICKS) {
            autoSaveTickCounter = 0;
            // Retrieve the current server instance
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                // Iterate through all online players
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    executorService.submit(() -> {
                        try {
                            // Call the same store method used in logout and file save events.
                            store(player, false);
                        } catch (Exception e) {
                            PlayerSync.LOGGER.error("Error auto-saving player " + player.getUUID(), e);
                        }
                    });
                    executorService.submit(() -> {
                        try {
                            new ModsSupport().StoreCurios(player, false);
                        } catch (SQLException e) {
                            PlayerSync.LOGGER.error("Error auto-saving Curios data for player " + player.getUUID(), e);
                        }
                    });

                    }
            }
        }
        if (autoCleanCuriosCacheTickCounter >= AUTO_CLEAN_CURIOS_CACHE_INTERVAL_TICKS) {
            autoCleanCuriosCacheTickCounter = 0;
            executorService.submit(() -> {
                try {
                    CuriosCache.RemoveExpiredCuriosCache();
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("An error occurred while cleaning curios cache:" + e.getMessage());
                }
            });
        }
    }

    private static void setXpForPlayer(ServerPlayer serverPlayer, int databaseXp) {
        // Don't use giveExperience() as it has several side-effects:
        // triggers an event, sends network packets, increases the score, ...
        serverPlayer.totalExperience = databaseXp;
        serverPlayer.experienceLevel = 0;
        serverPlayer.experienceProgress = 0;

        int xpForLevel;

        while (databaseXp >= (xpForLevel = serverPlayer.getXpNeededForNextLevel())) {
            databaseXp -= xpForLevel;
            serverPlayer.experienceLevel++;
        }

        serverPlayer.experienceProgress = serverPlayer.experienceLevel > 0
                ? (float) databaseXp / serverPlayer.getXpNeededForNextLevel()
                : 0f;

        PlayerSync.LOGGER.debug("Giving player "
                + serverPlayer.experienceLevel + " levels and "
                + serverPlayer.experienceProgress * 100 + "% experience progress, calculated from "
                + serverPlayer.totalExperience + " XP.");
    }

    private static int getTotalExperience(final Player player) {
        int level = player.experienceLevel;
        int totalXp = 0;

        // Calculate total XP for completed levels
        if (level > 30) {
            totalXp = (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220);
        } else if (level > 15) {
            totalXp = (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360);
        } else {
            totalXp = level * level + 6 * level;
        }

        // Add partial level progress
        totalXp += Math.round(player.getXpNeededForNextLevel() * player.experienceProgress);

        PlayerSync.LOGGER.debug("Experience calcuation for "
                + player.experienceLevel + " levels and "
                + player.experienceProgress * 100 + "% experience progress yields "
                + totalXp + " XP.");

        return totalXp;
    }

    @SubscribeEvent
    //Don't know what will happen if a fake player is killed,need more test.
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !deadPlayerWhileLogging.contains(event.getEntity().getUUID().toString())) {
            CuriosCache.tryStoreCuriosToCache(player);
        }
    }

    public static void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}