package de.mcstack.craftcore;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class PurchaseMenu implements InventoryHolder {
 final String table,id,item,purchaseId; final long price; final int available; int quantity=1;
 PurchaseMenu(String table,String id,long price,String item){this.table=table;this.id=id;this.price=price;this.item=item;this.purchaseId=UUID.randomUUID().toString();this.available=stack().getAmount();}
 public Inventory getInventory(){return null;}
 ItemStack stack(){return ItemStack.deserializeBytes(Base64.getDecoder().decode(item));}
 String remaining(){ItemStack s=stack();s.setAmount(available-quantity);return Base64.getEncoder().encodeToString(s.serializeAsBytes());}
 void show(Player p){Inventory inv=Bukkit.createInventory(this,27,"Menge wählen");int[] quantities={1,8,16,32,64,available};for(int i=0;i<quantities.length;i++){int n=quantities[i];ItemStack b=new ItemStack(n<=available?Material.PAPER:Material.GRAY_DYE);ItemMeta m=b.getItemMeta();m.setDisplayName(i==5?"Alles ("+n+")":Integer.toString(n));m.setLore(List.of("Stückpreis: "+Money.format(UnitPrice.fromTotal(price,available)),"Gesamtpreis: "+(n<=available?Money.format(UnitPrice.total(UnitPrice.fromTotal(price,available),n)):"nicht verfügbar")));b.setItemMeta(m);inv.setItem(2+i,b);}ItemStack confirm=new ItemStack(Material.LIME_WOOL);ItemMeta m=confirm.getItemMeta();m.setDisplayName("Bestätigen: "+quantity);m.setLore(List.of("Stückpreis: "+Money.format(UnitPrice.fromTotal(price,available)),"Gesamtpreis: "+Money.format(UnitPrice.total(UnitPrice.fromTotal(price,available),quantity))));confirm.setItemMeta(m);inv.setItem(20,confirm);ItemStack cancel=new ItemStack(Material.RED_WOOL);ItemMeta cm=cancel.getItemMeta();cm.setDisplayName("Abbrechen");cancel.setItemMeta(cm);inv.setItem(24,cancel);p.openInventory(inv);}
 void click(CraftCore core,Player p,int slot)throws Exception{if(slot>=2&&slot<=7){int[] choices={1,8,16,32,64,available};int n=choices[slot-2];if(n<=available){quantity=n;show(p);}return;}if(slot==24){p.closeInventory();return;}if(slot!=20)return;String rest=quantity==available?item:remaining();String result=core.tx(c->PartialPurchase.buy(c,table,id,p.getUniqueId().toString(),purchaseId,price,item,available,quantity,rest,System.currentTimeMillis(),core.getConfig().getInt("auction.fee-basis-points",500)));if(result==null)SaleNotices.afterPurchase(core,purchaseId);p.closeInventory();p.sendMessage(result==null?"Kauf abgeschlossen. Die Items liegen im Abholfach (/abholen).":result);}
}
