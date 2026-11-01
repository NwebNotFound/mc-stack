package de.mcstack.craftcore;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

/** Standalone editor; a future run engine must explicitly consume these tables. */
final class MineRushLootEditor implements Listener, CommandExecutor {
  enum Rarity { GEWOEHNLICH, UNGEWOEHNLICH, SELTEN, EPISCH, LEGENDAER }
  record Entry(ItemStack item, int weight, int min, int max, Rarity rarity) {}
  private final CraftCore core;
  private final File directory;
  private final Map<String, Editor> open = new HashMap<>();
  MineRushLootEditor(CraftCore core) { this.core=core; directory=new File(core.getDataFolder(),"loot"); }
  void start() {
    Objects.requireNonNull(core.getCommand("mr")).setExecutor(this);
    Bukkit.getPluginManager().registerEvents(this,core);
  }
  private final class Editor implements InventoryHolder {
    final String name; final List<Entry> entries; final Inventory inventory;
    Editor(String name,List<Entry> entries) { this.name=name; this.entries=entries; inventory=Bukkit.createInventory(this,54,"Loot: "+name); }
    public Inventory getInventory() { return inventory; }
    void render() {
      inventory.clear();
      for(int i=0;i<entries.size();i++) {
        Entry e=entries.get(i); ItemStack icon=e.item().clone(); ItemMeta meta=icon.getItemMeta();
        if(meta!=null) { List<String> lore=new ArrayList<>(); if(meta.hasLore()) lore.addAll(meta.getLore());
          lore.add("§7Gewicht: "+e.weight()+" | Menge: "+e.min()+"-"+e.max());
          lore.add("§7Seltenheit: "+e.rarity()); lore.add("§eLinks/Rechts: Gewicht +/-1; Shift-Rechts: Seltenheit");
          lore.add("§cShift-Links: Eintrag entfernen"); meta.setLore(lore); icon.setItemMeta(meta); }
        inventory.setItem(i,icon);
      }
    }
  }
  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
    if(!(sender instanceof Player p)) { sender.sendMessage("Nur für Spieler."); return true; }
    if(!p.hasPermission("craftcore.minerush.edit")) { p.sendMessage("Keine Berechtigung."); return true; }
    if(!"survival".equalsIgnoreCase(System.getenv().getOrDefault("CRAFTCORE_SERVER",""))) {p.sendMessage("Der Loot-Editor ist nur auf Survival verfügbar.");return true;}
    if(args.length!=3 || !args[0].equalsIgnoreCase("loot") || !args[1].equalsIgnoreCase("edit") || !args[2].matches("[a-zA-Z0-9_-]{1,32}")) {p.sendMessage("/mr loot edit <tabelle> (Name: Buchstaben, Zahlen, _ oder -)");return true;}
    String name=args[2].toLowerCase(Locale.ROOT);
    if(open.containsKey(name)) {p.sendMessage("Diese Tabelle wird bereits bearbeitet.");return true;}
    try { Editor editor=new Editor(name,load(name)); editor.render(); open.put(name,editor); p.openInventory(editor.inventory);
      p.sendMessage("Loot-Editor: Inventar-Item anklicken kopiert es; Shift-Klick entfernt. Mengenbereich ist in YAML editierbar.");
    } catch(Exception e) {core.getLogger().warning("Loot-Tabelle "+name+": "+e);p.sendMessage("Tabelle konnte nicht gelesen werden; Serverlog prüfen.");}
    return true;
  }
  private File file(String name) { return new File(directory,name+".yml"); }
  private List<Entry> load(String name) throws Exception {
    File file=file(name); List<Entry> result=new ArrayList<>(); if(!file.exists()) return result;
    YamlConfiguration yaml=new YamlConfiguration(); yaml.load(file);
    ConfigurationSection section=yaml.getConfigurationSection("entries");
    if(section==null) {if(yaml.contains("entries")) throw new IOException("entries muss eine Sektion sein"); return result;}
    for(String key:section.getKeys(false)) {
      ConfigurationSection e=section.getConfigurationSection(key); if(e==null) throw new IOException("Ungültiger Eintrag "+key);
      ItemStack item=e.getItemStack("item"); int weight=e.getInt("weight",0), min=e.getInt("min",0), max=e.getInt("max",0);
      Rarity rarity=Rarity.valueOf(e.getString("rarity","").toUpperCase(Locale.ROOT));
      if(item==null || item.getType().isAir() || weight<1 || weight>1000000 || min<1 || max<min || max>item.getMaxStackSize()) throw new IOException("Ungültiger Eintrag "+key);
      result.add(new Entry(item,weight,min,max,rarity));
    }
    if(result.size()>54) throw new IOException("Maximal 54 Eintraege"); return result;
  }
  private void save(Editor editor) throws IOException {
    if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Loot-Verzeichnis kann nicht erstellt werden");
    YamlConfiguration yaml=new YamlConfiguration(); yaml.createSection("entries");
    for(int i=0;i<editor.entries.size();i++) { Entry e=editor.entries.get(i); String path="entries."+i+".";
      yaml.set(path+"item",e.item()); yaml.set(path+"weight",e.weight()); yaml.set(path+"min",e.min()); yaml.set(path+"max",e.max()); yaml.set(path+"rarity",e.rarity().name()); }
    Path temp=Files.createTempFile(directory.toPath(),"loot-", ".tmp");
    try { yaml.save(temp.toFile()); try {Files.move(temp,file(editor.name).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
      catch(AtomicMoveNotSupportedException ex) { Files.move(temp,file(editor.name).toPath(),StandardCopyOption.REPLACE_EXISTING); }
    } finally {Files.deleteIfExists(temp);}
  }
  @EventHandler public void click(InventoryClickEvent event) {
    if(!(event.getView().getTopInventory().getHolder() instanceof MineRushLootEditor.Editor editor)) return;
    event.setCancelled(true); if(!(event.getWhoClicked() instanceof Player player)) return;
    List<Entry> before=new ArrayList<>(editor.entries); int raw=event.getRawSlot();
    if(raw>=0 && raw<54 && raw<editor.entries.size()) {
      Entry old=editor.entries.get(raw);
      if(event.getClick()==ClickType.SHIFT_LEFT) editor.entries.remove(raw);
      else if(event.getClick()==ClickType.LEFT && old.weight()<1000000) editor.entries.set(raw,new Entry(old.item(),old.weight()+1,old.min(),old.max(),old.rarity()));
      else if(event.getClick()==ClickType.RIGHT && old.weight()>1) editor.entries.set(raw,new Entry(old.item(),old.weight()-1,old.min(),old.max(),old.rarity()));
      else if(event.getClick()==ClickType.SHIFT_RIGHT) editor.entries.set(raw,new Entry(old.item(),old.weight(),old.min(),old.max(),Rarity.values()[(old.rarity().ordinal()+1)%Rarity.values().length]));
    } else if(raw>=54 && event.getClickedInventory()!=null && editor.entries.size()<54) {
      ItemStack item=event.getCurrentItem(); if(item!=null && !item.getType().isAir()) {ItemStack copy=item.clone(); int amount=copy.getAmount(); copy.setAmount(1); editor.entries.add(new Entry(copy,1,amount,amount,Rarity.GEWOEHNLICH));}
    }
    if(!before.equals(editor.entries)) try {save(editor);} catch(IOException ex) {editor.entries.clear();editor.entries.addAll(before);core.getLogger().warning("Loot speichern: "+ex);player.sendMessage("Speichern fehlgeschlagen; Änderung verworfen.");}
    editor.render();
  }
  @EventHandler public void drag(InventoryDragEvent e) {if(e.getView().getTopInventory().getHolder() instanceof MineRushLootEditor.Editor) e.setCancelled(true);}
  @EventHandler public void close(InventoryCloseEvent e) {if(e.getInventory().getHolder() instanceof MineRushLootEditor.Editor editor) open.remove(editor.name,editor);}
}
