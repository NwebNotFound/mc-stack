package de.mcstack.craftcore;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

final class UserMenu implements Listener, CommandExecutor, TabCompleter {
  private final CraftCore plugin;
  private final NamespacedKey key;
  private record Entry(String name, Material material, String command, String description, boolean suggest) {
    Entry(String n, Material m, String c, String d){this(n,m,c,d,false);}
  }
  private static final class Holder implements InventoryHolder {
    private Inventory inventory;
    private final List<Entry> entries;
    Holder(List<Entry> entries){this.entries=entries;}
    public Inventory getInventory(){return inventory;}
  }
  UserMenu(CraftCore plugin){this.plugin=plugin;this.key=new NamespacedKey(plugin,"menu_compass");}
  void start(){
    for(String name:List.of("menu","help")){PluginCommand c=plugin.getCommand(name);c.setExecutor(this);c.setTabCompleter(this);}
    Bukkit.getPluginManager().registerEvents(this,plugin);
  }
  static List<String> complete(String prefix,String... choices){return Arrays.stream(choices).filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();}
  private static Component prefix(){return Component.text("[Craft] ",NamedTextColor.GOLD);}
  private static void button(Player p,String label,String command,boolean execute){
    p.sendMessage(prefix().append(Component.text(label,NamedTextColor.AQUA).clickEvent(execute?ClickEvent.runCommand(command):ClickEvent.suggestCommand(command)).hoverEvent(HoverEvent.showText(Component.text(execute?"Klicken zum Öffnen":"Klicken und Eingabe ergänzen")))));
  }
  static void help(Player p,String category){
    switch(category.toLowerCase(Locale.ROOT)){
      case "team" -> {button(p,"Teaminfo", "/team info",true);button(p,"Team erstellen", "/team create ",false);button(p,"Spieler einladen", "/team invite ",false);button(p,"Einladung annehmen", "/team join ",false);button(p,"Team verlassen", "/team leave",false);button(p,"Mitglied entfernen", "/team kick ",false);button(p,"Rolle ändern", "/team promote ",false);button(p,"Rolle zurücksetzen", "/team demote ",false);}
      case "pay" -> button(p,"CP senden", "/pay ",false);
      case "handel" -> {button(p,"Bank und Markt", "/bank",true);button(p,"Kontostand", "/balance",true);button(p,"CP senden", "/pay ",false);button(p,"Spielershops", "/shop",true);button(p,"Auktionshaus", "/ah",true);button(p,"Handel anfragen", "/trade ",false);button(p,"Handel annehmen", "/trade accept",true);}
      case "teleport" -> {button(p,"Zum Lobby-Spawn", "/spawn",true);button(p,"Team-Teleport", "/tpteam",true);button(p,"Zum Home", "/home",true);button(p,"Home setzen", "/sethome",true);button(p,"Benanntes Home", "/home ",false);}
      case "welt" -> {button(p,"Claims anzeigen", "/claim list",true);button(p,"Chunk beanspruchen", "/claim",true);button(p,"Chunk freigeben", "/unclaim",true);button(p,"Parkour", "/parkour",true);}
      default -> {button(p,"Handel und CP", "/help handel",true);button(p,"Teleport", "/help teleport",true);button(p,"Team", "/help team",true);button(p,"Welt und Parkour", "/help welt",true);button(p,"Menü öffnen", "/menu",true);}
    }
  }
  void open(Player p,String menu) {
    List<Entry> entries=new ArrayList<>();
    switch(menu) {
      case "menu" -> {entries.add(new Entry("Statistik",Material.CLOCK,"stats","Spielzeit, Meilensteine und Abzeichen")); entries.add(new Entry("Bank",Material.EMERALD,"bank","Waren und Preise")); entries.add(new Entry("Shop",Material.CHEST,"shop","Spielershops")); entries.add(new Entry("Auktionshaus",Material.GOLDEN_HORSE_ARMOR,"ah","Angebote")); entries.add(new Entry("Homes",Material.RED_BED,"home","Deine Homes")); entries.add(new Entry("Team-Teleport",Material.PLAYER_HEAD,"tpteam","Zu einem Teammitglied teleportieren"));}
      case "pay" -> entries.add(new Entry("CP senden",Material.GOLD_INGOT,"pay ","Spieler und Betrag im Chat ergaenzen",true));
      case "trade" -> {entries.add(new Entry("Handel anfragen",Material.CRAFTING_TABLE,"trade ","Spieler im Chat ergaenzen",true));entries.add(new Entry("Anfrage annehmen",Material.LIME_WOOL,"trade accept","Anfrage in Reichweite annehmen"));}
      case "team" -> {entries.add(new Entry("Teaminfo",Material.BOOK,"team info","Dein Team")); for(String op:List.of("create","invite","join","kick","promote","demote")) entries.add(new Entry(op,Material.PLAYER_HEAD,"team "+op+" ","Eingabe im Chat ergaenzen",true)); entries.add(new Entry("Team verlassen",Material.OAK_DOOR,"team leave","Team verlassen oder aufloesen"));}
      case "claim" -> {entries.add(new Entry("Chunk beanspruchen",Material.GRASS_BLOCK,"claim create","Aktuellen Chunk beanspruchen"));entries.add(new Entry("Claims anzeigen",Material.MAP,"claim list","Eigene und Team-Claims"));entries.add(new Entry("Chunk freigeben",Material.BARRIER,"unclaim","Aktuellen Chunk freigeben"));}
      case "home" -> {
        if(!SpawnRoute.isSurvival(System.getenv().getOrDefault("CRAFTCORE_SERVER",""))) entries.add(new Entry("Nur auf Survival",Material.BARRIER,"","Homes sind nur auf Survival verfuegbar"));
        else try {int slots=plugin.tx(c->HomeSlots.lock(c,System.getenv().getOrDefault("CRAFTCORE_SERVER","Server"),p.getUniqueId().toString())); List<String> homes=plugin.tx(c->{List<String> out=new ArrayList<>();try(var q=c.prepareStatement("SELECT name FROM homes WHERE server=? AND uuid=? ORDER BY name LIMIT 45")){q.setString(1,System.getenv().getOrDefault("CRAFTCORE_SERVER","Server"));q.setString(2,p.getUniqueId().toString());try(var r=q.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;}); for(String name:homes)entries.add(new Entry(name,Material.RED_BED,"home "+name,"Zum Home teleportieren"));entries.add(new Entry("Home setzen",Material.COMPASS,"sethome ","Name im Chat ergaenzen oder /sethome nutzen",true)); if(slots<5){long price=HomeSlots.price(plugin,slots);if(price<=0)throw new IllegalArgumentException("Ungueltiger Home-Preis");entries.add(new Entry("Slot "+(slots+1)+" kaufen",Material.GOLD_INGOT,"@homebuy "+slots+" "+price,"Preis: "+Money.format(price)+" | Klick zum Kaufen"));}else entries.add(new Entry("Home-Slots: "+slots,Material.BOOK,"","Maximum erreicht"));} catch(Exception ex){plugin.getLogger().warning("Homes-Menue: "+ex);entries.add(new Entry("Homes nicht verfuegbar",Material.BARRIER,"","Bitte spaeter erneut versuchen"));}
      }
      case "vip" -> entries.add(new Entry("Noch nicht verfuegbar",Material.BARRIER,"","VIP-Server sind noch nicht eingerichtet"));
      default -> {return;}
    }
    int size=Math.max(9,Math.min(54,((entries.size()+8)/9)*9));Holder h=new Holder(entries);Inventory inv=Bukkit.createInventory(h,size,Component.text("Craft - "+menu,NamedTextColor.GOLD));h.inventory=inv;
    for(int i=0;i<entries.size() && i<size;i++){Entry e=entries.get(i);ItemStack item=new ItemStack(e.material());ItemMeta m=item.getItemMeta();m.displayName(Component.text(e.name(),NamedTextColor.AQUA));m.lore(List.of(Component.text(e.description(),NamedTextColor.GRAY)));item.setItemMeta(m);inv.setItem(i,item);}p.openInventory(inv);
  }
  public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("[Craft] Nur im Spiel verfuegbar.");return true;}if(command.getName().equals("menu"))open(p,"menu");else help(p,args.length==0?"":args[0]);return true;}
  public List<String> onTabComplete(CommandSender s,Command c,String alias,String[] args){return c.getName().equals("help")&&args.length==1?complete(args[0],"handel","teleport","team","welt"):List.of();}
  @EventHandler public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;e.setCancelled(true);int slot=e.getRawSlot();if(slot>=0&&slot<h.entries.size()&&e.getWhoClicked() instanceof Player p){Entry entry=h.entries.get(slot);if(entry.command().isEmpty())return;p.closeInventory();if(entry.command().startsWith("@homebuy ")){try{String[] parts=entry.command().split(" ");int expected=Integer.parseInt(parts[1]);long price=Long.parseLong(parts[2]);if(price!=HomeSlots.price(plugin,expected)){p.sendMessage("Preis hat sich geaendert. Bitte Menue erneut oeffnen.");return;}String result=plugin.tx(c->HomeSlots.buy(c,System.getenv().getOrDefault("CRAFTCORE_SERVER","Server"),p.getUniqueId().toString(),expected,price));p.sendMessage(result);open(p,"home");}catch(Exception ex){plugin.getLogger().warning("Home-Kauf: "+ex);p.sendMessage("Home-Kauf fehlgeschlagen.");}return;}if(entry.suggest())button(p,"Eingabe ergaenzen: /"+entry.command(),"/"+entry.command(),false);else p.performCommand(entry.command());}}
  @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);}
  static String nearest(String input, Collection<String> choices) {
    String best=null; int score=3; String word=input.toLowerCase(Locale.ROOT);
    for(String candidate:choices){int[] prev=new int[candidate.length()+1];for(int j=0;j<prev.length;j++)prev[j]=j;for(int i=1;i<=word.length();i++){int[] next=new int[prev.length];next[0]=i;for(int j=1;j<next.length;j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(word.charAt(i-1)==candidate.charAt(j-1)?0:1));prev=next;}int d=prev[candidate.length()];if(d<score){score=d;best=candidate;}}
    return best;
  }
  @EventHandler(ignoreCancelled=true) public void typo(PlayerCommandPreprocessEvent e) {
    String raw=e.getMessage().substring(1).split("\\s+",2)[0].toLowerCase(Locale.ROOT);
    if(raw.contains(":") || Bukkit.getCommandMap().getCommand(raw)!=null)return;
    String suggestion=nearest(raw,List.of("bank","market","shop","ah","pay","team","claim","home","tpteam","trade","vip","menu"));
    if(suggestion!=null){e.setCancelled(true);button(e.getPlayer(),"Unbekannter Befehl. Meintest du /"+suggestion+"?","/"+suggestion,false);}
  }
  @EventHandler public void join(PlayerJoinEvent e){Player p=e.getPlayer();if(!"lobby".equalsIgnoreCase(System.getenv().getOrDefault("CRAFTCORE_SERVER","")))return;for(ItemStack i:p.getInventory().getContents())if(isCompass(i))return;ItemStack existing=p.getInventory().getItem(8);if(existing!=null&&!existing.getType().isAir())return;ItemStack item=new ItemStack(Material.COMPASS);ItemMeta m=item.getItemMeta();m.displayName(Component.text("Menü",NamedTextColor.GOLD));m.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);item.setItemMeta(m);p.getInventory().setItem(8,item);}
  private boolean isCompass(ItemStack i){return i!=null&&i.hasItemMeta()&&i.getItemMeta().getPersistentDataContainer().has(key,PersistentDataType.BYTE);}
  @EventHandler(ignoreCancelled=true) public void use(PlayerInteractEvent e){if(e.getHand()!=EquipmentSlot.HAND||!isCompass(e.getItem()))return;switch(e.getAction()){case RIGHT_CLICK_AIR,RIGHT_CLICK_BLOCK -> {e.setCancelled(true);open(e.getPlayer(),"menu");}default -> {}}}
}
