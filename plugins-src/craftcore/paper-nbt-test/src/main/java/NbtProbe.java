import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionType;
import org.bukkit.block.ShulkerBox;
public class NbtProbe extends JavaPlugin {
  void check(String n,ItemStack item){ItemStack copy=ItemStack.deserializeBytes(item.serializeAsBytes());if(!item.equals(copy))throw new IllegalStateException(n+" roundtrip failed");getLogger().info("NBT_ROUNDTRIP_OK "+n);}
  public void onEnable(){try{
    ItemStack book=new ItemStack(Material.ENCHANTED_BOOK);EnchantmentStorageMeta bm=(EnchantmentStorageMeta)book.getItemMeta();bm.addStoredEnchant(Enchantment.UNBREAKING,3,true);book.setItemMeta(bm);check("enchanted_book",book);
    ItemStack potion=new ItemStack(Material.POTION);PotionMeta pm=(PotionMeta)potion.getItemMeta();pm.setBasePotionType(PotionType.HEALING);potion.setItemMeta(pm);check("potion",potion);
    ItemStack box=new ItemStack(Material.SHULKER_BOX);BlockStateMeta sm=(BlockStateMeta)box.getItemMeta();ShulkerBox state=(ShulkerBox)sm.getBlockState();state.getInventory().setItem(0,book);state.getInventory().setItem(1,potion);sm.setBlockState(state);box.setItemMeta(sm);ItemStack copy=ItemStack.deserializeBytes(box.serializeAsBytes());BlockStateMeta cm=(BlockStateMeta)copy.getItemMeta();ShulkerBox cs=(ShulkerBox)cm.getBlockState();if(!book.equals(cs.getInventory().getItem(0))||!potion.equals(cs.getInventory().getItem(1)))throw new IllegalStateException("shulker contents failed");check("shulker_contents",box);
  }catch(Throwable e){getLogger().severe("NBT_ROUNDTRIP_FAIL "+e);}}
}
