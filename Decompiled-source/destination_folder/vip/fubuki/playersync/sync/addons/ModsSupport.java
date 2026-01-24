package vip.fubuki.playersync.sync.addons;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.LocalJsonUtil;

public class ModsSupport {
   public void doBackPackRestore(Player player) {
      if (ModList.get().isLoaded("sophisticatedbackpacks")) {
         PlayerSync.LOGGER.info("Restoring backpack data for player " + player.getUUID());
         PlayerInventoryProvider.get().runOnBackpacks(player, (backpackItem, handler, identifier, slot) -> {
            IBackpackWrapper backpackWrapper = BackpackWrapper.fromStack(backpackItem);
            Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
            if (uuidOpt.isPresent()) {
               UUID contentsUuid = uuidOpt.get();

               try {
                  JDBCsetUp.QueryResult qrBackpack = JDBCsetUp.executeQuery("SELECT backpack_nbt FROM backpack_data WHERE uuid='" + contentsUuid + "'");
                  ResultSet rsBackpack = qrBackpack.resultSet();
                  if (rsBackpack.next()) {
                     String serialized = rsBackpack.getString("backpack_nbt");
                     String nbtString = VanillaSync.deserializeString(serialized);
                     CompoundTag backpackNbt = NbtUtils.snbtToStructure(nbtString);
                     BackpackStorage.get().setBackpackContents(contentsUuid, backpackNbt);
                     PlayerSync.LOGGER.info("Restored backpack data for UUID " + contentsUuid);
                  }

                  rsBackpack.close();
                  qrBackpack.connection().close();
               } catch (SQLException var12) {
                  PlayerSync.LOGGER.error("Error restoring backpack data for UUID " + contentsUuid, var12);
               } catch (CommandSyntaxException var13) {
                  throw new RuntimeException(var13);
               }
            } else {
               PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid during restore");
            }

            return false;
         });
      }
   }

   public void doCuriosRestore(Player player) throws SQLException {
      if (ModList.get().isLoaded("curios")) {
         Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
         JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery("SELECT curios_item FROM curios WHERE uuid = '" + player.getUUID() + "'");
         ResultSet rs = qr.resultSet();
         if (rs.next()) {
            String curiosData = rs.getString("curios_item");
            Map<String, String> storedMap = LocalJsonUtil.StringToMap(curiosData);
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

            handlerOpt.ifPresent(handler -> {
               for (Entry<String, String> entry : storedMap.entrySet()) {
                  String compositeKey = entry.getKey();
                  String[] parts = compositeKey.split(":");
                  if (parts.length == 2) {
                     String slotType = parts[0];

                     int slotIndex;
                     try {
                        slotIndex = Integer.parseInt(parts[1]);
                     } catch (NumberFormatException var12) {
                        continue;
                     }

                     String serialized = entry.getValue();
                     ItemStack stack = VanillaSync.safeDeserializeItem(serialized, slotIndex, "curios:" + slotType);
                     if (handler.getCurios().containsKey(slotType)) {
                        ICurioStacksHandler stacksHandler = (ICurioStacksHandler)handler.getCurios().get(slotType);
                        IDynamicStackHandler dynStacks = stacksHandler.getStacks();
                        if (slotIndex < dynStacks.getSlots()) {
                           dynStacks.setStackInSlot(slotIndex, stack);
                        }
                     }
                  }
               }
            });
            rs.close();
            qr.connection().close();
         } else {
            this.StoreCurios(player, true);
         }
      }
   }

   public void onPlayerLeave(Player player) throws SQLException {
      if (ModList.get().isLoaded("curios")) {
         this.StoreCurios(player, false);
      }
   }

   public void StoreCurios(Player player, boolean init) throws SQLException {
      Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
      Map<String, String> flatMap = new HashMap<>();
      handlerOpt.ifPresent(handler -> handler.getCurios().forEach((slotType, stacksHandler) -> {
         IDynamicStackHandler dynStacks = stacksHandler.getStacks();

         for (int i = 0; i < dynStacks.getSlots(); i++) {
            ItemStack stack = dynStacks.getStackInSlot(i);
            if (!stack.isEmpty()) {
               String serialized = VanillaSync.serialize(VanillaSync.serializeNBT(stack).toString());
               flatMap.put(slotType + ":" + i, serialized);
            }
         }
      }));
      String serializedData = flatMap.toString();
      if (init) {
         JDBCsetUp.executeUpdate("INSERT INTO curios (uuid,curios_item) VALUES ('" + player.getUUID() + "', '" + serializedData + "')");
      } else {
         JDBCsetUp.executeUpdate("UPDATE curios SET curios_item = '" + serializedData + "' WHERE uuid = '" + player.getUUID() + "'");
      }
   }

   public static void storeSophisticatedBackpacks(Player player) {
      PlayerSync.LOGGER.info("Storing backpack data for player " + player.getUUID());
      PlayerInventoryProvider.get().runOnBackpacks(player, (backpackItem, handler, identifier, slot) -> {
         IBackpackWrapper backpackWrapper = BackpackWrapper.fromStack(backpackItem);
         Optional<UUID> uuidOpt = backpackWrapper.getContentsUuid();
         if (uuidOpt.isPresent()) {
            UUID contentsUuid = uuidOpt.get();
            CompoundTag backpackNbt = BackpackStorage.get().getOrCreateBackpackContents(contentsUuid);
            String serialized = VanillaSync.serialize(backpackNbt.toString());

            try {
               JDBCsetUp.executeUpdate("REPLACE INTO backpack_data (uuid, backpack_nbt) VALUES ('" + contentsUuid + "', '" + serialized + "')");
               PlayerSync.LOGGER.info("Saved backpack data for UUID " + contentsUuid);
            } catch (SQLException var10) {
               PlayerSync.LOGGER.error("Error saving backpack data for UUID " + contentsUuid, var10);
            }
         } else {
            PlayerSync.LOGGER.warn("Backpack item in slot " + slot + " has no contentsUuid");
         }

         return false;
      });
   }
}
