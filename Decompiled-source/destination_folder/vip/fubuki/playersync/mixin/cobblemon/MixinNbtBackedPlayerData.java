package vip.fubuki.playersync.mixin.cobblemon;

import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.storage.player.InstancedPlayerData;
import com.cobblemon.mod.common.api.storage.player.adapter.NbtBackedPlayerData;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vip.fubuki.playersync.mixin.cobblemon.accessor.FileBasedPlayerDataStoreBackendAccessor;
import vip.fubuki.playersync.mixin.cobblemon.accessor.NbtBackedPlayerDataAccessor;
import vip.fubuki.playersync.util.JDBCsetUp;

@Mixin(NbtBackedPlayerData.class)
public class MixinNbtBackedPlayerData {
   @Inject(method = "save", at = @At("HEAD"))
   private void save$playerSync(InstancedPlayerData playerData, CallbackInfo ci) {
      if (playerData instanceof PokedexManager) {
         Codec<InstancedPlayerData> codec = ((NbtBackedPlayerDataAccessor)this).getCodec();
         DataResult<Tag> encodeResult = codec.encodeStart(NbtOps.INSTANCE, playerData);
         CompoundTag nbt = (CompoundTag)encodeResult.result().orElseThrow();
         String serializedData = nbt.toString();
         String sql = "INSERT INTO cobblemon (uuid, pokedex) VALUES ('"
            + playerData.getUuid()
            + "', '"
            + serializedData
            + "') ON DUPLICATE KEY UPDATE pokedex = '"
            + serializedData
            + "'";

         try {
            JDBCsetUp.executeUpdate(sql);
         } catch (SQLException var9) {
            throw new RuntimeException(var9);
         }
      }
   }

   @Inject(method = "load", at = @At("HEAD"), cancellable = true)
   private void load$playerSync(UUID uuid, CallbackInfoReturnable<InstancedPlayerData> cir) {
      if (((FileBasedPlayerDataStoreBackendAccessor)this).getType().getId().equals(ResourceLocation.fromNamespaceAndPath("cobblemon", "pokedex"))) {
         String sql = "SELECT pokedex FROM cobblemon WHERE uuid = '" + uuid + "'";

         try {
            JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery(sql);
            ResultSet rs = qr.resultSet();
            if (rs.next()) {
               String serializedData = rs.getString("pokedex");
               if (serializedData == null) {
                  rs.close();
                  qr.close();
                  return;
               }

               CompoundTag loadedNbt = NbtUtils.snbtToStructure(serializedData);
               if (!loadedNbt.isEmpty()) {
                  Codec<InstancedPlayerData> codec = ((NbtBackedPlayerDataAccessor)this).getCodec();
                  DataResult<InstancedPlayerData> decodeResult = codec.parse(NbtOps.INSTANCE, loadedNbt);
                  InstancedPlayerData playerData = (InstancedPlayerData)decodeResult.result().orElseThrow();
                  cir.setReturnValue(playerData);
               }
            }

            rs.close();
            qr.close();
         } catch (CommandSyntaxException | SQLException var11) {
            throw new RuntimeException(var11);
         }
      }
   }
}
