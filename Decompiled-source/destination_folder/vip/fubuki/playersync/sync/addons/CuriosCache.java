package vip.fubuki.playersync.sync.addons;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;

public class CuriosCache {
   private static final long CACHE_EXPIRY_MS = 3600000L;
   public static final ConcurrentHashMap<UUID, CuriosCache.CuriosCacheEntry> curiosCache = new ConcurrentHashMap<>();

   public static void tryStoreCuriosToCache(Player player) {
      if (ModList.get().isLoaded("curios") && isKeepInventoryActive(player)) {
         try {
            Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
            if (handlerOpt.isEmpty()) {
               PlayerSync.LOGGER.error("Obtain the curios api failed,cannot create the cache.");
               return;
            }

            ICuriosItemHandler handler = handlerOpt.get();
            String serializedData = serializeCuriosInventory(handler);
            if (serializedData.startsWith("{}")) {
               PlayerSync.LOGGER.debug("No curios data found,skipping the step of creating cache");
               return;
            }

            UUID playerUuid = player.getUUID();
            curiosCache.put(playerUuid, new CuriosCache.CuriosCacheEntry(serializedData));
         } catch (Exception var5) {
            PlayerSync.LOGGER.error("An error occurred while creating curios cache:" + var5.getMessage());
         }
      }
   }

   private static String serializeCuriosInventory(ICuriosItemHandler handler) {
      Map<String, String> flatMap = new HashMap<>();

      try {
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
      } catch (Exception var3) {
         PlayerSync.LOGGER.error("Failed to serialize curios data:" + var3.getMessage());
      }

      return flatMap.isEmpty() ? "{}" : flatMap.toString();
   }

   public static boolean isKeepInventoryActive(Player player) {
      MinecraftServer server = player.getServer();
      if (server == null) {
         PlayerSync.LOGGER.error("Trying to get the gamerule(KeepInventory),but server is null");
         return false;
      } else {
         return server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
      }
   }

   public static void RemoveExpiredCuriosCache() {
      long startMs = System.currentTimeMillis();
      int cacheSize = curiosCache.size();
      if (cacheSize == 0) {
         PlayerSync.LOGGER.debug("No curios caches,skipping cleaning");
      } else {
         int removed = 0;
         Iterator<Entry<UUID, CuriosCache.CuriosCacheEntry>> iterator = curiosCache.entrySet().iterator();

         while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
               iterator.remove();
               removed++;
            }
         }

         if (removed > 0) {
            PlayerSync.LOGGER
               .info("Cleaned {} curios cache(s),{} left,took {} Ms", new Object[]{removed, curiosCache.size(), System.currentTimeMillis() - startMs});
         }
      }
   }

   public static class CuriosCacheEntry {
      final long timeStamp = System.currentTimeMillis();
      final String serializedData;

      CuriosCacheEntry(String data) {
         this.serializedData = data;
      }

      boolean isExpired() {
         return System.currentTimeMillis() - this.timeStamp > 3600000L;
      }
   }
}
