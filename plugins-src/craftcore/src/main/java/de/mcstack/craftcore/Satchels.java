package de.mcstack.craftcore;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.StonecutterInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/** Mystery Satchels. Rolls happen here, on the server, after CP is taken. */
final class Satchels implements CommandExecutor, TabCompleter, Listener {
  private final CraftCore core;
  private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

  Satchels(CraftCore core) { this.core = core; }

  void start() throws Exception {
    core.tx(c -> { SatchelStore.prepare(c); return null; });
    var command = core.getCommand("satchel");
    command.setExecutor(this);
    command.setTabCompleter(this);
    Bukkit.getPluginManager().registerEvents(this, core);
  }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) { sender.sendMessage("Nur im Spiel."); return true; }
    if (!SpawnRoute.isSurvival(System.getenv().getOrDefault("CRAFTCORE_SERVER", ""))) { player.sendMessage("Satchels gibt es nur auf Survival."); return true; }
    if (args.length == 0) { openList(player); return true; }
    String action = args[0].toLowerCase(Locale.ROOT);
    if (action.equals("create") || action.equals("additem")) {
      if (!admin(player)) { player.sendMessage("Dafuer brauchst du Satchel-Rechte."); return true; }
    }
    try {
      switch (action) {
        case "create" -> create(player, args);
        case "additem" -> addItem(player, args);
        case "open" -> { if (args.length < 2) player.sendMessage("Nutze /satchel open <name> [1|10]"); else open(player, args[1], args.length >= 3 && args[2].equals("10") ? 10 : 1); }
        default -> openList(player);
      }
    } catch (Exception ex) {
      core.getLogger().warning("Satchel: " + ex.getClass().getSimpleName());
      player.sendMessage("Satchel-Aktion fehlgeschlagen.");
    }
    return true;
  }

  private void create(Player player, String[] args) throws Exception {
    if (args.length < 3) { player.sendMessage("Nutze /satchel create <name> <preis in CP>"); return; }
    String name = args[1];
    if (!name.matches("[A-Za-z0-9_-]{2,32}")) { player.sendMessage("Name: 2 bis 32 Zeichen, Buchstaben, Zahlen, _ und -."); return; }
    long cp;
    try { cp = Long.parseLong(args[2]); } catch (NumberFormatException ex) { player.sendMessage("Preis muss eine ganze Zahl CP sein."); return; }
    if (cp <= 0 || cp > 1_000_000) { player.sendMessage("Preis zwischen 1 und 1000000 CP."); return; }
    int sr = positive(core.getConfig().getInt("satchels.pity-sr", 10), 10);
    int ssr = positive(core.getConfig().getInt("satchels.pity-ssr", 50), 50);
    long cents = Math.multiplyExact(cp, 100);
    try {
      core.tx(c -> { SatchelStore.create(c, name, cents, sr, ssr); return null; });
    } catch (Exception ex) {
      SQLException sql = ex instanceof SQLException direct ? direct : ex.getCause() instanceof SQLException cause ? cause : null;
      if (sql != null && duplicate(sql)) { player.sendMessage("Den Satchel gibt es schon."); return; }
      throw ex;
    }
    player.sendMessage("Satchel " + name + " kostet " + cp + " CP. Pity SR " + sr + ", SSR " + ssr + ".");
  }

  private void addItem(Player player, String[] args) throws Exception {
    if (args.length < 3) { player.sendMessage("Nutze /satchel additem <satchel> <C|UC|R|SR|SSR|SSR+> [gewicht]"); return; }
    SatchelOdds.Rarity rarity = SatchelOdds.Rarity.parse(args[2]);
    if (rarity == null) { player.sendMessage("Seltenheit: C, UC, R, SR, SSR oder SSR+."); return; }
    int weight = args.length >= 4 ? parseWeight(args[3]) : configuredWeight(rarity);
    if (weight <= 0) { player.sendMessage("Gewicht muss zwischen 1 und 1000000 liegen."); return; }
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (hand == null || hand.getType().isAir()) { player.sendMessage("Nimm das Item in die Hand."); return; }
    ItemStack one = hand.clone();
    one.setAmount(1);
    byte[] bytes = one.serializeAsBytes();
    String name = args[1];
    boolean stored = core.tx(c -> {
      if (SatchelStore.find(c, name).isEmpty()) return false;
      SatchelStore.addItem(c, name, rarity.label(), weight, bytes);
      return true;
    });
    player.sendMessage(stored ? "Item als " + rarity.label() + " mit Gewicht " + weight + " hinzugefuegt." : "Satchel nicht gefunden.");
  }

  private void openList(Player player) {
    UUID id = player.getUniqueId();
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> {
      List<SatchelStore.Satchel> satchels;
      List<List<SatchelOdds.Share>> odds = new ArrayList<>();
      try {
        satchels = core.tx(SatchelStore::enabled);
        for (SatchelStore.Satchel satchel : satchels) {
          List<SatchelOdds.Entry> entries = entries(core.tx(c -> SatchelStore.items(c, satchel.name())));
          odds.add(entries.isEmpty() ? List.of() : SatchelOdds.shares(entries));
        }
      } catch (Exception ex) {
        core.getLogger().warning("Satchel-Liste: " + ex.getClass().getSimpleName());
        Bukkit.getScheduler().runTask(core, () -> player.sendMessage("Satchels gerade nicht verfuegbar."));
        return;
      }
      Bukkit.getScheduler().runTask(core, () -> showList(player, id, satchels, odds));
    });
  }

  private void showList(Player player, UUID id, List<SatchelStore.Satchel> satchels, List<List<SatchelOdds.Share>> odds) {
    if (!player.isOnline() || !player.getUniqueId().equals(id)) return;
    ListHolder holder = new ListHolder(satchels.stream().map(SatchelStore.Satchel::name).toList());
    Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Mystery Satchels", NamedTextColor.GOLD));
    holder.inventory = inv;
    for (int i = 0; i < satchels.size() && i < 27; i++) {
      SatchelStore.Satchel satchel = satchels.get(i);
      ItemStack icon = new ItemStack(Material.BUNDLE);
      ItemMeta meta = icon.getItemMeta();
      meta.displayName(Component.text(satchel.name(), NamedTextColor.AQUA));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.text("Preis: " + Money.format(satchel.priceCents()) + " je Zug", NamedTextColor.GOLD));
      lore.add(Component.text("Odds sind Basisgewichte. SR nach " + satchel.pitySr() + ", SSR nach " + satchel.pitySsr() + ".", NamedTextColor.GRAY));
      lore.add(Component.text("Links: 1 Zug   Rechts: 10 Zuege", NamedTextColor.GRAY));
      for (SatchelOdds.Share share : odds.get(i)) {
        if (share.weight() <= 0) continue;
        lore.add(Component.text(share.rarity().label() + " " + share.percent(), color(share.rarity())));
      }
      meta.lore(lore);
      icon.setItemMeta(meta);
      inv.setItem(i, icon);
    }
    player.openInventory(inv);
  }

  private void open(Player player, String name, int count) {
    if (count != 1 && count != 10) { player.sendMessage("Ein Zug oder zehn."); return; }
    if (!busy.add(player.getUniqueId())) { player.sendMessage("Ein Satchel wird schon geoeffnet."); return; }
    UUID id = player.getUniqueId();
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> {
      SatchelStore.Buy purchase;
      try { purchase = core.tx(c -> SatchelStore.buy(c, id.toString(), name, count, new SecureRandom())); }
      catch (Exception ex) {
        busy.remove(id);
        core.getLogger().warning("Satchel-Kauf: " + ex.getClass().getSimpleName());
        Bukkit.getScheduler().runTask(core, () -> { if (player.isOnline()) player.sendMessage("Satchel-Kauf fehlgeschlagen."); });
        return;
      }
      Bukkit.getScheduler().runTask(core, () -> finish(player, id, purchase));
    });
  }

  private void finish(Player player, UUID id, SatchelStore.Buy purchase) {
    busy.remove(id);
    if (!purchase.ok()) { if (player.isOnline()) player.sendMessage(purchase.message()); return; }
    if (!player.isOnline()) return;
    List<ItemStack> rewards = deliver(player, purchase.claims());
    if (rewards.isEmpty()) return;
    player.sendMessage("Satchel geoeffnet. Verkauf der Belohnung ist auf " + Money.format(purchase.claims().get(0).capCents()) + " je Stueck begrenzt.");
    animate(player, rewards, purchase.claims().stream().map(SatchelStore.Claim::rarity).toList());
  }

  private List<ItemStack> deliver(Player player, List<SatchelStore.Claim> claims) {
    List<ItemStack> given = new ArrayList<>();
    for (SatchelStore.Claim claim : claims) {
      boolean won;
      try { won = core.tx(c -> SatchelStore.take(c, claim.id(), player.getUniqueId().toString())); }
      catch (Exception ex) { core.getLogger().warning("Satchel-Abholung: " + ex.getClass().getSimpleName()); continue; }
      if (!won) continue;
      if (!player.isOnline()) {
        try { core.tx(c -> { SatchelStore.insertClaim(c, UUID.randomUUID().toString(), player.getUniqueId().toString(), claim.rarity(), claim.bytes(), claim.capCents()); return null; }); }
        catch (Exception ex) { core.getLogger().severe("Satchel-Abholung nicht zurueckgelegt: " + claim.id() + " " + ex); }
        continue;
      }
      ItemStack item = ItemStack.deserializeBytes(claim.bytes());
      SatchelItems.stamp(item, claim.capCents());
      var left = player.getInventory().addItem(item);
      if (!left.isEmpty()) {
        try { core.tx(c -> { for (ItemStack rest : left.values()) SatchelStore.insertClaim(c, UUID.randomUUID().toString(), player.getUniqueId().toString(), claim.rarity(), rest.serializeAsBytes(), claim.capCents()); return null; }); }
        catch (Exception ex) { core.getLogger().severe("Satchel-Rest nicht gespeichert: " + ex); }
      }
      given.add(item);
    }
    return given;
  }

  @EventHandler public void join(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID id = player.getUniqueId();
    Bukkit.getScheduler().runTaskAsynchronously(core, () -> {
      List<SatchelStore.Claim> claims;
      try { claims = core.tx(c -> SatchelStore.openClaims(c, id.toString())); }
      catch (Exception ex) { core.getLogger().warning("Satchel-Abholung: " + ex.getClass().getSimpleName()); return; }
      if (claims.isEmpty()) return;
      Bukkit.getScheduler().runTask(core, () -> {
        if (!player.isOnline()) return;
        List<ItemStack> given = deliver(player, claims);
        if (!given.isEmpty()) player.sendMessage("Satchel-Belohnung abgeholt. Verkauf ist auf den Zugpreis begrenzt.");
      });
    });
  }

  private void animate(Player player, List<ItemStack> rewards, List<String> rarities) {
    SpinHolder holder = new SpinHolder();
    Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Satchel", NamedTextColor.LIGHT_PURPLE));
    holder.inventory = inv;
    player.openInventory(inv);
    int[] shown = {0};
    int[] next = {0};
    int[] gap = {2};
    BukkitTask[] task = new BukkitTask[1];
    task[0] = Bukkit.getScheduler().runTaskTimer(core, () -> {
      if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) { task[0].cancel(); return; }
      if (shown[0] < next[0]) { shown[0]++; return; }
      int step = next[0];
      next[0] = step + gap[0];
      gap[0] = Math.min(10, gap[0] + (step > 6 ? 1 : 0));
      if (step >= 14) {
        for (int i = 0; i < rewards.size() && i < 27; i++) inv.setItem(rewards.size() == 1 ? 13 : i, rewards.get(i).clone());
        SatchelOdds.Rarity best = SatchelOdds.Rarity.C;
        for (String rarity : rarities) {
          SatchelOdds.Rarity parsed = SatchelOdds.Rarity.parse(rarity);
          if (parsed != null && parsed.ordinal() > best.ordinal()) best = parsed;
        }
        player.playSound(player.getLocation(), best.atLeast(SatchelOdds.Rarity.SSR) ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        if (best.atLeast(SatchelOdds.Rarity.SSR)) player.showTitle(Title.title(Component.text(best.label(), color(best)), Component.text("Mystery Satchel")));
        task[0].cancel();
        Bukkit.getScheduler().runTaskLater(core, () -> { if (player.isOnline() && player.getOpenInventory().getTopInventory() == inv) player.closeInventory(); }, 60L);
        return;
      }
      SatchelOdds.Rarity flash = SatchelOdds.Rarity.values()[step % SatchelOdds.Rarity.values().length];
      inv.setItem(13, pane(flash));
      player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1f + step / 20f);
      shown[0]++;
    }, 1L, 1L);
  }

  private ItemStack pane(SatchelOdds.Rarity rarity) {
    Material glass = switch (rarity) {
      case C -> Material.GRAY_STAINED_GLASS_PANE;
      case UC -> Material.GREEN_STAINED_GLASS_PANE;
      case R -> Material.BLUE_STAINED_GLASS_PANE;
      case SR -> Material.PURPLE_STAINED_GLASS_PANE;
      case SSR -> Material.YELLOW_STAINED_GLASS_PANE;
      case SSR_PLUS -> Material.RED_STAINED_GLASS_PANE;
    };
    ItemStack item = new ItemStack(glass);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text(rarity.label(), color(rarity)));
    item.setItemMeta(meta);
    return item;
  }

  private static NamedTextColor color(SatchelOdds.Rarity rarity) {
    return switch (rarity) {
      case C -> NamedTextColor.GRAY;
      case UC -> NamedTextColor.GREEN;
      case R -> NamedTextColor.BLUE;
      case SR -> NamedTextColor.LIGHT_PURPLE;
      case SSR -> NamedTextColor.GOLD;
      case SSR_PLUS -> NamedTextColor.RED;
    };
  }

  private List<SatchelOdds.Entry> entries(List<SatchelStore.Item> items) {
    List<SatchelOdds.Entry> entries = new ArrayList<>();
    for (SatchelStore.Item item : items) {
      SatchelOdds.Rarity rarity = SatchelOdds.Rarity.parse(item.rarity());
      if (rarity != null && item.weight() > 0) entries.add(new SatchelOdds.Entry(item.id(), rarity, item.weight()));
    }
    return entries;
  }

  private int configuredWeight(SatchelOdds.Rarity rarity) {
    int configured = core.getConfig().getInt("satchels.weights." + rarity.name(), -1);
    return configured > 0 ? configured : SatchelOdds.defaultWeight(rarity);
  }

  private static int parseWeight(String raw) {
    try { int n = Integer.parseInt(raw); return n >= 1 && n <= 1_000_000 ? n : -1; }
    catch (NumberFormatException ex) { return -1; }
  }

  private static int positive(int value, int fallback) { return value > 0 ? value : fallback; }

  private static boolean admin(Player player) {
    return player.hasPermission("craftcore.satchel.admin") || player.hasPermission("craftcore.admin");
  }

  private static boolean duplicate(SQLException ex) { return ex.getErrorCode() == 1062 || "23505".equals(ex.getSQLState()); }

  @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) return UserMenu.complete(args[0], "create", "additem", "open");
    if (args.length == 3 && args[0].equalsIgnoreCase("additem")) return UserMenu.complete(args[2], "C", "UC", "R", "SR", "SSR", "SSR+");
    if (args.length == 3 && args[0].equalsIgnoreCase("open")) return UserMenu.complete(args[2], "1", "10");
    return List.of();
  }

  @EventHandler(ignoreCancelled = true) public void prepareCraft(PrepareItemCraftEvent event) {
    if (SatchelCraft.usesCapped(tagged(event.getInventory().getMatrix()))) event.getInventory().setResult(null);
  }

  @EventHandler(ignoreCancelled = true) public void craft(CraftItemEvent event) {
    if (!(event.getInventory() instanceof CraftingInventory crafting) || !SatchelCraft.usesCapped(tagged(crafting.getMatrix()))) return;
    event.setCancelled(true);
    if (event.getWhoClicked() instanceof Player player) player.sendMessage("Satchel-Belohnungen kann man nicht verarbeiten.");
  }

  @EventHandler(ignoreCancelled = true) public void prepareSmith(PrepareSmithingEvent event) {
    if (smithTagged(event.getInventory())) event.setResult(null);
  }

  @EventHandler(ignoreCancelled = true) public void smith(SmithItemEvent event) {
    if (!smithTagged(event.getInventory())) return;
    event.setCancelled(true);
    if (event.getWhoClicked() instanceof Player player) player.sendMessage("Satchel-Belohnungen kann man nicht verarbeiten.");
  }

  @EventHandler(ignoreCancelled = true) public void smelt(FurnaceSmeltEvent event) {
    if (SatchelItems.tagged(event.getSource())) event.setCancelled(true);
  }

  /** Campfires, smokers and blast furnaces cook through BlockCookEvent; the result would lose the sell cap. */
  @EventHandler(ignoreCancelled = true) public void cook(BlockCookEvent event) {
    if (SatchelItems.tagged(event.getSource())) event.setCancelled(true);
  }

  /** A placed and re-mined block drops a fresh, untagged item, which would strip the sell cap. */
  @EventHandler(ignoreCancelled = true) public void place(BlockPlaceEvent event) {
    if (!SatchelItems.tagged(event.getItemInHand())) return;
    event.setCancelled(true);
    event.getPlayer().sendMessage("Satchel-Belohnungen kann man nicht platzieren.");
  }

  /** Stonecutter results are new stacks without the cap tag: block taking the result (slot 1) of a tagged input. */
  @EventHandler(ignoreCancelled = true) public void stonecut(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory() instanceof StonecutterInventory cutter) || event.getRawSlot() != 1 || !SatchelItems.tagged(cutter.getInputItem())) return;
    event.setCancelled(true);
    if (event.getWhoClicked() instanceof Player player) player.sendMessage("Satchel-Belohnungen kann man nicht verarbeiten.");
  }

  private static boolean smithTagged(SmithingInventory inventory) {
    return SatchelCraft.usesCapped(SatchelItems.tagged(inventory.getInputEquipment()), SatchelItems.tagged(inventory.getInputMineral()), templateTagged(inventory));
  }

  private static boolean templateTagged(SmithingInventory inventory) {
    try { return SatchelItems.tagged(inventory.getInputTemplate()); }
    catch (NoSuchMethodError ex) { return false; }
  }

  private static boolean[] tagged(ItemStack[] items) {
    boolean[] flags = new boolean[items.length];
    for (int i = 0; i < items.length; i++) flags[i] = SatchelItems.tagged(items[i]);
    return flags;
  }

  @EventHandler public void click(InventoryClickEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof SpinHolder) { event.setCancelled(true); return; }
    if (!(event.getView().getTopInventory().getHolder() instanceof ListHolder holder)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)) return;
    int slot = event.getRawSlot();
    if (slot < 0 || slot >= holder.names.size()) return;
    open(player, holder.names.get(slot), event.isRightClick() ? 10 : 1);
  }

  @EventHandler public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof ListHolder || event.getView().getTopInventory().getHolder() instanceof SpinHolder) event.setCancelled(true);
  }

  private static final class ListHolder implements InventoryHolder {
    final List<String> names;
    Inventory inventory;
    ListHolder(List<String> names) { this.names = names; }
    public Inventory getInventory() { return inventory; }
  }

  private static final class SpinHolder implements InventoryHolder {
    Inventory inventory;
    public Inventory getInventory() { return inventory; }
  }
}
